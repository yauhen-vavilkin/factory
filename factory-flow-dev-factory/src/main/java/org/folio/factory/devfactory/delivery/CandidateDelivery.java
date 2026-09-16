package org.folio.factory.devfactory.delivery;

import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.verification.VerificationReceipt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact Git transport in the trusted control plane. Credentials never enter a workload. */
public class CandidateDelivery {
    private final CandidateFreezer freezer;
    private final String remoteRoot;

    public CandidateDelivery(CandidateFreezer freezer) { this(freezer, "https://github.com/"); }

    // Package-private local Git fixture transport; production always targets github.com.
    CandidateDelivery(CandidateFreezer freezer, String remoteRoot) {
        this.freezer = freezer;
        this.remoteRoot = remoteRoot;
    }

    public DeliveryReceipt deliver(String executionId, String issueKey, String summary, String sourceUrl,
                                   Candidate candidate, VerificationReceipt verification,
                                   DeliveryTarget target, String githubToken) {
        Objects.requireNonNull(verification, "Verification receipt is missing").requireVerified(executionId, candidate);
        Objects.requireNonNull(target, "Delivery target is missing").requireAuthorized();
        if (githubToken == null || githubToken.isBlank()) throw new DeliveryBlockedException("FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
        if (issueKey == null || !issueKey.matches("[A-Z][A-Z0-9]*-[0-9]+")) throw new DeliveryBlockedException("Invalid delivery issue key");
        String branch = "factory/" + issueKey.toLowerCase(java.util.Locale.ROOT) + "-" + candidate.patchSha256().substring(0, 8);
        try {
            CandidateFreezer.git(null, "check-ref-format", "--branch", branch);
            CandidateFreezer.git(null, "check-ref-format", "--branch", target.baseBranch());
        } catch (RuntimeException e) {
            throw new DeliveryBlockedException("Delivery branch configuration is invalid");
        }
        String remote = remoteRoot + target.repository() + ".git";
        Map<String, String> auth = Map.of("GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "http.https://github.com/.extraHeader",
                "GIT_CONFIG_VALUE_0", "Authorization: Basic " + Base64.getEncoder().encodeToString(
                        ("x-access-token:" + githubToken).getBytes(StandardCharsets.UTF_8)));
        Path workspace = freezer.reconstruct(sourceUrl, candidate);
        try {
            requireRemote(workspace, auth, "fetch", "--quiet", remote, "refs/heads/" + target.baseBranch());
            Processes.Result ancestry = gitResult(workspace, Map.of(), "merge-base", "--is-ancestor", candidate.baseSha(), "FETCH_HEAD");
            if (ancestry.exitCode() == 1) {
                throw new DeliveryBlockedException("Destination base no longer contains the candidate base; synchronize the fork");
            }
            requireSuccess(ancestry, "merge-base");
            String date = CandidateFreezer.git(workspace, "show", "-s", "--format=%cI", candidate.baseSha()).strip();
            Map<String, String> identity = Map.of("GIT_AUTHOR_NAME", "Factory", "GIT_AUTHOR_EMAIL", "factory@localhost",
                    "GIT_COMMITTER_NAME", "Factory", "GIT_COMMITTER_EMAIL", "factory@localhost",
                    "GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date);
            String title = issueKey + ": " + (summary == null ? "Developer Flow candidate" : summary.replaceAll("[\\r\\n]", " ").strip());
            String commit = git(workspace, identity, "commit-tree", candidate.treeSha(), "-p", candidate.baseSha(), "-m", title).strip();
            String ref = "refs/heads/" + branch;
            Processes.Result remoteBranch = gitResult(workspace, auth, "ls-remote", "--heads", remote, ref);
            if (remoteBranch.exitCode() != 0 && permanentRemoteFailure(remoteBranch)) {
                throw new DeliveryBlockedException("Delivery destination is missing or unauthorized");
            }
            requireSuccess(remoteBranch, "ls-remote");
            String existing = remoteBranch.output().strip();
            if (!existing.isEmpty() && !existing.split("\\s+")[0].equals(commit)) {
                throw new DeliveryBlockedException("Delivery branch conflicts with an existing remote commit");
            }
            if (existing.isEmpty()) {
                Processes.Result push = gitResult(workspace, auth, "push", "--porcelain", remote, commit + ":" + ref);
                String error = push.error().toLowerCase(java.util.Locale.ROOT);
                if (push.exitCode() != 0 && (permanentRemoteFailure(push) || error.contains("non-fast-forward")
                        || error.contains("rejected"))) {
                    throw new DeliveryBlockedException("Delivery branch was rejected or now conflicts at the destination");
                }
                requireSuccess(push, "push");
            }
            requireRemote(workspace, auth, "fetch", "--quiet", remote, ref);
            if (!CandidateFreezer.git(workspace, "rev-parse", "FETCH_HEAD").strip().equals(commit)
                    || !CandidateFreezer.git(workspace, "rev-parse", "FETCH_HEAD^{tree}").strip().equals(candidate.treeSha())
                    || !CandidateFreezer.git(workspace, "rev-parse", "FETCH_HEAD^").strip().equals(candidate.baseSha())) {
                throw new DeliveryBlockedException("Remote delivery identity does not match the verified candidate");
            }
            return new DeliveryReceipt(executionId, target.repository(), branch, candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), commit);
        } finally { CandidateFreezer.cleanup(workspace); }
    }

    private static String git(Path workspace, Map<String, String> environment, String... args) {
        Processes.Result result = gitResult(workspace, environment, args);
        requireSuccess(result, args[0]);
        return result.output();
    }

    private static Processes.Result gitResult(Path workspace, Map<String, String> environment, String... args) {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "credential.helper="));
        command.addAll(List.of(args));
        return Processes.run(workspace, command, 180, environment);
    }

    private static void requireRemote(Path workspace, Map<String, String> environment, String... args) {
        Processes.Result result = gitResult(workspace, environment, args);
        if (result.exitCode() != 0 && permanentRemoteFailure(result)) {
            throw new DeliveryBlockedException("Delivery destination is missing or unauthorized");
        }
        requireSuccess(result, args[0]);
    }

    private static void requireSuccess(Processes.Result result, String operation) {
        // Remote diagnostics may contain sensitive headers: never copy them into artifacts/logs.
        if (result.exitCode() != 0) throw new IllegalStateException("Delivery Git " + operation + " failed (exit " + result.exitCode() + ")");
    }

    private static boolean permanentRemoteFailure(Processes.Result result) {
        String error = result.error().toLowerCase(java.util.Locale.ROOT);
        return error.contains("authentication failed") || error.contains("repository not found")
                || error.contains("could not read username") || error.contains("error: 401")
                || error.contains("error: 403") || error.contains("error: 404")
                || error.contains("couldn't find remote ref")
                || error.contains("does not appear to be a git repository");
    }
}
