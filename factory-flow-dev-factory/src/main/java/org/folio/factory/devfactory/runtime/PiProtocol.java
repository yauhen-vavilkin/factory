package org.folio.factory.devfactory.runtime;

import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict final-result validation. Progress observations never authorize a coding result. */
final class PiProtocol {
    private static final Pattern HTTP_STATUS = Pattern.compile("\\b[45][0-9]{2}\\b");

    static CodingOutcome parse(String output, String provider, String model) {
        var mapper =
                JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        boolean settled = false;
        boolean identityMismatch = false;
        boolean retryExhausted = false;
        String stopReason = "";
        String failureDetail = "";
        String summary = "";
        var usage = new PiUsage();
        for (String line : output.split("\n")) {
            if (line.isBlank()) continue;
            if (line.length() > 512 * 1024) {
                if (isOversizedAgentEnd(line, mapper)) {
                    usage.ignoredOversizedAgentEnd(line.length());
                    continue;
                }
                return CodingOutcome.failed(
                        "FRAME_TOO_LARGE",
                        "Pi frame exceeds limit",
                        withIdentity(usage.snapshot(), provider, model));
            }
            JsonNode event;
            try {
                event = mapper.readTree(line);
            } catch (RuntimeException e) {
                return CodingOutcome.failed(
                        "MALFORMED_FRAME",
                        "Pi returned malformed event output",
                        withIdentity(usage.snapshot(), provider, model));
            }
            String type = event.path("type").asString("");
            usage.record(event);
            if (type.equals("agent_start") || type.equals("auto_retry_start")) settled = false;
            if (type.equals("agent_settled")) settled = true;
            if (type.equals("auto_retry_end")) {
                retryExhausted = !event.path("success").asBoolean(false);
                if (retryExhausted && !event.path("finalError").asString("").isBlank())
                    failureDetail = providerCause(event.path("finalError").asString(""));
            }
            if (type.equals("message_end")) {
                var message = event.path("message");
                if (!message.path("role").asString("").equals("assistant")) continue;
                stopReason = message.path("stopReason").asString("");
                summary = "";
                if (List.of("error", "aborted").contains(stopReason))
                    failureDetail = providerCause(message.path("errorMessage").asString(""));
                else retryExhausted = false;
                if (List.of("error", "aborted").contains(stopReason)) continue;
                if (!message.path("provider").asString(provider).equals(provider)
                        || !message.path("model").asString(model).equals(model))
                    identityMismatch = true;
                StringBuilder text = new StringBuilder();
                for (var block : message.path("content"))
                    if (block.path("type").asString("").equals("text"))
                        text.append(block.path("text").asString(""));
                if (!text.isEmpty()) summary = text.toString();
            }
        }
        Map<String, Object> metrics = withIdentity(usage.snapshot(), provider, model);
        if (identityMismatch)
            return CodingOutcome.failed(
                    "IDENTITY_MISMATCH", "Pi provider/model identity mismatch", metrics);
        if (!settled)
            return CodingOutcome.failed(
                    "INCOMPLETE_RESULT", "Pi did not return a completed coding result", metrics);
        if (stopReason.equals("aborted")
                || retryExhausted && failureDetail.equals("Retry cancelled"))
            return CodingOutcome.failed(
                    "PROVIDER_ABORTED", "Pi provider request aborted: " + failureDetail, metrics);
        if (stopReason.equals("error"))
            return CodingOutcome.failed(
                    retryExhausted ? "PROVIDER_RETRIES_EXHAUSTED" : "PROVIDER_FAILED",
                    "Pi provider request failed: " + failureDetail,
                    metrics);
        if (!stopReason.equals("stop") || summary.isBlank())
            return CodingOutcome.failed(
                    "INCOMPLETE_RESULT", "Pi did not return a completed coding result", metrics);
        String normalized = summary.stripLeading();
        if (normalized.startsWith("FACTORY_DECISION_REQUIRED:")) {
            String detail = normalized.substring("FACTORY_DECISION_REQUIRED:".length()).strip();
            if (detail.contains("\n") || detail.contains("\r"))
                return CodingOutcome.failed(
                        "INVALID_DECISION_RESULT",
                        "Pi returned an invalid decision request",
                        metrics);
            try {
                var decision = mapper.readTree(detail);
                var options = new ArrayList<String>();
                decision.path("options").forEach(option -> options.add(option.asString("")));
                return CodingOutcome.needsDecision(
                        new CodingOutcome.Decision(
                                CodingOutcome.DecisionKind.valueOf(
                                        decision.path("kind").asString("")),
                                decision.path("question").asString(""),
                                options,
                                decision.path("evidence").asString("")),
                        metrics);
            } catch (RuntimeException e) {
                return CodingOutcome.failed(
                        "INVALID_DECISION_RESULT",
                        "Pi returned an invalid decision request",
                        metrics);
            }
        }
        return CodingOutcome.completed(
                summary.substring(0, Math.min(8000, summary.length())), metrics);
    }

    /**
     * Pi 0.85.1 JSON mode serializes agent_end as {type, messages, willRetry}. Validate the entire
     * frame without building its potentially large message tree.
     */
    private static boolean isOversizedAgentEnd(String line, JsonMapper mapper) {
        try (var parser = mapper.tokenStreamFactory().createParser(line)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return false;
            boolean type = false, messages = false, willRetry = false;
            JsonToken token;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                if (token != JsonToken.PROPERTY_NAME) return false;
                String name = parser.currentName();
                token = parser.nextToken();
                if (name.equals("type")) {
                    if (type
                            || token != JsonToken.VALUE_STRING
                            || !parser.getText().equals("agent_end")) return false;
                    type = true;
                } else if (name.equals("messages")) {
                    if (messages || token != JsonToken.START_ARRAY) return false;
                    parser.skipChildren();
                    messages = true;
                } else if (name.equals("willRetry")) {
                    if (willRetry
                            || token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE)
                        return false;
                    willRetry = true;
                } else return false;
            }
            return type && messages && parser.nextToken() == null;
        } catch (RuntimeException e) {
            return false;
        }
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
}
