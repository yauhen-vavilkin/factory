package org.folio.factory.devfactory.runtime;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Token counts as a coding runtime or LLM step reported them; null means not reported. The component names are
 * the metric keys of audit details, coding outcomes and PR feedback state, so {@link #toMetrics()} and
 * {@link #from} keep that JSON as it is.
 */
public record TokenUsage(Long inputTokens, Long cacheReadTokens, Long cacheWriteTokens, Long cacheWrite5mTokens,
                         Long cacheWrite1hTokens, Long outputTokens, Long promptTokens, Long completionTokens,
                         boolean usageIncomplete) {
    public static final TokenUsage NONE = new TokenUsage(null, null, null, null, null, null, null, null, false);

    private static final String INPUT = "inputTokens";
    private static final String CACHE_READ = "cacheReadTokens";
    private static final String CACHE_WRITE = "cacheWriteTokens";
    private static final String CACHE_WRITE_5M = "cacheWrite5mTokens";
    private static final String CACHE_WRITE_1H = "cacheWrite1hTokens";
    private static final String OUTPUT = "outputTokens";
    private static final String PROMPT = "promptTokens";
    private static final String COMPLETION = "completionTokens";
    private static final String INCOMPLETE = "usageIncomplete";

    public static TokenUsage from(JsonNode metrics) {
        return read(key -> metrics.path(key).isNumber() ? metrics.path(key).asLong() : null,
                metrics.path(INCOMPLETE).asBoolean(false));
    }

    public static TokenUsage from(Map<String, ?> metrics) {
        return read(key -> metrics.get(key) instanceof Number count ? count.longValue() : null,
                Boolean.TRUE.equals(metrics.get(INCOMPLETE)));
    }

    private static TokenUsage read(Function<String, Long> count, boolean incomplete) {
        return new TokenUsage(count.apply(INPUT), count.apply(CACHE_READ), count.apply(CACHE_WRITE),
                count.apply(CACHE_WRITE_5M), count.apply(CACHE_WRITE_1H), count.apply(OUTPUT), count.apply(PROMPT),
                count.apply(COMPLETION), incomplete);
    }

    /** True when the runtime reported input or output tokens, which only coding runtimes do. */
    public boolean hasCodingTokens() {
        return inputTokens != null || outputTokens != null;
    }

    /** The sum of two reports; a count stays null only when neither reported it. */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(sum(inputTokens, other.inputTokens), sum(cacheReadTokens, other.cacheReadTokens),
                sum(cacheWriteTokens, other.cacheWriteTokens), sum(cacheWrite5mTokens, other.cacheWrite5mTokens),
                sum(cacheWrite1hTokens, other.cacheWrite1hTokens), sum(outputTokens, other.outputTokens),
                sum(promptTokens, other.promptTokens), sum(completionTokens, other.completionTokens),
                usageIncomplete || other.usageIncomplete);
    }

    public TokenUsage incomplete() {
        return new TokenUsage(inputTokens, cacheReadTokens, cacheWriteTokens, cacheWrite5mTokens, cacheWrite1hTokens,
                outputTokens, promptTokens, completionTokens, true);
    }

    /**
     * The runtime metrics plus Factory's prompt total (input and cache tokens) and completion total (output
     * tokens), which the engine metrics read; totals the runtime reported itself stay.
     */
    public static Map<String, Object> withFactoryTotals(Map<String, Object> runtimeMetrics) {
        var usage = from(runtimeMetrics);
        var metrics = new LinkedHashMap<>(runtimeMetrics);
        putTotal(metrics, PROMPT, sum(sum(atLeastZero(usage.inputTokens), atLeastZero(usage.cacheReadTokens)),
                atLeastZero(usage.cacheWriteTokens)));
        putTotal(metrics, COMPLETION, atLeastZero(usage.outputTokens));
        return metrics;
    }

    /** True when any count was reported. */
    public boolean hasCounts() {
        return hasCodingTokens() || cacheReadTokens != null || cacheWriteTokens != null || promptTokens != null
                || completionTokens != null;
    }

    /** The reported counts under their metric keys; {@code usageIncomplete} appears only when it is true. */
    public Map<String, Object> toMetrics() {
        var metrics = new LinkedHashMap<String, Object>();
        putReported(metrics, INPUT, inputTokens);
        putReported(metrics, CACHE_READ, cacheReadTokens);
        putReported(metrics, CACHE_WRITE, cacheWriteTokens);
        putReported(metrics, CACHE_WRITE_5M, cacheWrite5mTokens);
        putReported(metrics, CACHE_WRITE_1H, cacheWrite1hTokens);
        putReported(metrics, OUTPUT, outputTokens);
        putReported(metrics, PROMPT, promptTokens);
        putReported(metrics, COMPLETION, completionTokens);
        if (usageIncomplete) {
            metrics.put(INCOMPLETE, true);
        }
        return metrics;
    }

    private static void putTotal(Map<String, Object> metrics, String key, Long total) {
        if (total != null) {
            metrics.putIfAbsent(key, total);
        }
    }

    private static void putReported(Map<String, Object> metrics, String key, Long count) {
        if (count != null) {
            metrics.put(key, count);
        }
    }

    private static Long sum(Long left, Long right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : left + right;
    }

    private static Long atLeastZero(Long count) {
        return count == null ? null : Math.max(0, count);
    }
}
