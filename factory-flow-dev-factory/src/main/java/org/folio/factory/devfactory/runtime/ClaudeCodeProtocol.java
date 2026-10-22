package org.folio.factory.devfactory.runtime;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Bounded Claude stream projection. Only a single terminal result can complete an attempt. */
final class ClaudeCodeProtocol implements Consumer<String> {
    private static final int MAX_FRAME_LENGTH = 512 * 1024;
    private static final int MAX_ACTIVITY_EVENTS = 200;
    private static final int MAX_TOOL_NAME_LENGTH = 80;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final String model;
    private final UnaryOperator<String> redactor;
    private final Consumer<Map<String, Object>> progressListener;
    private final Map<String, JsonNode> messageUsage = new LinkedHashMap<>();
    private JsonNode terminalResult;
    private String protocolErrorCode;
    private String streamingMessageId = "";
    private int emittedActivityEvents;
    private boolean initialized;

    ClaudeCodeProtocol(
            String model,
            UnaryOperator<String> redactor,
            Consumer<Map<String, Object>> progressListener) {
        this.model = model;
        this.redactor = redactor;
        this.progressListener = progressListener;
    }

    @Override
    public void accept(String line) {
        if (line.isBlank() || protocolErrorCode != null) {
            return;
        }
        if (line.length() > MAX_FRAME_LENGTH) {
            protocolErrorCode = "FRAME_TOO_LARGE";
            return;
        }

        JsonNode frame;
        try {
            frame = JSON.readTree(line);
        } catch (RuntimeException exception) {
            protocolErrorCode = "MALFORMED_FRAME";
            return;
        }
        if (!frame.isObject() || !frame.path("type").isString()) {
            protocolErrorCode = "MALFORMED_FRAME";
            return;
        }
        if (terminalResult != null) {
            protocolErrorCode = "INVALID_RESULT_SEQUENCE";
            return;
        }

        switch (frame.path("type").asString()) {
            case "system" -> handleSystemFrame(frame);
            case "assistant" -> handleAssistantFrame(frame);
            case "stream_event" -> handleStreamEvent(frame.path("event"));
            case "user" -> handleToolResults(frame);
            case "result" -> {
                terminalResult = frame;
                publishUsage();
                emitActivity(Map.of("activity", "agent_end"));
            }
            default -> {
                // Future diagnostic events are not completion evidence.
            }
        }
    }

    private void handleSystemFrame(JsonNode frame) {
        switch (frame.path("subtype").asString("")) {
            case "init" -> {
                String reportedModel = frame.path("model").asString("");
                if (initialized || !reportedModel.equals(model)) {
                    protocolErrorCode = "IDENTITY_MISMATCH";
                }
                initialized = true;
                emitActivity(Map.of("activity", "agent_start"));
            }
            case "api_retry" -> emitActivity(Map.of(
                    "activity", "auto_retry_start",
                    "attempt", Math.max(0, frame.path("attempt").asInt(0)),
                    "maxAttempts", Math.max(0, frame.path("max_retries").asInt(0))));
            default -> {
                // Other system events do not affect the attempt state.
            }
        }
    }

    private void handleAssistantFrame(JsonNode frame) {
        JsonNode message = frame.path("message");
        String messageId = message.path("id").asString("");
        if (!messageId.isBlank()) {
            // Multiple content blocks can report the same message: replace, never double count.
            messageUsage.put(messageId, message.path("usage"));
            publishUsage();
        }
        for (JsonNode block : message.path("content")) {
            if ("tool_use".equals(block.path("type").asString(""))) {
                String toolName = redactor.apply(block.path("name").asString(""));
                String safeToolName = toolName.replaceAll("[\\p{Cntrl}]", " ");
                safeToolName = safeToolName.substring(0, Math.min(MAX_TOOL_NAME_LENGTH, safeToolName.length()));
                emitActivity(Map.of("activity", "tool_execution_start", "tool", safeToolName));
            }
        }
    }

    private void handleToolResults(JsonNode frame) {
        for (JsonNode block : frame.path("message").path("content")) {
            if ("tool_result".equals(block.path("type").asString(""))) {
                emitActivity(Map.of(
                        "activity", "tool_execution_end",
                        "error", block.path("is_error").asBoolean(false)));
            }
        }
    }

    private void handleStreamEvent(JsonNode event) {
        switch (event.path("type").asString("")) {
            case "message_start" -> {
                JsonNode message = event.path("message");
                streamingMessageId = message.path("id").asString("");
                if (!streamingMessageId.isBlank()) {
                    messageUsage.put(streamingMessageId, message.path("usage"));
                }
            }
            case "message_delta" -> updateStreamingUsage(event.path("usage"));
            case "message_stop" -> streamingMessageId = "";
            default -> {
                // Text/tool deltas contain private content, not usage evidence.
            }
        }
    }

    private void updateStreamingUsage(JsonNode delta) {
        // Assistant content is emitted before this final per-message output count.
        if (streamingMessageId.isBlank()) {
            return;
        }
        ObjectNode mergedUsage = JSON.createObjectNode();
        copyUsageFields(mergedUsage, messageUsage.get(streamingMessageId));
        copyUsageFields(mergedUsage, delta);
        messageUsage.put(streamingMessageId, mergedUsage);
        publishUsage();
    }

