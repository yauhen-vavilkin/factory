package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import org.folio.factory.app.developer.CodingTranscriptStore;
import org.folio.factory.devfactory.runtime.TokenUsage;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Display rows of the transcript page: one block per coding run, each tool call next to its result. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class DeveloperTranscriptView {

    static List<Map<String, Object>> runs(List<CodingTranscriptStore.Entry> entries) {
        Map<UUID, Map<String, Object>> runs = new LinkedHashMap<>();
        Map<String, Map<String, Object>> calls = new HashMap<>();
        for (var entry : entries) {
            var run = runs.computeIfAbsent(entry.runId(), id -> runRow(entry, runs.size() + 1));
            var detail = entry.detail();
            var callKey = entry.runId() + "/" + detail.path("id").asString("");
            if (entry.kind().equals("tool_result") && calls.containsKey(callKey)) {
                addResult(calls.remove(callKey), detail);
                continue;
            }
            var row = entryRow(entry, detail);
            if (entry.kind().equals("tool_call")) {
                calls.put(callKey, row);
            }
            entriesOf(run).add(row);
        }
        return List.copyOf(runs.values());
    }

    private static Map<String, Object> runRow(CodingTranscriptStore.Entry first, int number) {
        var step = "pr-follow-up".equals(first.stepId())
                ? "PR feedback" : DeveloperExecutionView.technicalStepLabel(first.stepId());
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("label", "Run " + number + " · " + step);
        run.put("started", UiFormat.format(first.at()));
        run.put("entries", new ArrayList<Map<String, Object>>());
        return run;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(Map<String, Object> run) {
        return (List<Map<String, Object>>) run.get("entries");
    }

    private static Map<String, Object> entryRow(CodingTranscriptStore.Entry entry, JsonNode detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", entry.kind());
        row.put("time", UiFormat.clock(entry.at()));
        row.put("timeTitle", UiFormat.format(entry.at()));
        row.put("text", detail.path("text").asString(""));
        switch (entry.kind()) {
            case "tool_call" -> {
                putTool(row, detail, detail.path("input").asString(""));
                row.put("hasResult", false);
            }
            case "tool_result" -> {
                putTool(row, detail, "");
                addResult(row, detail);
            }
            case "result" -> {
                row.put("subtype", detail.path("subtype").asString(""));
                row.put("error", detail.path("error").asBoolean(false));
                row.put("facts", resultFacts(detail));
            }
            default -> { }
        }
        return row;
    }

    private static void putTool(Map<String, Object> row, JsonNode detail, String input) {
        row.put("kind", "tool");
        row.put("tool", detail.path("tool").asString("Tool"));
        row.put("input", input);
    }

    private static void addResult(Map<String, Object> row, JsonNode detail) {
        row.put("hasResult", true);
        row.put("result", detail.path("text").asString(""));
        row.put("error", detail.path("error").asBoolean(false));
        row.put("duration", detail.path("durationMs").isIntegralNumber()
                ? milliseconds(detail.path("durationMs").asLong()) : null);
    }

    private static String resultFacts(JsonNode detail) {
        List<String> facts = new ArrayList<>();
        if (detail.path("turns").isIntegralNumber()) facts.add(detail.path("turns").asLong() + " turns");
        if (detail.path("durationMs").isIntegralNumber()) facts.add(milliseconds(detail.path("durationMs").asLong()));
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

    private static String milliseconds(long value) {
        return value < 1000 ? value + " ms" : UiFormat.duration(value / 1000);
    }
}
