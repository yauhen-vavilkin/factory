package org.folio.factory.devfactory.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Bounded subprocess transport with an explicit environment allowlist. */
public final class Processes {
    public static final int OUTPUT_LIMIT = 2 * 1024 * 1024;
    public static final int STREAM_LINE_LIMIT = 8 * 1024 * 1024;
    public static final int STDERR_TAIL = 64 * 1024;

    /** How long the line reader may still work after the command ended, for example on slow best-effort writes. */
    static final java.time.Duration READER_DRAIN = java.time.Duration.ofMinutes(2);

    private static final int OBSERVED_LINE_LIMIT = 512 * 1024;

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
                             int outputLimit, Consumer<String> stdoutLine) {
        if (outputLimit < 1) {
            throw new IllegalArgumentException("Output limit must be positive");
        }
        try {
            var process = start(directory, argv, extraEnvironment);
            return collect(process, seconds, new OutputBudget(outputLimit), stdoutLine);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start command", e);
        }
    }

    /**
     * How a streamed command ended. The stderr tail is redacted and holds at most {@link #STDERR_TAIL}
     * characters. {@code outputUnread} means the line reader was still busy long after the command ended,
     * so its state must not be read.
     */
    public record Streamed(int exitCode, boolean timedOut, boolean lineTooLarge, String stderrTail,
                           boolean outputUnread) {
        public Streamed(int exitCode, boolean timedOut, boolean lineTooLarge, String stderrTail) {
            this(exitCode, timedOut, lineTooLarge, stderrTail, false);
        }
    }

    /**
     * Gives each stdout line to {@code stdoutLine} as it arrives and keeps no stdout in memory. There is
     * no total output limit. A single line over {@link #STREAM_LINE_LIMIT} bytes stops the command, and so
     * does {@code stdoutLine} returning false. Only the last redacted stderr lines are kept.
     */
    public static Streamed stream(Path directory, List<String> argv, int seconds,
                                  UnaryOperator<String> redactor, Predicate<String> stdoutLine) {
        Process process = null;
        try {
            process = start(directory, argv, Map.of());
            return awaitStream(process, seconds, new StderrTail(redactor), stdoutLine);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start command", e);
        }
    }

    private static Streamed awaitStream(Process process, int seconds, StderrTail stderr,
                                        Predicate<String> stdoutLine) throws InterruptedException {
        var lineTooLarge = new AtomicBoolean();
        var readerFailure = new AtomicReference<RuntimeException>();
        var stdoutReader = Thread.ofVirtual().start(() -> readLines(process, process.getInputStream(),
                STREAM_LINE_LIMIT, guarded(stdoutLine, readerFailure), () -> stopOnLargeLine(lineTooLarge)));
        var stderrReader = Thread.ofVirtual().start(() -> readLines(process, process.getErrorStream(),
                STDERR_TAIL, stderr::addLine, stderr::addSkippedLine));
        var timedOut = !process.waitFor(seconds, TimeUnit.SECONDS);
        if (timedOut) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        stdoutReader.join(READER_DRAIN);
        stderrReader.join(5000);
        if (readerFailure.get() != null) {
            throw new IllegalStateException("Output reader failed", readerFailure.get());
        }
        return new Streamed(process.isAlive() ? -1 : process.exitValue(), timedOut, lineTooLarge.get(),
                stderr.text(), stdoutReader.isAlive());
    }

    /** A failing consumer stops the command like one that says stop; the failure is thrown after the readers end. */
    private static Predicate<String> guarded(Predicate<String> consumer, AtomicReference<RuntimeException> failure) {
        return line -> {
            try {
                return consumer.test(line);
            } catch (RuntimeException e) {
                failure.compareAndSet(null, e);
                return false;
            }
        };
    }

    private static boolean stopOnLargeLine(AtomicBoolean lineTooLarge) {
        lineTooLarge.set(true);
        return false;
    }

    private static void readLines(Process process, InputStream stream, int lineLimit,
                                  Predicate<String> consumer, BooleanSupplier tooLarge) {
        var lines = new LineSplitter(lineLimit, consumer, tooLarge);
        try (stream) {
            var buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (!lines.add(buffer, count)) {
                    process.destroyForcibly();
                    return;
                }
            }
            lines.finish();
        } catch (IOException ignored) { /* The exit code and the parser state decide the outcome. */ }
    }

    private static String text(ByteArrayOutputStream line) {
        String text = line.toString(StandardCharsets.UTF_8);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    /** Cuts a byte stream into lines; a line over the limit is reported once and its bytes are dropped. */
    private static final class LineSplitter {
        private final int lineLimit;
        private final Predicate<String> consumer;
        private final BooleanSupplier tooLarge;
        private ByteArrayOutputStream line = new ByteArrayOutputStream();
        private boolean skipping;

        LineSplitter(int lineLimit, Predicate<String> consumer, BooleanSupplier tooLarge) {
            this.lineLimit = lineLimit;
            this.consumer = consumer;
            this.tooLarge = tooLarge;
        }

        /** False when the command must stop. */
        boolean add(byte[] buffer, int count) {
            int start = 0;
            for (int i = 0; i <= count; i++) {
                if (i < count && buffer[i] != '\n') {
                    continue;
                }
                if (!append(buffer, start, i - start) || i < count && !endLine()) {
                    return false;
                }
                start = i + 1;
            }
            return true;
        }

        void finish() {
            if (!skipping && line.size() > 0) {
                consumer.test(text(line));
            }
        }

        private boolean append(byte[] buffer, int start, int length) {
            if (skipping) {
                return true;
            }
            line.write(buffer, start, length);
            if (line.size() <= lineLimit) {
                return true;
            }
            // A new buffer, not reset(): the oversized one must not stay in memory while the rest is skipped.
            line = new ByteArrayOutputStream();
            skipping = true;
            return tooLarge.getAsBoolean();
        }

        private boolean endLine() {
            if (!skipping && !consumer.test(text(line))) {
                return false;
            }
            line.reset();
            skipping = false;
            return true;
        }
    }

    /** The last redacted stderr lines. Both line callbacks return true: stderr never stops the command. */
    private static final class StderrTail {
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private final UnaryOperator<String> redactor;
        private int size;

        StderrTail(UnaryOperator<String> redactor) {
            this.redactor = redactor;
        }

        boolean addLine(String line) {
            add(redactor.apply(line));
            return true;
        }

        boolean addSkippedLine() {
            add("[a very long error line was left out]");
            return true;
        }

        synchronized String text() { return String.join("\n", lines); }

        private synchronized void add(String line) {
            lines.addLast(line);
            size += line.length() + 1;
            while (size > STDERR_TAIL && lines.size() > 1) size -= lines.removeFirst().length() + 1;
        }
    }

    private static Process start(Path directory, List<String> argv, Map<String, String> extraEnvironment)
            throws IOException {
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
        return process;
    }

    private static Result collect(Process process, int seconds, OutputBudget budget, Consumer<String> stdoutLine)
            throws InterruptedException {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var stdoutReader = reader(process, process.getInputStream(), stdout, budget, stdoutLine);
        var stderrReader = reader(process, process.getErrorStream(), stderr, budget, null);
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("Command exceeded " + seconds + " seconds");
        }
        stdoutReader.join(5000);
        stderrReader.join(5000);
        if (stdoutReader.isAlive() || stderrReader.isAlive() || budget.exceeded()) {
            throw new IllegalStateException("Command exceeded output limit");
        }
        return new Result(process.exitValue(), stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static Thread reader(Process process, InputStream stream, ByteArrayOutputStream destination,
                                 OutputBudget budget, Consumer<String> observer) {
        return Thread.ofVirtual().start(() -> {
            var lines = new ObservedLines(observer);
            try (stream) {
                var buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (!budget.spend(count)) {
                        process.destroyForcibly();
                        break;
                    }
                    destination.write(buffer, 0, count);
                    lines.add(buffer, count);
                }
                lines.finish();
            } catch (IOException ignored) { /* Exit and size checks below remain authoritative. */ }
        });
    }

    /** One output limit shared by stdout and stderr of a command. */
    private static final class OutputBudget {
        private final int limit;
        private final AtomicInteger total = new AtomicInteger();
        private final AtomicBoolean overflow = new AtomicBoolean();

        OutputBudget(int limit) {
            this.limit = limit;
        }

        /** False once the command wrote more than the limit. */
        boolean spend(int count) {
            if (total.addAndGet(count) <= limit) {
                return true;
            }
            overflow.set(true);
            return false;
        }

        boolean exceeded() {
            return overflow.get();
        }
    }

    /** Lines for an optional observer; a line over {@link #OBSERVED_LINE_LIMIT} bytes is skipped, not cut. */
    private static final class ObservedLines {
        private final Consumer<String> observer;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private boolean oversized;

        ObservedLines(Consumer<String> observer) {
            this.observer = observer;
        }

        void add(byte[] buffer, int count) {
            if (observer == null) {
                return;
            }
            for (int i = 0; i < count; i++) {
                if (buffer[i] == '\n') {
                    endLine();
                } else if (line.size() < OBSERVED_LINE_LIMIT) {
                    line.write(buffer[i]);
                } else {
                    oversized = true;
                }
            }
        }

        void finish() {
            if (observer != null && !oversized && line.size() > 0) {
                observe(observer, line);
            }
        }

        private void endLine() {
            if (!oversized) {
                observe(observer, line);
            }
            line.reset();
            oversized = false;
        }
    }

    private static void observe(Consumer<String> observer, ByteArrayOutputStream line) {
        try { observer.accept(line.toString(StandardCharsets.UTF_8)); }
        catch (RuntimeException ignored) { /* Observations cannot change command acceptance. */ }
    }
}
