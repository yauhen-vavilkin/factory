package org.folio.factory.devfactory.runtime;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Bounded, sanitized yarn and Jest activity, and the one actionable install failure. */
public final class YarnBaselineOutput implements BuildOutput {
    static final int EVENT_LIMIT = 120;
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
    /** The start of the line that yarn prints last when {@code yarn install} fails. */
    private static final String FAILED_INSTALL_LINE = "info Visit https://yarnpkg.com/en/docs/cli/install";
    private static final Pattern STEP = Pattern.compile("^\\[\\d+/\\d+] .*");
    private static final Pattern NETWORK_FAILURE = Pattern.compile(
            "etimedout|esockettimedout|econnreset|econnrefused|eai_again|enotfound|socket hang up"
                    + "|request failed \\\\?\"5\\d\\d");
    private final Consumer<String> observer;
    private int emitted;
    private int steps;
    private int results;
    private int diagnostics;
    private int passes;
    private int passesSeen;
    private int failures;
    private int summaries;
    private boolean closed;

    public YarnBaselineOutput(Consumer<String> observer) { this.observer = observer; }

    @Override public synchronized void accept(String rawLine) {
        if (closed || emitted >= EVENT_LIMIT || rawLine == null) return;
        String line = ANSI.matcher(rawLine).replaceAll("").strip();
        if (line.isBlank() || !wanted(line)) return;
        emitted++;
        String safe = MavenBaselineOutput.sanitize(line);
        observer.accept(safe.substring(0, Math.min(240, safe.length())));
    }

    @Override public synchronized void heartbeat() {
        if (closed || emitted >= EVENT_LIMIT) return;
        emitted++;
        observer.accept("Starting build is still running");
    }

    /** Prevents output-reader or scheduler callbacks from publishing after completion. */
    @Override public synchronized void close() { closed = true; }

    /** The last yarn error, which names the cause of an install or test-command failure. */
    public static Optional<String> failureSummary(String output) {
        return lastError(output).map(error -> "Starting build failed: " + error);
    }

    /** The last yarn error alone, sanitized and bounded, for callers that name their own step. */
    public static Optional<String> lastError(String output) {
        if (output == null || output.isBlank()) return Optional.empty();
        String last = null;
        for (String raw : output.lines().toList()) {
            String line = ANSI.matcher(raw).replaceAll("").strip();
            if (line.startsWith("error ") && !line.startsWith("error Command failed")) last = line.substring(6);
        }
        if (last == null) return Optional.empty();
        String safe = MavenBaselineOutput.sanitize(last);
        return Optional.of(safe.substring(0, Math.min(200, safe.length())));
    }

    /**
     * The last yarn error of a command that stopped in its dependency install. Yarn ends a failed command with a
     * line that names it, and that line is still in an output of which only the end is kept. A failed test command
     * ends with the line of {@code run}, so its errors are not taken for the install's.
     */
    public static Optional<String> installError(String output) {
        if (output == null || output.lines().noneMatch(raw -> ANSI.matcher(raw).replaceAll("").strip()
                .startsWith(FAILED_INSTALL_LINE))) return Optional.empty();
        return lastError(output);
    }

    /** Retry only yarn's own network errors, never network words that tests print. */
    public static boolean isTransientDownloadFailure(String output) {
        if (output == null || output.isBlank()) return false;
        return output.lines()
                .map(raw -> ANSI.matcher(raw).replaceAll("").strip().toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.startsWith("error ") && NETWORK_FAILURE.matcher(line).find());
    }

    private boolean wanted(String line) {
        if (STEP.matcher(line).matches()) return steps++ < 12;
        if (line.startsWith("Done in ") || line.startsWith("success ")) return results++ < 6;
        if (line.startsWith("error ") || line.startsWith("info There appears to be trouble")) return diagnostics++ < 30;
        if (line.startsWith("Test Suites:") || line.startsWith("Tests:")) return summaries++ < 4;
        if (line.startsWith("FAIL ")) return failures++ < 20;
        if (line.startsWith("PASS ")) {
            passesSeen++;
            return (passesSeen <= 10 || passesSeen % 25 == 0) && passes++ < 20;
        }
        return false;
    }
}
