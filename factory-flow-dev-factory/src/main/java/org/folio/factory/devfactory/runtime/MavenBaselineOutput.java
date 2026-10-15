package org.folio.factory.devfactory.runtime;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Bounded, sanitized Maven activity and one actionable dependency-transfer failure. */
public final class MavenBaselineOutput implements Consumer<String> {
    static final int EVENT_LIMIT = 120;
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
    private static final Pattern URI_CREDENTIALS = Pattern.compile("(https?://)[^/@\\s]+@", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(authorization|password|token|api[-_]?key)(\\s*[=:]\\s*)\\S+");
    private static final Pattern TRANSFER = Pattern.compile(
            "(?i)Could not transfer artifact (?:[^:\\s]+:)?([^:\\s]+):[^:\\s]+:([^:\\s]+) from/to ([^ (:\\s]+)");
    private static final Pattern NETWORK_FAILURE = Pattern.compile(
            "connect timed out|connection timed out|connection timeout|read timed out|read timeout|connection reset"
                    + "|unknownhostexception|unknown host|temporary failure in name resolution|name or service not known"
                    + "|premature end of content-length|unexpected end of stream|incomplete http body");
    private static final Pattern EXCEPTION_CONTINUATION = Pattern.compile(
            "(?:[a-z0-9_$]+\\.)+[a-z0-9_$]*exception:.*");
    private final Consumer<String> observer;
    private int emitted;
    private int downloadsSeen;
    private int downloadsEmitted;
    private int diagnosticsEmitted;
    private int activityEmitted;
    private int resultsEmitted;
    private boolean closed;

    public MavenBaselineOutput(Consumer<String> observer) { this.observer = observer; }

    @Override public synchronized void accept(String rawLine) {
        if (closed || emitted >= EVENT_LIMIT || rawLine == null) return;
        String line = ANSI.matcher(rawLine).replaceAll("").strip();
        Category category = category(line);
        if (line.isBlank() || category == null || !withinCategoryLimit(category)) return;
        emitted++;
        String safe = sanitize(line);
        observer.accept(safe.substring(0, Math.min(240, safe.length())));
    }

    public synchronized void heartbeat() {
        if (closed || emitted >= EVENT_LIMIT) return;
        emitted++;
        observer.accept("Starting build is still running");
    }

    /** Prevents output-reader or scheduler callbacks from publishing after completion. */
    public synchronized void close() { closed = true; }

    public static Optional<String> failureSummary(String output) {
        if (output == null || output.isBlank()) return Optional.empty();
        var matcher = TRANSFER.matcher(output);
        String summary = null;
        while (matcher.find()) {
            String repository = matcher.group(3).equalsIgnoreCase("central") ? "Maven Central" : matcher.group(3);
            summary = "Dependency resolution failed: could not transfer " + matcher.group(1) + " "
                    + matcher.group(2) + " from " + repository;
        }
        if (summary == null) return Optional.empty();
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("premature end of content-length") || lower.contains("unexpected end of stream"))
            summary += " (incomplete download)";
        return Optional.of(summary);
    }

    /** Retry only Maven transfer diagnostics, never arbitrary application/test network messages. */
    public static boolean isTransientDownloadFailure(String output) {
        if (output == null || output.isBlank()) return false;
        String lower = ANSI.matcher(output).replaceAll("").toLowerCase(Locale.ROOT);
        if (lower.contains("compilation failure") || lower.contains("compilation error")
                || lower.contains("there are test failures")
                || Pattern.compile("tests run:.*(?:failures|errors): [1-9]").matcher(lower).find()) return false;
        var lines = lower.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains("[error]") || !(line.contains("could not transfer artifact")
                    || line.contains("could not transfer metadata") || line.contains("failed to transfer artifact"))) continue;
            if (NETWORK_FAILURE.matcher(line).find()) return true;
            // Maven can wrap a transfer exception onto adjacent diagnostic lines. Only
            // recognizable exception continuations qualify, not arbitrary later test output.
            for (int next = i + 1; next < Math.min(lines.size(), i + 4); next++) {
                String continuation = lines.get(next).strip();
                if (continuation.startsWith("[error]")) continuation = continuation.substring(7).strip();
                boolean cause = continuation.startsWith("caused by:") || continuation.startsWith("connect to ")
                        || EXCEPTION_CONTINUATION.matcher(continuation).matches()
                        || NETWORK_FAILURE.matcher(continuation).matches()
                        || continuation.startsWith("premature end of content-length")
                        || continuation.startsWith("unexpected end of stream")
                        || continuation.startsWith("incomplete http body");
                if (!cause) break;
                if (NETWORK_FAILURE.matcher(continuation).find()) return true;
            }
        }
        return false;
    }

    private static Category category(String line) {
        String value = line.toLowerCase(Locale.ROOT);
        if (value.contains("build success") || value.contains("build failure")) return Category.RESULT;
        if (value.contains("downloading from ") || value.contains("downloaded from ")) return Category.DOWNLOAD;
        if (value.startsWith("[warning]") || value.startsWith("[error]")) return Category.DIAGNOSTIC;
        if ((line.contains("---") && line.matches(".*--- .+:.+ .*---.*"))
                || value.contains("compiling ") || value.contains("tests run:")
                || value.startsWith("[info] running ")) return Category.ACTIVITY;
        return null;
    }

    private boolean withinCategoryLimit(Category category) {
        return switch (category) {
            case DOWNLOAD -> {
                downloadsSeen++;
                boolean sample = downloadsSeen <= 10 || downloadsSeen % 25 == 0;
                yield sample && downloadsEmitted++ < 30;
            }
            case DIAGNOSTIC -> diagnosticsEmitted++ < 30;
            case ACTIVITY -> activityEmitted++ < 58;
            case RESULT -> resultsEmitted++ < 2;
        };
    }

    public static String sanitize(String text) {
        String safe = URI_CREDENTIALS.matcher(text).replaceAll("$1[REDACTED]@");
        safe = NAMED_SECRET.matcher(safe).replaceAll("$1$2[REDACTED]");
        return safe;
    }

    private enum Category { DOWNLOAD, DIAGNOSTIC, ACTIVITY, RESULT }
}
