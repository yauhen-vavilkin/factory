package org.folio.factory.devfactory.delivery;

import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.Git;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.verification.VerificationReceipt;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Exact Git transport in the trusted control plane. Credentials never enter a workload. */
public class CandidateDelivery {
    private final CandidateFreezer freezer;
    private final String remoteRoot;

    public CandidateDelivery(CandidateFreezer freezer) {
        this(freezer, "https://github.com/");
    }

    // Package-private local Git fixture transport; production always targets github.com.
    CandidateDelivery(CandidateFreezer freezer, String remoteRoot) {
        this.freezer = freezer;
        this.remoteRoot = remoteRoot;
    }

    /** Delivery Git operations bounded by the task attempt's remaining whole-attempt budget. */
    public DeliveryReceipt deliver(
            String executionId,
            String issueKey,
            String summary,
            String sourceUrl,
            Candidate candidate,
            VerificationReceipt verification,
            DeliveryTarget target,
            String githubToken,
            boolean stableBranch,
            int gitTimeoutSeconds) {
        var request = new Request(executionId, issueKey, summary, sourceUrl, candidate, verification, target,
                githubToken, gitTimeoutSeconds);
        requireDeliverable(request);
        var branch = branchFor(executionId, issueKey, candidate, stableBranch);
        requireValidBranches(branch, target.baseBranch());
        return publishFromWorkspace(request, branch);
    }

    /**
     * The single source of the delivered branch name, shared with publication-intent
     * persistence so the recorded head identity cannot drift from the pushed one.
     */
    public static String branchFor(
            String executionId, String issueKey, Candidate candidate, boolean stableBranch) {
        return "factory/"
                + issueKey.toLowerCase(Locale.ROOT)
                + "-"
                + (stableBranch
                        ? UUID.fromString(executionId).toString()
                        : candidate.patchSha256().substring(0, 8));
    }

