package org.folio.factory.devfactory.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The only reader of the Claude Code stream. One pass over the lines gives the outcome, the live
 * activity and the transcript; only a single terminal result can complete an attempt.
 */
final class ClaudeCodeProtocol implements Consumer<String> {
    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeProtocol.class);
    static final int TOOL_RESULT_LIMIT = 8 * 1024;
    static final int TRANSCRIPT_LIMIT = 5 * 1024 * 1024;
    private static final int MAX_TOOL_NAME_LENGTH = 80;
    private static final int SUMMARY_LIMIT = 240;
    private static final int REASON_DETAIL_LIMIT = 300;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final String model;
    private final int maxTurns;
    private final UnaryOperator<String> redactor;
    private final Consumer<Map<String, Object>> progressListener;
    private final CodingTranscripts.Sink transcript;
    private final Map<String, JsonNode> messageUsage = new LinkedHashMap<>();
    private final Map<String, ToolCall> openTools = new HashMap<>();
    private JsonNode terminalResult;
    private CodingOutcome failure;
    private String streamingMessageId = "";
    private boolean initialized;
    private long transcriptSize;
    private boolean transcriptCut;
    private boolean activityLost;

    ClaudeCodeProtocol(String model, UnaryOperator<String> redactor, Consumer<Map<String, Object>> progressListener) {
        this(model, 0, redactor, progressListener, CodingTranscripts.NONE);
    }

    ClaudeCodeProtocol(String model, int maxTurns, UnaryOperator<String> redactor,
                       Consumer<Map<String, Object>> progressListener, CodingTranscripts.Sink transcript) {
        this.model = model;
        this.maxTurns = maxTurns;
        this.redactor = redactor;
        this.progressListener = progressListener;
        this.transcript = transcript;
    }

    private record ToolCall(String tool, String summaryKey, String summary, long startedNanos) {
    }

    /** True once the stream can no longer complete the attempt; reading more lines would only cost money. */
    boolean failed() {
        return failure != null;
    }

    /** The first transcript entry: the exact prompt sent to the CLI. */
    void prompt(String text) {
        record("prompt", Map.of("text", redactor.apply(text)));
    }

    @Override
    public void accept(String line) {
        if (line.isBlank() || failure != null) {
            return;
        }
        try {
            read(line);
        } catch (RuntimeException exception) {
            fail("PARSER_ERROR", "Factory could not process the Claude Code output ("
                    + exception.getClass().getSimpleName() + "), so it stopped the attempt. Retry the task;"
                    + " if it happens again, report it together with the transcript.");
        }
    }

    private void read(String line) {
        JsonNode frame;
        try {
            frame = JSON.readTree(line);
        } catch (RuntimeException exception) {
            frame = null;
        }
        if (frame == null || !frame.isObject() || !frame.path("type").isString()) {
            fail("MALFORMED_FRAME", "Claude Code wrote an output line that is not a valid event, so Factory"
                    + " stopped the attempt. Retry the task; if it happens again, check that the coding image"
                    + " runs Claude Code " + ClaudeCodeCodingRuntime.VERSION + ".");
            return;
        }
        if (terminalResult != null) {
            fail("INVALID_RESULT_SEQUENCE", "Claude Code wrote more output after its final result, so Factory"
                    + " cannot trust the result. Retry the task.");
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
                recordResult(frame);
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
                if (initialized) {
                    fail("IDENTITY_MISMATCH", "Claude Code started a second session in one run, so Factory"
                            + " stopped it. Retry the task.");
                    return;
                }
                if (!reportedModel.equals(model)) {
                    fail("IDENTITY_MISMATCH", "Claude Code started with model " + summary(reportedModel)
                            + " instead of " + model + ", so Factory stopped it. Check the model in the Developer"
                            + " Flow settings and retry.");
                    return;
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
            switch (block.path("type").asString("")) {
                case "text" -> {
                    String text = block.path("text").asString("");
                    if (!text.isBlank()) record("text", Map.of("text", redactor.apply(text)));
                }
                case "tool_use" -> startTool(block);
                default -> {
                    // Thinking blocks stay private: they never reach activity or the transcript.
                }
            }
        }
    }

    private void startTool(JsonNode block) {
        String tool = toolName(block.path("name").asString(""));
        String id = block.path("id").asString("");
        JsonNode input = block.path("input");
        String key = summaryKey(tool);
        String summary = key == null ? "" : summary(input.path(summaryField(tool)).asString(""));
        openTools.put(id, new ToolCall(tool, key, summary, System.nanoTime()));
        var event = new LinkedHashMap<String, Object>();
        event.put("activity", "tool_execution_start");
        event.put("tool", tool);
        if (!summary.isBlank()) event.put(key, summary);
        emitActivity(event);
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", id);
        entry.put("tool", tool);
        entry.put("input", redactor.apply(input.isMissingNode() ? "{}"
                : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(input)));
        record("tool_call", entry);
    }

    private void handleToolResults(JsonNode frame) {
        for (JsonNode block : frame.path("message").path("content")) {
            if (!"tool_result".equals(block.path("type").asString(""))) continue;
            ToolCall call = openTools.remove(block.path("tool_use_id").asString(""));
            boolean error = block.path("is_error").asBoolean(false);
            String text = redactor.apply(resultText(block.path("content")));
            var event = new LinkedHashMap<String, Object>();
            event.put("activity", "tool_execution_end");
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", block.path("tool_use_id").asString(""));
            if (call != null) {
                long durationMs = Math.max(0, (System.nanoTime() - call.startedNanos()) / 1_000_000);
                event.put("tool", call.tool());
                if (!call.summary().isBlank()) event.put(call.summaryKey(), call.summary());
                event.put("durationMs", durationMs);
                entry.put("tool", call.tool());
                entry.put("durationMs", durationMs);
            }
            event.put("error", error);
            if (error) event.put("errorText", summary(text));
            emitActivity(event);
            entry.put("error", error);
            entry.put("text", text.length() <= TOOL_RESULT_LIMIT ? text
                    : text.substring(0, TOOL_RESULT_LIMIT) + "\n[cut: " + (text.length() - TOOL_RESULT_LIMIT)
                            + " more characters]");
            record("tool_result", entry);
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

    private void recordResult(JsonNode result) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("subtype", clip(result.path("subtype").asString(""), 80));
        entry.put("error", result.path("is_error").asBoolean(true));
        entry.put("text", redactor.apply(String.join("\n", errorsOrResult(result))));
        if (result.path("num_turns").isIntegralNumber()) entry.put("turns", result.path("num_turns").asLong());
        if (result.path("duration_ms").isIntegralNumber()) entry.put("durationMs", result.path("duration_ms").asLong());
        var usage = new LinkedHashMap<String, Object>();
        addUsage(usage, result.path("usage"));
        entry.put("usage", usage);
        record("result", entry);
    }

    private void publishUsage() {
        var event = new LinkedHashMap<>(metrics());
        event.put("activity", "coding_usage");
        emitActivity(event);
    }

    private void emitActivity(Map<String, Object> event) {
        try {
            progressListener.accept(event);
        } catch (RuntimeException e) {
            // Live activity is best effort; it never decides the outcome. Log and note it once per run,
            // so a database outage does not flood the log.
            if (activityLost) return;
            activityLost = true;
            log.warn("Could not save Claude Code activity; later failures in this run are not logged: {}",
                    redactor.apply(String.valueOf(e.getMessage())));
            record("note", Map.of("text", "Some activity events could not be saved, so the activity log and"
                    + " this transcript may be incomplete."));
        }
    }

    private void record(String kind, Map<String, Object> detail) {
        if (transcriptCut) return;
        long size = detail.values().stream().mapToLong(value -> String.valueOf(value).length()).sum();
        if (transcriptSize + size > TRANSCRIPT_LIMIT) {
            transcriptCut = true;
            kind = "note";
            detail = Map.of("text", "Transcript cut at 5 MB; the attempt went on.");
        }
        transcriptSize += size;
        try {
            transcript.add(new CodingTranscripts.Entry(kind, Instant.now(),
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(detail))));
        } catch (RuntimeException ignored) {
            // The sink logs and marks its own write failures; the attempt goes on.
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

    /** The outcome of a finished command: Factory's own stops first, then what the CLI reported. */
    CodingOutcome outcome(Processes.Streamed run, int timeoutSeconds) {
        if (run.timedOut()) {
            long minutes = Math.max(1, Math.round(timeoutSeconds / 60.0));
            return fail("TIME_LIMIT", "Claude Code did not finish within the time limit of " + minutes
                    + (minutes == 1 ? " minute" : " minutes") + ", so Factory stopped it. Retry with a lower effort"
                    + " or a smaller task, or raise the limit (FACTORY_DEV_FACTORY_TIMEOUT_SECONDS,"
                    + " FACTORY_PILOT_EXECUTION_DEADLINE).");
        }
        if (run.lineTooLarge() && failure == null) {
            return fail("FRAME_TOO_LARGE", "Claude Code wrote one output line larger than "
                    + Processes.STREAM_LINE_LIMIT / (1024 * 1024) + " MB, so Factory stopped it. Retry the task;"
                    + " if it happens again, look in the transcript for the tool that returned so much output.");
        }
        return outcome(run.exitCode(), run.stderrTail());
    }

    CodingOutcome outcome(int exitCode) {
        return outcome(exitCode, "");
    }

    private CodingOutcome outcome(int exitCode, String stderrTail) {
        if (failure != null) {
            return failure;
        }
        if (terminalResult != null && (terminalResult.path("is_error").asBoolean(true)
                || !"success".equals(terminalResult.path("subtype").asString("")))) {
            String subtype = clip(terminalResult.path("subtype").asString(""), 80);
            String text = String.join("; ", errorsOrResult(terminalResult));
            return switch (subtype) {
                case "error_max_turns" -> fail("TURN_LIMIT", "Claude Code used all " + maxTurns
                        + " turns before it finished the task. Retry with more turns or a different effort.");
                case "error_max_budget_usd" -> fail("BUDGET_LIMIT", "Claude Code reached its spending limit"
                        + " before it finished the task. Retry with a lower effort or a smaller task."
                        + detail("CLI message", text, false));
                default -> fail("PROVIDER_FAILED", "Claude Code stopped with an error (subtype "
                        + (subtype.isBlank() ? "unknown" : subtype) + apiStatus(terminalResult) + "). Check the"
                        + " provider and the Developer Flow settings, then retry." + detail("Error", text, false));
            };
        }
        if (exitCode != 0) {
            if (!initialized && exitCode >= 125 && exitCode <= 127) {
                return fail("RUNTIME_ERROR", "Docker could not start Claude Code in the coding container (exit "
                        + exitCode + "). Check that Docker is running and the coding image is correct, then retry."
                        + detail("Last error output", stderrTail, true));
            }
            return fail("RUNTIME_EXIT", "Claude Code exited with code " + exitCode + " before it returned a"
                    + " result. Check the coding runtime settings and retry."
                    + detail("Last error output", stderrTail, true));
        }
        if (!initialized || terminalResult == null || !terminalResult.path("is_error").isBoolean()
                || terminalResult.path("result").asString("").isBlank()) {
            return fail("INCOMPLETE_RESULT", "Claude Code ended without a final answer. Retry the task."
                    + detail("Last error output", stderrTail, true));
        }
        String answer = redactor.apply(terminalResult.path("result").asString());
        var outcome = CodingProtocol.answer(answer, metrics(), "Claude Code");
        if (outcome.status() == CodingOutcome.Status.FAILED) note(outcome.failure().message());
        return outcome;
    }

    /** A failure found outside the stream, such as a Docker error before the CLI started. */
    CodingOutcome fail(String code, String message) {
        if (failure == null) {
            failure = CodingOutcome.failed(code, message, metrics());
            note(message);
        }
        return failure;
    }

    private void note(String text) {
        record("note", Map.of("text", "Factory stopped the run: " + text));
    }

    private List<String> errorsOrResult(JsonNode result) {
        var texts = new ArrayList<String>();
        result.path("errors").forEach(error -> texts.add(error.isString() ? error.asString() : error.toString()));
        if (texts.isEmpty() && !result.path("result").asString("").isBlank()) texts.add(result.path("result").asString());
        return texts;
    }

    private static String apiStatus(JsonNode result) {
        JsonNode status = result.path("api_error_status");
        return status.isIntegralNumber() ? ", API status " + status.asInt() : "";
    }

    /** At most {@link #REASON_DETAIL_LIMIT} redacted characters; redaction runs before the cut. */
    private String detail(String label, String text, boolean keepEnd) {
        String clean = oneLine(redactor.apply(text == null ? "" : text));
        if (clean.isBlank()) return "";
        if (clean.length() > REASON_DETAIL_LIMIT) clean = keepEnd
                ? "…" + clean.substring(clean.length() - REASON_DETAIL_LIMIT)
                : clean.substring(0, REASON_DETAIL_LIMIT) + "…";
        return " " + label + ": " + clean;
    }

    private String summary(String text) {
        return clip(oneLine(redactor.apply(text)), SUMMARY_LIMIT);
    }

    private String toolName(String name) {
        return clip(oneLine(redactor.apply(name)), MAX_TOOL_NAME_LENGTH);
    }

    private static String summaryKey(String tool) {
        return switch (tool) {
            case "Bash" -> "command";
            case "Read", "Edit", "Write", "NotebookEdit" -> "path";
            case "Glob", "Grep" -> "pattern";
            case "WebFetch" -> "url";
            default -> null;
        };
    }

    private static String summaryField(String tool) {
        return switch (tool) {
            case "Bash" -> "command";
            case "NotebookEdit" -> "notebook_path";
            case "Read", "Edit", "Write" -> "file_path";
            case "Glob", "Grep" -> "pattern";
            case "WebFetch" -> "url";
            default -> "";
        };
    }

    private static String resultText(JsonNode content) {
        if (content.isString()) return content.asString();
        if (!content.isArray()) return content.isMissingNode() || content.isNull() ? "" : content.toString();
        var parts = new ArrayList<String>();
        for (JsonNode part : content) {
            switch (part.path("type").asString("")) {
                case "text" -> parts.add(part.path("text").asString(""));
                case "image" -> parts.add("[image]");
                default -> parts.add(part.toString());
            }
        }
        return String.join("\n", parts);
    }

    private static String oneLine(String text) {
        return text.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    }

    private static String clip(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
