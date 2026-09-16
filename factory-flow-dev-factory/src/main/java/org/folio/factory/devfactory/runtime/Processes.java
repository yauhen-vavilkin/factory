package org.folio.factory.devfactory.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded subprocess transport with an explicit environment allowlist. */
public final class Processes {
    public static final int OUTPUT_LIMIT = 2 * 1024 * 1024;
    private Processes() { }
    public record Result(int exitCode, String output) {
        public Result requireSuccess() {
            if (exitCode != 0) throw new IllegalStateException("Command failed (exit " + exitCode + "): " + output.substring(Math.max(0, output.length() - 4000)));
            return this;
        }
    }
    public static Result run(Path directory, List<String> argv, int seconds) {
        return run(directory, argv, seconds, Map.of());
    }
    public static Result run(Path directory, List<String> argv, int seconds, Map<String, String> extraEnvironment) {
        return run(directory, argv, seconds, extraEnvironment, OUTPUT_LIMIT);
    }
    public static Result run(Path directory, List<String> argv, int seconds, Map<String, String> extraEnvironment,
                             int outputLimit) {
        if (outputLimit < 1) throw new IllegalArgumentException("Output limit must be positive");
        try {
            var builder = new ProcessBuilder(argv).redirectErrorStream(true);
            if (directory != null) builder.directory(directory.toFile());
            builder.environment().clear();
            for (String key : List.of("PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "TMPDIR")) {
                if (System.getenv(key) != null) builder.environment().put(key, System.getenv(key));
            }
            builder.environment().putAll(extraEnvironment);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            var process = builder.start();
            process.getOutputStream().close();
            var bytes = new ByteArrayOutputStream();
            boolean[] overflow = {false};
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var stream = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (bytes.size() + count > outputLimit) { overflow[0] = true; process.destroyForcibly(); break; }
                        bytes.write(buffer, 0, count);
                    }
                } catch (IOException ignored) { /* Exit and size checks below remain authoritative. */ }
            });
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("Command exceeded " + seconds + " seconds");
            }
            reader.join(5000);
            if (reader.isAlive() || overflow[0]) throw new IllegalStateException("Command exceeded output limit");
            return new Result(process.exitValue(), bytes.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted", e);
        } catch (IOException e) { throw new IllegalStateException("Could not start command", e); }
    }
}
