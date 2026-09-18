package org.folio.factory.devfactory.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

/** Bounded subprocess transport with an explicit environment allowlist. */
public final class Processes {
    public static final int OUTPUT_LIMIT = 2 * 1024 * 1024;
    private Processes() { }
    public record Result(int exitCode, String output, String error) {
        public Result(int exitCode, String output) {
            this(exitCode, output, "");
        }
        public Result requireSuccess() {
            if (exitCode != 0) {
                String diagnostics = error.isBlank() ? output : error;
                throw new IllegalStateException("Command failed (exit " + exitCode + "): "
                        + diagnostics.substring(Math.max(0, diagnostics.length() - 4000)));
            }
            return this;
        }
        public String diagnostics() {
            if (error.isBlank()) return output;
            if (output.isBlank()) return error;
            return output + System.lineSeparator() + error;
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
        return run(directory, argv, seconds, extraEnvironment, outputLimit, null);
    }
    public static Result run(Path directory, List<String> argv, int seconds, Map<String, String> extraEnvironment,
                             int outputLimit, java.util.function.Consumer<String> stdoutLine) {
        if (outputLimit < 1) throw new IllegalArgumentException("Output limit must be positive");
        try {
            var builder = new ProcessBuilder(argv);
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
            var stdout = new ByteArrayOutputStream();
            var stderr = new ByteArrayOutputStream();
            var total = new AtomicInteger();
            var overflow = new AtomicBoolean();
            Thread stdoutReader = reader(process, process.getInputStream(), stdout, outputLimit, total, overflow, stdoutLine);
            Thread stderrReader = reader(process, process.getErrorStream(), stderr, outputLimit, total, overflow, null);
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("Command exceeded " + seconds + " seconds");
            }
            stdoutReader.join(5000);
            stderrReader.join(5000);
            if (stdoutReader.isAlive() || stderrReader.isAlive() || overflow.get()) {
                throw new IllegalStateException("Command exceeded output limit");
            }
            return new Result(process.exitValue(), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted", e);
        } catch (IOException e) { throw new IllegalStateException("Could not start command", e); }
    }

    private static Thread reader(Process process, java.io.InputStream stream, ByteArrayOutputStream destination,
                                 int outputLimit, AtomicInteger total, AtomicBoolean overflow,
                                 java.util.function.Consumer<String> observer) {
        return Thread.ofVirtual().start(() -> {
                var line = new ByteArrayOutputStream();
                boolean oversized = false;
                try (stream) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (total.addAndGet(count) > outputLimit) {
                            overflow.set(true);
                            process.destroyForcibly();
                            break;
                        }
                        destination.write(buffer, 0, count);
                        if (observer != null) for (int i = 0; i < count; i++) {
                            if (buffer[i] == '\n') {
                                if (!oversized) observe(observer, line);
                                line.reset();
                                oversized = false;
                            } else if (line.size() < 512 * 1024) {
                                line.write(buffer[i]);
                            } else oversized = true;
                        }
                    }
                    if (observer != null && !oversized && line.size() > 0) observe(observer, line);
                } catch (IOException ignored) { /* Exit and size checks below remain authoritative. */ }
            });
    }
    private static void observe(java.util.function.Consumer<String> observer, ByteArrayOutputStream line) {
        try { observer.accept(line.toString(StandardCharsets.UTF_8)); }
        catch (RuntimeException ignored) { /* Observations cannot change command acceptance. */ }
    }
}