    private static void requireDeliverable(Request request) {
        Objects.requireNonNull(request.verification(), "Verification receipt is missing")
                .requireVerified(request.executionId(), request.candidate());
        Objects.requireNonNull(request.target(), "Delivery target is missing").requireValid();
        if (request.githubToken() == null || request.githubToken().isBlank()) {
            throw new DeliveryBlockedException("FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
        }
        if (request.issueKey() == null || !request.issueKey().matches("[A-Z][A-Z0-9]*-[0-9]+")) {
            throw new DeliveryBlockedException("Invalid delivery issue key");
        }
    }

    private static void requireValidBranches(String branch, String baseBranch) {
        try {
            CandidateFreezer.git(null, "check-ref-format", "--branch", branch);
            CandidateFreezer.git(null, "check-ref-format", "--branch", baseBranch);
        } catch (RuntimeException e) {
            throw new DeliveryBlockedException("Delivery branch configuration is invalid");
        }
    }

    private DeliveryReceipt publishFromWorkspace(Request request, String branch) {
        var remote = remoteRoot + request.target().repository() + ".git";
        var auth = PublicationGit.authentication("https://github.com/", request.githubToken());
        var workspace = freezer.reconstruct(request.sourceUrl(), request.candidate());
        try {
            return publish(new GitSession(workspace, remote, auth, request.gitTimeoutSeconds()), request, branch);
        } finally {
            CandidateFreezer.cleanup(workspace);
        }
    }

    private static DeliveryReceipt publish(GitSession git, Request request, String branch) {
        var candidate = request.candidate();
        git.fetch("refs/heads/" + request.target().baseBranch());
        requireBaseAncestry(git, candidate);
        var commit = commitCandidate(git, request.issueKey(), request.summary(), candidate);
        var ref = "refs/heads/" + branch;
        if (!remoteHasCommit(git, ref, commit)) {
            push(git, commit, ref);
        }
        git.fetch(ref);
        requireRemoteIdentity(git.workspace(), commit, candidate);
        return new DeliveryReceipt(
                request.executionId(),
                request.target().repository(),
                branch,
                candidate.baseSha(),
                candidate.treeSha(),
                candidate.patchSha256(),
                commit);
    }

    private static void requireBaseAncestry(GitSession git, Candidate candidate) {
        var ancestry = git.run(Map.of(), "merge-base", "--is-ancestor", candidate.baseSha(), "FETCH_HEAD");
        if (ancestry.exitCode() == 1) {
            throw new DeliveryBlockedException(
                    "Destination base no longer contains the candidate base; synchronize the fork");
        }
        requireSuccess(ancestry, "merge-base");
    }

    private static String commitCandidate(GitSession git, String issueKey, String summary, Candidate candidate) {
        var date = CandidateFreezer.git(git.workspace(), "show", "-s", "--format=%cI", candidate.baseSha())
                .strip();
        var identity = PublicationGit.identity(date, "Factory", "factory@localhost");
        var commit = git.run(identity, "commit-tree", candidate.treeSha(), "-p", candidate.baseSha(),
                "-m", commitTitle(issueKey, summary));
        requireSuccess(commit, "commit-tree");
        return commit.output().strip();
    }

    private static String commitTitle(String issueKey, String summary) {
        return issueKey + ": "
                + (summary == null ? "Developer Flow candidate" : summary.replaceAll("[\\r\\n]", " ").strip());
    }

    /** True when the branch already holds this commit; any other commit there is a conflict. */
    private static boolean remoteHasCommit(GitSession git, String ref, String commit) {
        var existing = git.requireRemote("ls-remote", "--heads", git.remote(), ref).output().strip();
        if (!existing.isEmpty() && !existing.split("\\s+")[0].equals(commit)) {
            throw new DeliveryBlockedException("Delivery branch conflicts with an existing remote commit");
        }
        return !existing.isEmpty();
    }

    private static void push(GitSession git, String commit, String ref) {
        var push = git.run(git.auth(), "push", "--porcelain", git.remote(), commit + ":" + ref);
        var error = push.error().toLowerCase(Locale.ROOT);
        if (push.exitCode() != 0
                && (permanentRemoteFailure(push) || error.contains("non-fast-forward") || error.contains("rejected"))) {
            throw new DeliveryBlockedException("Delivery branch was rejected or now conflicts at the destination");
        }
        requireSuccess(push, "push");
    }

    private static void requireRemoteIdentity(Path workspace, String commit, Candidate candidate) {
        if (!revParse(workspace, "FETCH_HEAD").equals(commit)
                || !revParse(workspace, "FETCH_HEAD^{tree}").equals(candidate.treeSha())
                || !revParse(workspace, "FETCH_HEAD^").equals(candidate.baseSha())) {
            throw new DeliveryBlockedException("Remote delivery identity does not match the verified candidate");
        }
    }

    private static String revParse(Path workspace, String revision) {
        return CandidateFreezer.git(workspace, "rev-parse", revision).strip();
    }

    private static void requireSuccess(Processes.Result result, String operation) {
        // Remote diagnostics may contain sensitive headers: never copy them into artifacts/logs.
        if (result.exitCode() != 0)
            throw new IllegalStateException(
                    "Delivery Git " + operation + " failed (exit " + result.exitCode() + ")");
    }

    private static boolean permanentRemoteFailure(Processes.Result result) {
        String error = result.error().toLowerCase(Locale.ROOT);
        return error.contains("authentication failed")
                || error.contains("repository not found")
                || error.contains("could not read username")
                || error.contains("error: 401")
                || error.contains("error: 403")
                || error.contains("error: 404")
                || error.contains("couldn't find remote ref")
                || error.contains("does not appear to be a git repository");
    }

    private record Request(
            String executionId,
            String issueKey,
            String summary,
            String sourceUrl,
            Candidate candidate,
            VerificationReceipt verification,
            DeliveryTarget target,
            String githubToken,
            int gitTimeoutSeconds) {
        // The default toString would print the token.
        @Override
        public String toString() {
            return "Request[executionId=" + executionId + ", issueKey=" + issueKey + ", target=" + target + "]";
        }
    }

    /** One reconstructed workspace and its destination; every command shares the attempt's timeout. */
    private record GitSession(Path workspace, String remote, Map<String, String> auth, int timeoutSeconds) {
        Processes.Result run(Map<String, String> environment, String... args) {
            return Git.run(workspace, environment, timeoutSeconds, args);
        }

        void fetch(String ref) {
            requireRemote("fetch", "--quiet", remote, ref);
        }

        /** A destination that is missing or refuses the credentials blocks delivery instead of failing it. */
        Processes.Result requireRemote(String... args) {
            var result = run(auth, args);
            if (result.exitCode() != 0 && permanentRemoteFailure(result)) {
                throw new DeliveryBlockedException("Delivery destination is missing or unauthorized");
            }
            requireSuccess(result, args[0]);
            return result;
        }

        // The default toString would print the authorization header.
        @Override
        public String toString() {
            return "GitSession[workspace=" + workspace + ", remote=" + remote + "]";
        }
    }
}
