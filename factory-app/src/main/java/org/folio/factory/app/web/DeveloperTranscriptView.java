package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import org.folio.factory.app.developer.CodingTranscriptStore;
import org.folio.factory.devfactory.runtime.TokenUsage;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Display rows of the transcript page: one section per coding run, named as the run page names the work (the
 * implementation, a round of PR feedback), and each tool call next to its result.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class DeveloperTranscriptView {

    private static final String PR_FEEDBACK = "pr-follow-up";
    private static final Map<String, String> STEPS = Map.of(
            "implement", "Implementation",
            "continue-implementation", "Implementation, after your answer",
            "verify", "Verification",
            PR_FEEDBACK, "PR feedback");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern OUTER_NEWLINES = Pattern.compile("^\\n+|\\n+$");

    /** One coding run: its name, when it started and what happened in it. */
    record Section(String label, PageTime started, List<Row> entries) {
    }

    /**
     * One transcript entry. {@code kind} is prompt, text, tool, result or note; {@code blocks} is the agent's text
     * with each Markdown heading as a line of its own; the tool and result values are empty or null for the
     * other kinds.
     */
    record Row(String kind, PageTime time, String text, List<Block> blocks, String tool, String input,
               boolean hasResult, String result, boolean error, String duration, String subtype, String facts) {
    }

    /** A part of the agent's text: a heading, shown as a bold line without its marks, or the text under it. */
    record Block(String text, boolean heading) {
    }

    static List<Section> sections(List<CodingTranscriptStore.Entry> entries, List<FeedbackRows.RoundStart> rounds) {
        Map<UUID, List<CodingTranscriptStore.Entry>> runs = new LinkedHashMap<>();
        entries.forEach(entry -> runs.computeIfAbsent(entry.runId(), id -> new ArrayList<>()).add(entry));
        var sections = new ArrayList<Section>();
        var labels = new HashSet<String>();
        for (var run : runs.values()) {
            var first = run.getFirst();
            var label = label(first, rounds);
            sections.add(new Section(labels.add(label) ? label : label + ", continued",
                    PageTime.timestamp(first.at()), rows(run)));
        }
        return sections;
    }

    /** "Implementation", or "Round 2 · PR feedback" for the round that had started when the run began. */
    private static String label(CodingTranscriptStore.Entry first, List<FeedbackRows.RoundStart> rounds) {
        var step = STEPS.getOrDefault(first.stepId(), first.stepId());
        if (!PR_FEEDBACK.equals(first.stepId())) {
            return step;
        }
        String round = null;
        for (var start : rounds) {
            if (!start.startedAt().isAfter(first.at())) {
                round = start.title();
            }
        }
        return round == null ? step : round + " · " + step;
    }

    /** The entries of one run, a tool result folded into the call it answers. */
    private static List<Row> rows(List<CodingTranscriptStore.Entry> run) {
        Map<String, JsonNode> results = new HashMap<>();
        Set<Integer> folded = new HashSet<>();
        Set<String> open = new HashSet<>();
        for (var entry : run) {
            var id = entry.detail().path("id").asString("");
            if (entry.kind().equals("tool_call")) {
                open.add(id);
            } else if (entry.kind().equals("tool_result") && open.remove(id)) {
                results.put(id, entry.detail());
                folded.add(entry.seq());
            }
        }
        return run.stream().filter(entry -> !folded.contains(entry.seq()))
                .map(entry -> row(entry, results.get(entry.detail().path("id").asString(""))))
                .toList();
    }

    private static Row row(CodingTranscriptStore.Entry entry, JsonNode result) {
        var detail = entry.detail();
        var time = PageTime.clock(entry.at());
        var text = detail.path("text").asString("");
        return switch (entry.kind()) {
            case "tool_call" -> tool(time, detail, detail.path("input").asString(""), result);
            case "tool_result" -> tool(time, detail, "", detail);
            case "result" -> new Row("result", time, text, List.of(), null, "", false, null,
                    detail.path("error").asBoolean(false), null, detail.path("subtype").asString(""),
                    resultFacts(detail));
            case "text" -> new Row("text", time, text, blocks(text), null, "", false, null, false, null, null, "");
            default -> new Row(entry.kind(), time, text, List.of(), null, "", false, null, false, null, null, "");
        };
    }

    private static Row tool(PageTime time, JsonNode call, String input, JsonNode result) {
        var name = call.path("tool").asString("Tool");
        if (result == null) {
            return new Row("tool", time, "", List.of(), name, input, false, null, false, null, null, "");
        }
        var duration = result.path("durationMs").isIntegralNumber()
                ? UiFormat.milliseconds(result.path("durationMs").asLong()) : null;
        return new Row("tool", time, "", List.of(), name, input, true, result.path("text").asString(""),
                result.path("error").asBoolean(false), duration, null, "");
    }

    /**
     * The agent writes Markdown: a heading line becomes a block of its own, the lines between stay together. A
     * "# comment" inside a fenced code block is code, not a heading.
     */
    private static List<Block> blocks(String text) {
        var blocks = new ArrayList<Block>();
        var lines = new ArrayList<String>();
        var code = false;
        for (var line : text.lines().toList()) {
            code ^= line.strip().startsWith("```");
            var heading = HEADING.matcher(line);
            if (code || !heading.matches() || heading.group(1).isEmpty()) {
                lines.add(line);
                continue;
            }
            addText(blocks, lines);
            blocks.add(new Block(heading.group(1), true));
        }
        addText(blocks, lines);
        return blocks;
    }

    private static void addText(List<Block> blocks, List<String> lines) {
        var text = OUTER_NEWLINES.matcher(String.join("\n", lines)).replaceAll("");
        lines.clear();
        if (!text.isBlank()) {
            blocks.add(new Block(text, false));
        }
    }

    private static String resultFacts(JsonNode detail) {
        List<String> facts = new ArrayList<>();
        if (detail.path("turns").isIntegralNumber()) facts.add(detail.path("turns").asLong() + " turns");
        if (detail.path("durationMs").isIntegralNumber()) {
            facts.add(UiFormat.milliseconds(detail.path("durationMs").asLong()));
        }
        var usage = TokenUsage.from(detail.path("usage"));
        addTokens(facts, usage.inputTokens(), "input");
        addTokens(facts, usage.cacheReadTokens(), "cache read");
        addTokens(facts, usage.cacheWriteTokens(), "cache write");
        addTokens(facts, usage.outputTokens(), "output");
        return String.join(" · ", facts);
    }

    private static void addTokens(List<String> facts, Long count, String category) {
        if (count != null) {
            facts.add(UiFormat.count(count) + " " + category + " tokens");
        }
    }
}
