package org.folio.factory.devfactory.repository;

import org.folio.factory.core.agent.AgentExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Native {@code git ls-remote} resolution. Runs without credentials or prompts:
 * supported source repositories are public.
 */
public class GitBaseRefResolver implements BaseRefResolver {

    private static final Logger log = LoggerFactory.getLogger(GitBaseRefResolver.class);
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final long TIMEOUT_SECONDS = 60;
    private static final int MAX_ERROR_CHARS = 500;

    private final String gitBaseUrl;

    public GitBaseRefResolver(String gitBaseUrl) {
        this.gitBaseUrl = gitBaseUrl;
    }

    @Override
    public Optional<String> resolve(String sourceRepo, String branch) {
        String ref = "refs/heads/" + branch;
        if (run(List.of("git", "check-ref-format", ref)).exitCode() != 0) {
            throw new AgentExecutionException("Configured base branch is not a valid Git ref: " + branch);
        }
        String url = gitBaseUrl + "/" + sourceRepo + ".git";
        Result result = run(List.of("git", "-c", "credential.helper=", "ls-remote", "--exit-code", url, ref));
        if (result.exitCode() == 2) {
            return Optional.empty();
        }
        if (result.exitCode() != 0) {
            throw new AgentExecutionException("git ls-remote failed for " + sourceRepo + " (exit "
                    + result.exitCode() + "): " + bounded(result.output()));
        }
        for (String line : result.output().split("\n")) {
            String[] parts = line.strip().split("\t");
            if (parts.length == 2 && parts[1].equals(ref) && SHA.matcher(parts[0]).matches()) {
                return Optional.of(parts[0]);
            }
        }
        throw new AgentExecutionException("git ls-remote returned no usable SHA for " + sourceRepo + " " + ref);
    }

    private Result run(List<String> command) {
        Path output = null;
        try {
            output = Files.createTempFile("factory-dev-git", ".out");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().remove("GIT_ASKPASS");
            builder.environment().remove("SSH_ASKPASS");
            Process process = builder.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AgentExecutionException("git timed out after " + TIMEOUT_SECONDS + "s");
            }
            return new Result(process.exitValue(), Files.readString(output));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentExecutionException("Could not run git: " + e.getMessage(), e);
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", output, e.getMessage());
                }
            }
        }
    }

    private static String bounded(String text) {
        String stripped = text.strip();
        return stripped.length() <= MAX_ERROR_CHARS ? stripped : stripped.substring(0, MAX_ERROR_CHARS) + "...";
    }

    private record Result(int exitCode, String output) {
    }
}
