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
    private final Consumer<String> observer;
    private int emitted;
    private int downloadsSeen;
    private int downloadsEmitted;
    private int diagnosticsEmitted;
    private int activityEmitted;
    private int resultsEmitted;

    public MavenBaselineOutput(Consumer<String> observer) { this.observer = observer; }

    @Override public void accept(String rawLine) {
        if (emitted >= EVENT_LIMIT || rawLine == null) return;
        String line = ANSI.matcher(rawLine).replaceAll("").strip();
        Category category = category(line);
        if (line.isBlank() || category == null || !withinCategoryLimit(category)) return;
        emitted++;
        String safe = sanitize(line);
        observer.accept(safe.substring(0, Math.min(240, safe.length())));
    }

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
