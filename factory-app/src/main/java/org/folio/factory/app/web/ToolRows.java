package org.folio.factory.app.web;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pairs the end of a tool call with its start, so Activity shows one row per call with the time it
 * took. It pairs only when the match is certain: one open call of that tool, or one open call with
 * the same file, pattern, URL or command. Otherwise the end keeps its own row.
 */
final class ToolRows {

    private static final Pattern EXIT_CODE = Pattern.compile("(?i)exit code:? (-?\\d+)");

    private final Map<String, Tool> tools = new HashMap<>();

    /** How a call ended: "took 9 ms", or "failed · exit 1" without the error text, which stays in the transcript. */
    record Ending(int row, String result, boolean failed) {
    }

    /** A call that started and has not ended yet, at its row in the activity list. */
    private record Open(int row, String subject, Instant startedAt) {
    }

    private static final class Tool {
        private int active;
        private final List<Open> open = new ArrayList<>();
    }

    void started(JsonNode detail, int row, Instant at) {
        var tool = tools.computeIfAbsent(toolKey(detail), key -> new Tool());
        tool.active++;
        tool.open.add(new Open(row, subject(detail), at));
    }

    /** The row this end belongs to and how the call ended, when the end belongs to one known start. */
    Optional<Ending> ended(JsonNode detail, Instant at) {
        var tool = tools.computeIfAbsent(toolKey(detail), key -> new Tool());
        var match = match(tool, subject(detail));
        tool.active = Math.max(0, tool.active - 1);
        if (match.isEmpty() || tool.active == 0) {
            tool.open.clear();
        }
        match.ifPresent(tool.open::remove);
        return match.map(open -> new Ending(open.row(), result(detail, elapsed(detail, open, at)),
                detail.path("error").asBoolean(false)));
    }

    /** The rows of calls that have not ended: they run now, while the run works. */
    List<Integer> openRows() {
        return tools.values().stream().flatMap(tool -> tool.open.stream()).map(Open::row).toList();
    }

    /** "took 2 min", "failed · exit 1", or "failed"; null when nothing says how long the call took. */
    static String result(JsonNode detail, Long millis) {
        if (detail.path("error").asBoolean(false)) {
            var exit = EXIT_CODE.matcher(detail.path("errorText").asString(""));
            return exit.find() ? "failed · exit " + exit.group(1) : "failed";
        }
        return millis == null ? null : "took " + UiFormat.milliseconds(millis);
    }

    private static Long elapsed(JsonNode detail, Open open, Instant at) {
        return detail.path("durationMs").isIntegralNumber() ? detail.path("durationMs").asLong()
                : Math.max(0, Duration.between(open.startedAt(), at).toMillis());
    }

    private static Optional<Open> match(Tool tool, String subject) {
        if (tool.active == 1 && tool.open.size() == 1) {
            return Optional.of(tool.open.getFirst());
        }
        var same = tool.open.stream().filter(open -> !subject.isBlank() && open.subject().equals(subject)).toList();
        return same.size() == 1 ? Optional.of(same.getFirst()) : Optional.empty();
    }

    private static String toolKey(JsonNode detail) {
        return detail.path("tool").asString("").strip().toLowerCase(Locale.ROOT);
    }

    private static String subject(JsonNode detail) {
        var path = detail.path("path").asString("").replace('\\', '/').strip();
        for (var subject : List.of(path, detail.path("pattern").asString("").strip(),
                detail.path("url").asString("").strip(), detail.path("command").asString("").strip())) {
            if (!subject.isBlank()) {
                return subject;
            }
        }
        return "";
    }
}
