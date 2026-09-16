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
        if (githubToken == null || githubToken.isBlank()) throw new IllegalStateException("FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
        if (issueKey == null || !issueKey.matches("[A-Z][A-Z0-9]*-[0-9]+")) throw new IllegalStateException("Invalid delivery issue key");
        String branch = "factory/" + issueKey.toLowerCase(java.util.Locale.ROOT) + "-" + candidate.patchSha256().substring(0, 8);
        CandidateFreezer.git(null, "check-ref-format", "--branch", branch);
        CandidateFreezer.git(null, "check-ref-format", "--branch", target.baseBranch());
        String remote = remoteRoot + target.repository() + ".git";
        Map<String, String> auth = Map.of("GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "http.https://github.com/.extraHeader",
                "GIT_CONFIG_VALUE_0", "Authorization: Basic " + Base64.getEncoder().encodeToString(
                        ("x-access-token:" + githubToken).getBytes(StandardCharsets.UTF_8)));
        Path workspace = freezer.reconstruct(sourceUrl, candidate);
        try {
            git(workspace, auth, "fetch", "--quiet", remote, "refs/heads/" + target.baseBranch());
            git(workspace, Map.of(), "merge-base", "--is-ancestor", candidate.baseSha(), "FETCH_HEAD");
            String date = CandidateFreezer.git(workspace, "show", "-s", "--format=%cI", candidate.baseSha()).strip();
            Map<String, String> identity = Map.of("GIT_AUTHOR_NAME", "Factory", "GIT_AUTHOR_EMAIL", "factory@localhost",
                    "GIT_COMMITTER_NAME", "Factory", "GIT_COMMITTER_EMAIL", "factory@localhost",
                    "GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date);
            String title = issueKey + ": " + (summary == null ? "Developer Flow candidate" : summary.replaceAll("[\\r\\n]", " ").strip());
            String commit = git(workspace, identity, "commit-tree", candidate.treeSha(), "-p", candidate.baseSha(), "-m", title).strip();
            String ref = "refs/heads/" + branch;
            String existing = git(workspace, auth, "ls-remote", "--heads", remote, ref).strip();
            if (!existing.isEmpty() && !existing.split("\\s+")[0].equals(commit)) {
                throw new IllegalStateException("Delivery branch conflicts with an existing remote commit");
            }
            if (existing.isEmpty()) git(workspace, auth, "push", "--porcelain", remote, commit + ":" + ref);
            git(workspace, auth, "fetch", "--quiet", remote, ref);
            if (!CandidateFreezer.git(workspace, "rev-parse", "FETCH_HEAD").strip().equals(commit)
                    || !CandidateFreezer.git(workspace, "rev-parse", "FETCH_HEAD^{tree}").strip().equals(candidate.treeSha())
                    || !CandidateFreezer.git(workspace, "rev-parse", "FETCH_HEAD^").strip().equals(candidate.baseSha())) {
                throw new IllegalStateException("Remote delivery identity mismatch");
            }
            return new DeliveryReceipt(executionId, target.repository(), branch, candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), commit);
        } finally { CandidateFreezer.delete(workspace); }
    }

    private static String git(Path workspace, Map<String, String> environment, String... args) {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "credential.helper="));
        command.addAll(List.of(args));
        Processes.Result result = Processes.run(workspace, command, 180, environment);
        // Remote diagnostics may contain sensitive headers: never copy them into artifacts/logs.
        if (result.exitCode() != 0) throw new IllegalStateException("Delivery Git " + args[0] + " failed (exit " + result.exitCode() + ")");
        return result.output();
    }
}
