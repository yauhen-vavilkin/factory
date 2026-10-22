package org.folio.factory.devfactory.runtime;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Bounded Claude stream projection. Only a single terminal result can complete an attempt. */
final class ClaudeCodeProtocol implements Consumer<String> {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final String model;
    private final UnaryOperator<String> redact;
    private final Consumer<Map<String, Object>> progress;
    private final Map<String, JsonNode> messages = new LinkedHashMap<>();
    private JsonNode result;
    private String invalid;
    private String streamingMessage = "";
    private int emitted;
    private boolean initialized;

    ClaudeCodeProtocol(String model, UnaryOperator<String> redact, Consumer<Map<String, Object>> progress) {
        this.model = model;
        this.redact = redact;
        this.progress = progress;
    }

    @Override public void accept(String line) {
        if (line.isBlank() || invalid != null) return;
        if (line.length() > 512 * 1024) { invalid = "FRAME_TOO_LARGE"; return; }
        JsonNode frame;
        try { frame = JSON.readTree(line); }
        catch (RuntimeException e) { invalid = "MALFORMED_FRAME"; return; }
        if (!frame.isObject() || !frame.path("type").isString()) { invalid = "MALFORMED_FRAME"; return; }
        if (result != null) { invalid = "INVALID_RESULT_SEQUENCE"; return; }
        switch (frame.path("type").asString()) {
            case "system" -> system(frame);
            case "assistant" -> assistant(frame);
            case "stream_event" -> streamEvent(frame.path("event"));
            case "user" -> {
                for (var block : frame.path("message").path("content"))
                    if (block.path("type").asString("").equals("tool_result"))
                        emit(Map.of("activity", "tool_execution_end", "error", block.path("is_error").asBoolean(false)));
            }
            case "result" -> {
                result = frame;
                usageProgress();
                emit(Map.of("activity", "agent_end"));
            }
            default -> { /* Future diagnostic events are not completion evidence. */ }
        }
    }

    private void system(JsonNode frame) {
        switch (frame.path("subtype").asString("")) {
            case "init" -> {
                if (initialized || !frame.path("model").asString("").equals(model)) invalid = "IDENTITY_MISMATCH";
                initialized = true;
                emit(Map.of("activity", "agent_start"));
            }
            case "api_retry" -> emit(Map.of("activity", "auto_retry_start",
                    "attempt", Math.max(0, frame.path("attempt").asInt(0)),
                    "maxAttempts", Math.max(0, frame.path("max_retries").asInt(0))));
            default -> { }
        }
    }

    private void assistant(JsonNode frame) {
        var message = frame.path("message");
        String id = message.path("id").asString("");
        if (!id.isBlank()) {
            // Multiple content blocks can report the same message: replace, never double count.
            messages.put(id, message.path("usage"));
            usageProgress();
        }
        for (var block : message.path("content"))
            if (block.path("type").asString("").equals("tool_use")) {
                String tool = redact.apply(block.path("name").asString(""));
                emit(Map.of("activity", "tool_execution_start", "tool",
                        tool.replaceAll("[\\p{Cntrl}]", " ").substring(0, Math.min(80, tool.length()))));
            }
    }

    private void streamEvent(JsonNode event) {
        switch (event.path("type").asString("")) {
            case "message_start" -> {
                streamingMessage = event.path("message").path("id").asString("");
                if (!streamingMessage.isBlank())
                    messages.put(streamingMessage, event.path("message").path("usage"));
            }
            case "message_delta" -> {
                // Assistant content is emitted before this final per-message output count.
                if (streamingMessage.isBlank()) return;
                var usage = JSON.createObjectNode();
                var previous = messages.get(streamingMessage);
                if (previous != null && previous.isObject()) previous.properties().forEach(e -> usage.set(e.getKey(), e.getValue()));
                if (event.path("usage").isObject()) event.path("usage").properties().forEach(e -> usage.set(e.getKey(), e.getValue()));
                messages.put(streamingMessage, usage);
                usageProgress();
            }
            case "message_stop" -> streamingMessage = "";
            default -> { /* Text/tool deltas contain private content, not usage evidence. */ }
        }
    }

