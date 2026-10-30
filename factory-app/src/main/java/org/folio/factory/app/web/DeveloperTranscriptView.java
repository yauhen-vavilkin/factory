package org.folio.factory.app.web;

import org.folio.factory.app.developer.CodingTranscriptStore;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Display rows of the transcript page: one block per coding run, each tool call next to its result. */
final class DeveloperTranscriptView {
    private DeveloperTranscriptView() {
    }

    static List<Map<String, Object>> runs(List<CodingTranscriptStore.Entry> entries) {
        Map<UUID, Map<String, Object>> runs = new LinkedHashMap<>();
        Map<String, Map<String, Object>> calls = new HashMap<>();
        for (var entry : entries) {
            var run = runs.computeIfAbsent(entry.runId(), id -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("label", "Run " + (runs.size() + 1) + " · " + ("pr-follow-up".equals(entry.stepId())
                        ? "PR feedback" : DeveloperExecutionView.technicalStepLabel(entry.stepId())));
                created.put("started", UiFormat.format(entry.at()));
                created.put("entries", new ArrayList<Map<String, Object>>());
                return created;
            });
            @SuppressWarnings("unchecked")
            var rows = (List<Map<String, Object>>) run.get("entries");
            JsonNode detail = entry.detail();
            String callKey = entry.runId() + "/" + detail.path("id").asString("");
            if (entry.kind().equals("tool_result") && calls.containsKey(callKey)) {
                addResult(calls.remove(callKey), detail);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", entry.kind());
            row.put("time", UiFormat.clock(entry.at()));
            row.put("timeTitle", UiFormat.format(entry.at()));
            row.put("text", detail.path("text").asString(""));
            switch (entry.kind()) {
                case "tool_call" -> {
                    row.put("kind", "tool");
                    row.put("tool", detail.path("tool").asString("Tool"));
                    row.put("input", detail.path("input").asString(""));
                    row.put("hasResult", false);
                    calls.put(callKey, row);
                }
                case "tool_result" -> {
                    row.put("kind", "tool");
                    row.put("tool", detail.path("tool").asString("Tool"));
                    row.put("input", "");
                    addResult(row, detail);
                }
                case "result" -> {
                    row.put("subtype", detail.path("subtype").asString(""));
                    row.put("error", detail.path("error").asBoolean(false));
                    row.put("facts", resultFacts(detail));
                }
                default -> { }
            }
            rows.add(row);
        }
        return List.copyOf(runs.values());
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
        JsonNode usage = detail.path("usage");
        for (var field : List.of(List.of("inputTokens", "input"), List.of("cacheReadTokens", "cache read"),
                List.of("cacheWriteTokens", "cache write"), List.of("outputTokens", "output"))) {
            if (usage.path(field.get(0)).isIntegralNumber())
                facts.add(UiFormat.count(usage.path(field.get(0)).asLong()) + " " + field.get(1) + " tokens");
        }
        return String.join(" · ", facts);
    }

    private static String milliseconds(long value) {
        return value < 1000 ? value + " ms" : UiFormat.duration(value / 1000);
    }
}
