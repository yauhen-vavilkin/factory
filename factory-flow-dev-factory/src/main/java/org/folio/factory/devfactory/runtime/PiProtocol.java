package org.folio.factory.devfactory.runtime;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Strict final-result validation. Progress observations never authorize a coding result. */
final class PiProtocol {
    private static final Pattern HTTP_STATUS = Pattern.compile("\\b[45][0-9]{2}\\b");
    private static final int FRAME_LIMIT = 512 * 1024;
    private static final List<String> PROVIDER_STOPS = List.of("error", "aborted");
    private static final String INCOMPLETE = "Pi did not return a completed coding result";

    static CodingOutcome parse(String output, String provider, String model) {
        var mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        var result = new FinalResult(provider, model);
        for (var line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.length() > FRAME_LIMIT) {
                if (!isOversizedAgentEnd(line, mapper)) {
                    return result.failed("FRAME_TOO_LARGE", "Pi frame exceeds limit");
                }
                result.ignoreOversizedAgentEnd(line.length());
                continue;
            }
            var event = readFrame(line, mapper);
            if (event.isEmpty()) {
                return result.failed("MALFORMED_FRAME", "Pi returned malformed event output");
            }
            result.apply(event.get());
        }
        return result.outcome();
    }

    private static Optional<JsonNode> readFrame(String line, JsonMapper mapper) {
        try {
            return Optional.of(mapper.readTree(line));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Pi 0.85.1 JSON mode serializes agent_end as {type, messages, willRetry}. Validate the entire
     * frame without building its potentially large message tree.
     */
    private static boolean isOversizedAgentEnd(String line, JsonMapper mapper) {
        try (var parser = mapper.tokenStreamFactory().createParser(line)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            var seen = new HashSet<String>();
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.PROPERTY_NAME) {
                    return false;
                }
                var name = parser.currentName();
                var value = parser.nextToken();
                if (!seen.add(name) || !isAgentEndField(name, value, parser)) {
                    return false;
                }
            }
            return seen.contains("type") && seen.contains("messages") && parser.nextToken() == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Skips the messages array, so the caller reads on from the next field. */
    private static boolean isAgentEndField(String name, JsonToken value, JsonParser parser) {
        return switch (name) {
            case "type" -> value == JsonToken.VALUE_STRING && parser.getText().equals("agent_end");
            case "messages" -> {
                if (value != JsonToken.START_ARRAY) {
                    yield false;
                }
                parser.skipChildren();
                yield true;
            }
            case "willRetry" -> value == JsonToken.VALUE_TRUE || value == JsonToken.VALUE_FALSE;
            default -> false;
        };
    }

    /**
     * Only allow fixed categories and a numeric HTTP status from untrusted provider diagnostics.
     */
    static String providerCause(String raw) {
        if (raw == null || raw.isBlank()) return "cause unavailable";
        var status = HTTP_STATUS.matcher(raw);
        String code = status.find() ? status.group() : "";
        String lower = raw.toLowerCase(Locale.ROOT);
        String category =
                switch (code) {
                    case "400", "422" -> "request rejected";
                    case "401", "403" -> "authentication rejected";
                    case "402" -> "quota exhausted";
                    case "404" -> "model or endpoint unavailable";
                    case "408", "504" -> "request timed out";
                    case "413" -> "request too large";
                    case "429" -> "rate limited";
                    default -> classifyProviderCause(code, lower);
                };
        return code.isEmpty() ? category : "HTTP " + code + " (" + category + ")";
    }

    private static String classifyProviderCause(String code, String lower) {
        if (code.startsWith("5")) return "provider unavailable";
        if (!code.isEmpty()) return "request rejected";
        if (lower.contains("rate limit") || lower.contains("too many requests"))
            return "rate limited";
        if (lower.contains("context") && (lower.contains("overflow") || lower.contains("length")))
            return "context limit exceeded";
        if (lower.contains("timeout") || lower.contains("timed out")) return "request timed out";
        if (lower.contains("connection") || lower.contains("network")) return "network error";
        if (lower.contains("cancel") || lower.contains("abort")) return "Retry cancelled";
        return "cause unavailable";
    }

    static Map<String, Object> withIdentity(
            Map<String, Object> usage, String provider, String model) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("provider", provider);
        metrics.put("model", model);
        metrics.putAll(usage);
        return Map.copyOf(metrics);
    }

    /** What the frames read so far say about the attempt; only the last assistant message counts. */
    private static final class FinalResult {
        private final String provider;
        private final String model;
        private final PiUsage usage = new PiUsage();
        private boolean settled;
        private boolean identityMismatch;
        private boolean retryExhausted;
        private String stopReason = "";
        private String failureDetail = "";
        private String summary = "";

        FinalResult(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }

        void apply(JsonNode event) {
            var type = event.path("type").asString("");
            usage.record(event);
            if (type.equals("agent_start") || type.equals("auto_retry_start")) {
                settled = false;
            }
            if (type.equals("agent_settled")) {
                settled = true;
            }
            if (type.equals("auto_retry_end")) {
                retryEnded(event);
            }
            if (type.equals("message_end")) {
                messageEnded(event.path("message"));
            }
        }

        void ignoreOversizedAgentEnd(int frameChars) {
            usage.ignoredOversizedAgentEnd(frameChars);
        }

        private void retryEnded(JsonNode event) {
            retryExhausted = !event.path("success").asBoolean(false);
            if (retryExhausted && !event.path("finalError").asString("").isBlank()) {
                failureDetail = providerCause(event.path("finalError").asString(""));
            }
        }

        private void messageEnded(JsonNode message) {
            if (!message.path("role").asString("").equals("assistant")) {
                return;
            }
            stopReason = message.path("stopReason").asString("");
            summary = "";
            if (PROVIDER_STOPS.contains(stopReason)) {
                failureDetail = providerCause(message.path("errorMessage").asString(""));
                return;
            }
            retryExhausted = false;
            if (!message.path("provider").asString(provider).equals(provider)
                    || !message.path("model").asString(model).equals(model)) {
                identityMismatch = true;
            }
            var text = assistantText(message.path("content"));
            if (!text.isEmpty()) {
                summary = text;
            }
        }

        private static String assistantText(JsonNode content) {
            var text = new StringBuilder();
            for (var block : content) {
                if (block.path("type").asString("").equals("text")) {
                    text.append(block.path("text").asString(""));
                }
            }
            return text.toString();
        }

        CodingOutcome outcome() {
            if (identityMismatch) {
                return failed("IDENTITY_MISMATCH", "Pi provider/model identity mismatch");
            }
            if (!settled) {
                return failed("INCOMPLETE_RESULT", INCOMPLETE);
            }
            if (stopReason.equals("aborted") || retryExhausted && failureDetail.equals("Retry cancelled")) {
                return failed("PROVIDER_ABORTED", "Pi provider request aborted: " + failureDetail);
            }
            if (stopReason.equals("error")) {
                return failed(retryExhausted ? "PROVIDER_RETRIES_EXHAUSTED" : "PROVIDER_FAILED",
                        "Pi provider request failed: " + failureDetail);
            }
            if (!stopReason.equals("stop") || summary.isBlank()) {
                return failed("INCOMPLETE_RESULT", INCOMPLETE);
            }
            return CodingProtocol.answer(summary, metrics(), "Pi");
        }

        CodingOutcome failed(String code, String message) {
            return CodingOutcome.failed(code, message, metrics());
        }

        private Map<String, Object> metrics() {
            return withIdentity(usage.snapshot(), provider, model);
        }
    }
}