    private void usageProgress() {
        var event = new LinkedHashMap<>(metrics());
        event.put("activity", "coding_usage");
        progress.accept(event);
    }

    private void emit(Map<String, Object> event) { if (emitted++ < 200) progress.accept(event); }

    Map<String, Object> metrics() {
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("provider", "anthropic-messages");
        metrics.put("model", model);
        boolean complete = result != null && result.path("usage").isObject();
        var usage = complete ? java.util.List.of(result.path("usage")) : messages.values();
        boolean missing = usage.isEmpty() || messages.values().stream().anyMatch(item -> !hasTokenEvidence(item));
        for (var item : usage) {
            missing |= !hasTokenEvidence(item);
            add(metrics, item, "input_tokens", "inputTokens");
            add(metrics, item, "output_tokens", "outputTokens");
            add(metrics, item, "cache_read_input_tokens", "cacheReadTokens");
            add(metrics, item, "cache_creation_input_tokens", "cacheWriteTokens");
            add(metrics, item.path("cache_creation"), "ephemeral_5m_input_tokens", "cacheWrite5mTokens");
            add(metrics, item.path("cache_creation"), "ephemeral_1h_input_tokens", "cacheWrite1hTokens");
        }
        if (!complete || missing) metrics.put("usageIncomplete", true);
        // Claude's cost is a client estimate, not the operator endpoint's bill. Factory uses its rate card.
        if (result != null && result.path("total_cost_usd").isNumber()
                && Double.isFinite(result.path("total_cost_usd").asDouble())
                && result.path("total_cost_usd").asDouble() >= 0)
            metrics.put("runtimeEstimatedCostUsd", result.path("total_cost_usd").decimalValue());
        return metrics;
    }

    private static boolean hasTokenEvidence(JsonNode usage) {
        // Claude substitutes zeroes when the endpoint omits usage. A generated answer
        // needs positive output and some input evidence, including fully cached input.
        return usage.path("input_tokens").isIntegralNumber() && usage.path("input_tokens").asLong() >= 0
                && positive(usage.path("output_tokens"))
                && (positive(usage.path("input_tokens")) || positive(usage.path("cache_read_input_tokens"))
                    || positive(usage.path("cache_creation_input_tokens")));
    }

    private static boolean positive(JsonNode value) { return value.isIntegralNumber() && value.asLong() > 0; }

    private static void add(Map<String, Object> metrics, JsonNode item, String from, String to) {
        if (item.path(from).isIntegralNumber() && item.path(from).asLong() >= 0)
            metrics.merge(to, item.path(from).asLong(), (a, b) -> (Long) a + (Long) b);
    }

    CodingOutcome outcome(int exitCode) {
        if (invalid != null) return failed(invalid, "Claude Code returned an invalid event stream");
        if (result != null && (result.path("is_error").asBoolean(true)
                || !result.path("subtype").asString("").equals("success"))) {
            String code = switch (result.path("subtype").asString("")) {
                case "error_max_turns" -> "TURN_LIMIT";
                case "error_max_budget_usd" -> "BUDGET_LIMIT";
                default -> "PROVIDER_FAILED";
            };
            return failed(code, "Claude Code did not complete the attempt (" + code + ")");
        }
        if (exitCode != 0) return failed("RUNTIME_EXIT", "Claude Code exited with code " + exitCode);
        if (!initialized || result == null || !result.path("is_error").isBoolean()
                || result.path("result").asString("").isBlank())
            return failed("INCOMPLETE_RESULT", "Claude Code did not return a completed coding result");
        return CodingProtocol.answer(redact.apply(result.path("result").asString()), metrics(), "Claude Code");
    }

    private CodingOutcome failed(String code, String message) { return CodingOutcome.failed(code, message, metrics()); }
}
