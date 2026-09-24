package org.folio.factory.devfactory.feedback;

import org.folio.factory.devfactory.candidate.*;
import org.folio.factory.devfactory.runtime.Processes;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Two-phase, deterministic Git publication. No credentials enter the coding workload. */
public class FeedbackGit {
    public record Intent(String parent, String tree, String sha, String message, String date, String authorName, String authorEmail) { }
    private final CandidateFreezer freezer;
    public FeedbackGit(CandidateFreezer freezer) { this.freezer = freezer; }
    public Intent prepare(String source, Candidate candidate, String message, String date) {
        Path path = freezer.reconstruct(source, candidate);
        try {
            String cleanMessage = message.replaceAll("[\\r\\n]", " ");
            String sha = git(path, identity(date, "Factory", "factory@localhost"), "commit-tree", candidate.treeSha(), "-p", candidate.baseSha(), "-m", cleanMessage);
            return new Intent(candidate.baseSha(), candidate.treeSha(), sha, cleanMessage, date, "Factory", "factory@localhost");
        } finally { CandidateFreezer.cleanup(path); }
    }
    public String publish(String source, String branch, Candidate candidate, Intent intent, String token, Runnable ownership) {
        if (!candidate.baseSha().equals(intent.parent()) || !candidate.treeSha().equals(intent.tree()))
            throw new IllegalStateException("Publication intention does not match candidate");
        Path path = freezer.reconstruct(source, candidate);
        try {
            CandidateFreezer.git(path, "check-ref-format", "--branch", branch);
            String sha = git(path, identity(intent.date(), intent.authorName(), intent.authorEmail()), "commit-tree", intent.tree(), "-p", intent.parent(), "-m", intent.message());
            if (!sha.equals(intent.sha())) throw new IllegalStateException("Publication commit cannot be reproduced");
            Map<String,String> auth = authentication(source, token);
            String ref = "refs/heads/" + branch;
            ownership.run();
            git(path, auth, "fetch", "--quiet", source, ref);
            String head = git(path, Map.of(), "rev-parse", "FETCH_HEAD");
            if (head.equals(sha) || descendant(path, sha)) return sha;
            if (!head.equals(intent.parent())) throw new IllegalStateException("PR branch changed; refresh before continuing");
            ownership.run();
            git(path, auth, "push", "--porcelain", source, sha + ":" + ref);
            git(path, auth, "fetch", "--quiet", source, ref);
            if (!git(path, Map.of(), "rev-parse", "FETCH_HEAD").equals(sha) && !descendant(path, sha))
                throw new IllegalStateException("Published commit could not be confirmed; retry publication reconciliation");
            return sha;
        } finally { CandidateFreezer.cleanup(path); }
    }
    private static boolean descendant(Path path, String sha) {
        return command(path, Map.of(), "merge-base", "--is-ancestor", sha, "FETCH_HEAD").exitCode() == 0;
    }
    private static Map<String,String> identity(String date, String name, String email) {
        return Map.of("GIT_AUTHOR_NAME", name, "GIT_AUTHOR_EMAIL", email,
                "GIT_COMMITTER_NAME", name, "GIT_COMMITTER_EMAIL", email,
                "GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date);
    }
    private static Map<String,String> authentication(String source, String token) {
        if (!source.startsWith("https://")) return Map.of(); // Local bare repositories are used by deterministic tests.
        if (token == null || token.isBlank()) throw new IllegalStateException("GitHub credentials are not configured");
        String header = "Authorization: Basic " + Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
        return Map.of("GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "http." + source + ".extraHeader", "GIT_CONFIG_VALUE_0", header);
    }
    private static String git(Path path, Map<String,String> env, String... args) {
        var result = command(path, env, args);
        if (result.exitCode() != 0) throw new IllegalStateException("PR Git " + args[0] + " failed (exit " + result.exitCode() + ")");
        return result.output().strip();
    }
    private static Processes.Result command(Path path, Map<String,String> env, String... args) {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "credential.helper="));
        command.addAll(List.of(args));
        return Processes.run(path, command, 180, env);
    }
}
