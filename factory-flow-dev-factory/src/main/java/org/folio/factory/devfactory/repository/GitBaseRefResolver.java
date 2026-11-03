package org.folio.factory.devfactory.repository;

import lombok.RequiredArgsConstructor;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.devfactory.runtime.Git;
import org.folio.factory.devfactory.runtime.Processes;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Native {@code git ls-remote} resolution. Runs without credentials or prompts:
 * supported source repositories are public.
 */
@RequiredArgsConstructor
public class GitBaseRefResolver implements BaseRefResolver {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_ERROR_CHARS = 500;

    private final String gitBaseUrl;

    @Override
    public Optional<String> resolve(String sourceRepo, String branch) {
        String ref = "refs/heads/" + branch;
        if (git("check-ref-format", ref).exitCode() != 0) {
            throw new AgentExecutionException("Configured base branch is not a valid Git ref: " + branch);
        }
        String url = gitBaseUrl + "/" + sourceRepo + ".git";
        Processes.Result result = git("ls-remote", "--exit-code", url, ref);
        if (result.exitCode() == 2) {
            return Optional.empty();
        }
        if (result.exitCode() != 0) {
            throw new AgentExecutionException("git ls-remote failed for " + sourceRepo + " (exit "
                    + result.exitCode() + "): " + bounded(result.error()));
        }
        for (String line : result.output().split("\n")) {
            String[] parts = line.strip().split("\t");
            if (parts.length == 2 && parts[1].equals(ref) && SHA.matcher(parts[0]).matches()) {
                return Optional.of(parts[0]);
            }
        }
        throw new AgentExecutionException("git ls-remote returned no usable SHA for " + sourceRepo + " " + ref);
    }

    private static Processes.Result git(String... args) {
        try {
            return Git.run(null, Map.of(), TIMEOUT_SECONDS, args);
        } catch (RuntimeException e) {
            throw new AgentExecutionException("Could not run git: " + e.getMessage(), e);
        }
    }

    private static String bounded(String text) {
        String stripped = text.strip();
        return stripped.length() <= MAX_ERROR_CHARS ? stripped : stripped.substring(0, MAX_ERROR_CHARS) + "...";
    }
}
