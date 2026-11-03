package org.folio.factory.devfactory.feedback;

import lombok.RequiredArgsConstructor;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.delivery.PublicationGit;
import org.folio.factory.devfactory.runtime.Git;

import java.nio.file.Path;
import java.util.Map;

/** Two-phase, deterministic Git publication. No credentials enter the coding workload. */
@RequiredArgsConstructor
public class FeedbackGit {
    public record Intent(
            String parent,
            String tree,
            String sha,
            String message,
            String date,
            String authorName,
            String authorEmail) {}

    private final CandidateFreezer freezer;

    public Intent prepare(String source, Candidate candidate, String message, String date) {
        Path path = freezer.reconstruct(source, candidate);
        try {
            String cleanMessage = message.replaceAll("[\\r\\n]", " ");
            String sha = commitTree(path, PublicationGit.identity(date, "Factory", "factory@localhost"),
                    candidate.treeSha(), candidate.baseSha(), cleanMessage);
            return new Intent(candidate.baseSha(), candidate.treeSha(), sha, cleanMessage, date, "Factory",
                    "factory@localhost");
        } finally {
            CandidateFreezer.cleanup(path);
        }
    }

    public String publish(
            String source,
            String branch,
            Candidate candidate,
            Intent intent,
            String token,
            Runnable ownership) {
        if (!candidate.baseSha().equals(intent.parent()) || !candidate.treeSha().equals(intent.tree()))
            throw new IllegalStateException("Publication intention does not match candidate");
        Path path = freezer.reconstruct(source, candidate);
        try {
            CandidateFreezer.git(path, "check-ref-format", "--branch", branch);
            String sha = reproducedCommit(path, intent);
            Map<String, String> auth = authentication(source, token);
            String ref = "refs/heads/" + branch;
            ownership.run();
            git(path, auth, "fetch", "--quiet", source, ref);
            String head = git(path, Map.of(), "rev-parse", "FETCH_HEAD");
            if (head.equals(sha) || descendant(path, sha)) return sha;
            if (!head.equals(intent.parent()))
                throw new IllegalStateException("PR branch changed; refresh before continuing");
            ownership.run();
            git(path, auth, "push", "--porcelain", source, sha + ":" + ref);
            confirmPublished(path, auth, source, ref, sha);
            return sha;
        } finally {
            CandidateFreezer.cleanup(path);
        }
    }

    private static String commitTree(Path path, Map<String, String> identity, String tree, String parent,
                                     String message) {
        return git(path, identity, "commit-tree", tree, "-p", parent, "-m", message);
    }

    /** The commit that prepare recorded, built again: the same intent always gives the same SHA. */
    private static String reproducedCommit(Path path, Intent intent) {
        String sha = commitTree(path, PublicationGit.identity(intent.date(), intent.authorName(),
                intent.authorEmail()), intent.tree(), intent.parent(), intent.message());
        if (!sha.equals(intent.sha()))
            throw new IllegalStateException("Publication commit cannot be reproduced");
        return sha;
    }

    private static void confirmPublished(Path path, Map<String, String> auth, String source, String ref,
                                         String sha) {
        git(path, auth, "fetch", "--quiet", source, ref);
        if (!git(path, Map.of(), "rev-parse", "FETCH_HEAD").equals(sha) && !descendant(path, sha))
            throw new IllegalStateException(
                    "Published commit could not be confirmed; retry publication reconciliation");
    }

    private static boolean descendant(Path path, String sha) {
        return Git.run(path, Map.of(), Git.TIMEOUT_SECONDS, "merge-base", "--is-ancestor", sha, "FETCH_HEAD").exitCode()
                == 0;
    }

    private static Map<String, String> authentication(String source, String token) {
        if (!source.startsWith("https://"))
            return Map.of(); // Local bare repositories are used by deterministic tests.
        if (token == null || token.isBlank())
            throw new IllegalStateException("GitHub credentials are not configured");
        return PublicationGit.authentication(source, token);
    }

    private static String git(Path path, Map<String, String> env, String... args) {
        var result = Git.run(path, env, Git.TIMEOUT_SECONDS, args);
        if (result.exitCode() != 0)
            throw new IllegalStateException(
                    "PR Git " + args[0] + " failed (exit " + result.exitCode() + ")");
        return result.output().strip();
    }
}
