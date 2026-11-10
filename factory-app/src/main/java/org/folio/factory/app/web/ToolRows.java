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

/**
 * Pairs the end of a tool call with its start, so Activity shows one row per call with the time it
 * took. It pairs only when the match is certain: one open call of that tool, or one open call with
 * the same file, pattern, URL or command. Otherwise the end keeps its own row.
 */
final class ToolRows {

    private static final List<String[]> PAST = List.of(
            new String[] {"Running ", "Ran "}, new String[] {"Reading ", "Read "},
            new String[] {"Writing ", "Wrote "}, new String[] {"Editing ", "Edited "},
            new String[] {"Finding files", "Found files"}, new String[] {"Listing ", "Listed "},
            new String[] {"Searching ", "Searched "}, new String[] {"Fetching ", "Fetched "});

    private final Map<String, Tool> tools = new HashMap<>();

    /** A call that started and has not ended yet, at its row in the activity list. */
    private record Open(int row, String subject, String past, Instant startedAt) {
    }

    private static final class Tool {
        private int active;
        private final List<Open> open = new ArrayList<>();
    }

    void started(JsonNode detail, int row, String text, Instant at) {
        var tool = tools.computeIfAbsent(toolKey(detail), key -> new Tool());
        tool.active++;
        tool.open.add(new Open(row, subject(detail), past(text), at));
    }

    /** The row to replace and its merged text, when this end belongs to one known start. */
    Optional<Map.Entry<Integer, String>> ended(JsonNode detail, Instant at) {
        var tool = tools.computeIfAbsent(toolKey(detail), key -> new Tool());
        var match = match(tool, subject(detail));
        tool.active = Math.max(0, tool.active - 1);
        if (match.isEmpty() || tool.active == 0) {
            tool.open.clear();
        }
        match.ifPresent(tool.open::remove);
        return match.map(open -> Map.entry(open.row(), merged(open, detail, at)));
    }

    private static Optional<Open> match(Tool tool, String subject) {
        if (tool.active == 1 && tool.open.size() == 1) {
            return Optional.of(tool.open.getFirst());
        }
        var same = tool.open.stream().filter(open -> !subject.isBlank() && open.subject().equals(subject)).toList();
        return same.size() == 1 ? Optional.of(same.getFirst()) : Optional.empty();
    }

    private static String merged(Open open, JsonNode detail, Instant at) {
        var failed = detail.path("error").asBoolean(false);
        var millis = detail.path("durationMs").isIntegralNumber() ? detail.path("durationMs").asLong()
                : Math.max(0, Duration.between(open.startedAt(), at).toMillis());
        var text = open.past() + (failed ? " · failed" : "") + " · took " + UiFormat.milliseconds(millis);
        var errorText = detail.path("errorText").asString("").strip();
        return failed && !errorText.isBlank() ? text + " · " + errorText : text;
    }

    private static String past(String startText) {
        return PAST.stream().filter(pair -> startText.startsWith(pair[0])).findFirst()
                .map(pair -> pair[1] + startText.substring(pair[0].length()))
                .orElse(startText);
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
