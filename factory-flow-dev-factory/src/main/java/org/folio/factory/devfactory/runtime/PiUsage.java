package org.folio.factory.devfactory.runtime;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Adds incremental Pi message/compaction usage and emits cumulative attempt snapshots. */
final class PiUsage {

    private long input;
    private long cacheRead;
    private long cacheWrite;
    private long output;
    private int ignoredOversizedFrames;
    private int largestIgnoredFrameChars;
    private BigDecimal cost;
    private boolean inputPresent;
    private boolean cacheReadPresent;
    private boolean cacheWritePresent;
    private boolean outputPresent;
    private boolean incomplete;

    boolean record(JsonNode frame) {
        var reported = reportedUsage(frame);
        boolean changed = reported != null && add(reported);
        String type = frame.path("type").asString("");
        boolean metered =
                (type.equals("message_end")
                                && frame.path("message")
                                        .path("role")
                                        .asString("")
                                        .equals("assistant"))
                        || (type.equals("compaction_end")
                                && !frame.path("aborted").asBoolean(false));
        // Pi substitutes zeroes for missing provider usage. A model response with no
        // output or no input evidence cannot justify a complete cost estimate.
        if (metered && !hasInputAndOutput(reported)) {
            changed |= !incomplete;
            incomplete = true;
        }
        return changed;
    }

    private static boolean hasInputAndOutput(JsonNode reported) {
        return reported != null
                && reported.path("output").asLong(0) > 0
                && (reported.path("input").asLong(0) > 0
                        || reported.path("cacheRead").asLong(0) > 0
                        || reported.path("cacheWrite").asLong(0) > 0);
    }

    boolean add(JsonNode usage) {
        if (!usage.isObject()) return false;
        // Not ||: a frame can report tokens and cost, and both must be added.
        return addTokens(usage) | addCost(usage.path("cost").path("total"));
    }

    private boolean addTokens(JsonNode usage) {
        boolean found = false;
        if (usage.path("input").isNumber()) {
            inputPresent = true;
            input += Math.max(0, usage.path("input").asLong(0));
            found = true;
        }
        if (usage.path("cacheRead").isNumber()) {
            cacheReadPresent = true;
            cacheRead += Math.max(0, usage.path("cacheRead").asLong(0));
            found = true;
        }
        if (usage.path("cacheWrite").isNumber()) {
            cacheWritePresent = true;
            cacheWrite += Math.max(0, usage.path("cacheWrite").asLong(0));
            found = true;
        }
        if (usage.path("output").isNumber()) {
            outputPresent = true;
            output += Math.max(0, usage.path("output").asLong(0));
            found = true;
        }
        return found;
    }

    private boolean addCost(JsonNode total) {
        if (total.isNumber() && total.asDouble() >= 0 && Double.isFinite(total.asDouble())) {
            cost = (cost == null ? BigDecimal.ZERO : cost).add(total.decimalValue());
            return true;
        }
        return false;
    }

    void ignoredOversizedAgentEnd(int frameChars) {
        ignoredOversizedFrames++;
        largestIgnoredFrameChars = Math.max(largestIgnoredFrameChars, frameChars);
    }

    Map<String, Object> snapshot() {
        var tokens = new TokenUsage(inputPresent ? input : null, cacheReadPresent ? cacheRead : null,
                cacheWritePresent ? cacheWrite : null, null, null, outputPresent ? output : null, null, null,
                incomplete);
        Map<String, Object> metrics = new LinkedHashMap<>(tokens.toMetrics());
        if (cost != null) metrics.put("costUsd", cost);
        if (ignoredOversizedFrames > 0) {
            metrics.put("ignoredOversizedPiFrameType", "agent_end");
            metrics.put("ignoredOversizedPiFrameCount", ignoredOversizedFrames);
            metrics.put("largestIgnoredPiFrameChars", largestIgnoredFrameChars);
        }
        return metrics;
    }

    private static JsonNode reportedUsage(JsonNode event) {
        return switch (event.path("type").asString("")) {
            case "message_end" ->
                    event.path("message").path("role").asString("").equals("assistant")
                            ? event.path("message").path("usage")
                            : null;
            case "compaction_end" ->
                    event.path("aborted").asBoolean(false)
                            ? null
                            : event.path("result").path("usage");
            default -> null;
        };
    }
}