    private static void copyUsageFields(ObjectNode target, JsonNode source) {
        if (source != null && source.isObject()) {
            for (var entry : source.properties()) {
                target.set(entry.getKey(), entry.getValue());
            }
        }
    }

    private void publishUsage() {
        var event = new LinkedHashMap<>(metrics());
        event.put("activity", "coding_usage");
        progressListener.accept(event);
    }

    private void emitActivity(Map<String, Object> event) {
        boolean withinLimit = emittedActivityEvents < MAX_ACTIVITY_EVENTS;
        emittedActivityEvents++;
        if (withinLimit) {
            progressListener.accept(event);
        }
    }

    Map<String, Object> metrics() {
        var metrics = new LinkedHashMap<String, Object>();
        metrics.put("provider", "anthropic-messages");
        metrics.put("model", model);

        boolean hasTerminalUsage = terminalResult != null && terminalResult.path("usage").isObject();
        Collection<JsonNode> usageSnapshots = messageUsage.values();
        if (hasTerminalUsage) {
            usageSnapshots = List.of(terminalResult.path("usage"));
        }
        boolean hasCompleteUsage = hasTerminalUsage
                && !usageSnapshots.isEmpty()
                && messageUsage.values().stream().allMatch(ClaudeCodeProtocol::hasTokenEvidence);
        for (JsonNode usage : usageSnapshots) {
            if (!hasTokenEvidence(usage)) {
                hasCompleteUsage = false;
            }
            addUsage(metrics, usage);
        }
        if (!hasCompleteUsage) {
            metrics.put("usageIncomplete", true);
        }
        addRuntimeEstimate(metrics);
        return metrics;
    }

    private static void addUsage(Map<String, Object> metrics, JsonNode usage) {
        addTokenCount(metrics, usage, "input_tokens", "inputTokens");
        addTokenCount(metrics, usage, "output_tokens", "outputTokens");
        addTokenCount(metrics, usage, "cache_read_input_tokens", "cacheReadTokens");
        addTokenCount(metrics, usage, "cache_creation_input_tokens", "cacheWriteTokens");

        JsonNode cacheCreation = usage.path("cache_creation");
        addTokenCount(metrics, cacheCreation, "ephemeral_5m_input_tokens", "cacheWrite5mTokens");
        addTokenCount(metrics, cacheCreation, "ephemeral_1h_input_tokens", "cacheWrite1hTokens");
    }

    private void addRuntimeEstimate(Map<String, Object> metrics) {
        if (terminalResult == null) {
            return;
        }
        JsonNode estimate = terminalResult.path("total_cost_usd");
        if (estimate.isNumber() && Double.isFinite(estimate.asDouble()) && estimate.asDouble() >= 0) {
            // Claude's cost is a client estimate, not the operator endpoint's bill.
            metrics.put("runtimeEstimatedCostUsd", estimate.decimalValue());
        }
    }

    private static boolean hasTokenEvidence(JsonNode usage) {
        // Claude substitutes zeroes when the endpoint omits usage. A generated answer
        // needs positive output and some input evidence, including fully cached input.
        JsonNode inputTokens = usage.path("input_tokens");
        boolean hasInputEvidence = positive(inputTokens)
                || positive(usage.path("cache_read_input_tokens"))
                || positive(usage.path("cache_creation_input_tokens"));
        return inputTokens.isIntegralNumber()
                && inputTokens.asLong() >= 0
                && positive(usage.path("output_tokens"))
                && hasInputEvidence;
    }

    private static boolean positive(JsonNode value) {
        return value.isIntegralNumber() && value.asLong() > 0;
    }

    private static void addTokenCount(
            Map<String, Object> metrics, JsonNode usage, String providerField, String metricField) {
        JsonNode value = usage.path(providerField);
        if (value.isIntegralNumber() && value.asLong() >= 0) {
            metrics.merge(metricField, value.asLong(), (previous, current) -> (Long) previous + (Long) current);
        }
    }

    CodingOutcome outcome(int exitCode) {
        if (protocolErrorCode != null) {
            return failed(protocolErrorCode, "Claude Code returned an invalid event stream");
        }
        if (terminalResult != null && (terminalResult.path("is_error").asBoolean(true)
                || !"success".equals(terminalResult.path("subtype").asString("")))) {
            String code = switch (terminalResult.path("subtype").asString("")) {
                case "error_max_turns" -> "TURN_LIMIT";
                case "error_max_budget_usd" -> "BUDGET_LIMIT";
                default -> "PROVIDER_FAILED";
            };
            return failed(code, "Claude Code did not complete the attempt (" + code + ")");
        }
        if (exitCode != 0) {
            return failed("RUNTIME_EXIT", "Claude Code exited with code " + exitCode);
        }
        if (!initialized || terminalResult == null || !terminalResult.path("is_error").isBoolean()
                || terminalResult.path("result").asString("").isBlank()) {
            return failed("INCOMPLETE_RESULT", "Claude Code did not return a completed coding result");
        }
        String answer = redactor.apply(terminalResult.path("result").asString());
        return CodingProtocol.answer(answer, metrics(), "Claude Code");
    }

    private CodingOutcome failed(String code, String message) {
        return CodingOutcome.failed(code, message, metrics());
    }
}
