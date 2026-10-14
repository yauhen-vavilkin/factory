package org.folio.factory.app.web;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Step metrics for technical rows and totals from flows without attempt-level accounting. */
final class StepUsageSummary {
    private final Map<String, ReportedUsage> usageByStep;

    private StepUsageSummary(Map<String, ReportedUsage> usageByStep) {
        this.usageByStep = usageByStep;
    }

    boolean incomplete() {
        return usageByStep.values().stream().anyMatch(ReportedUsage::usageIncomplete);
    }

    DeveloperCostEstimator.Estimate estimate(DeveloperCostEstimator estimator) {
        boolean legacyOnly = usageByStep.values().stream()
                .anyMatch(usage -> usage.factoryTokensPresent() && !usage.codingTokensPresent());
        if (legacyOnly || incomplete()) return null;
        return estimator.estimate(usageByStep.values().stream()
                .filter(ReportedUsage::codingTokensPresent)
                .map(usage -> new DeveloperCostEstimator.Usage(
                        usage.model(), usage.input(), usage.cacheRead(), usage.cacheWrite(), usage.output()))
                .toList());
    }

    String stepTokens(String stepId) {
        var usage = usageByStep.get(stepId);
        if (usage == null || (!usage.codingTokensPresent() && !usage.factoryTokensPresent())) {
            return null;
        }
        long input = usage.inputTotal();
        long output = usage.outputTotal();
        return UiFormat.count(input + output) + " tokens (" + UiFormat.count(input)
                + " in / " + UiFormat.count(output) + " out)";
    }

    /**
     * Usage per step from completed metrics, with the latest streamed coding-runtime snapshot as a
     * Developer Flow failure fallback. Completed metrics always win, so a successful run never
     * counts its streamed snapshots again.
     */
    static StepUsageSummary from(
            List<AuditEvent> events, boolean includeCodingProgress, JsonMapper jsonMapper) {
        Map<String, ReportedUsage> completed = new LinkedHashMap<>();
        Map<String, ReportedUsage> progress = new LinkedHashMap<>();
        Map<String, String> runtimeModels = new LinkedHashMap<>();
        for (AuditEvent event : events) {
            if (event.getStepId() == null) continue;
            JsonNode detail =
                    jsonMapper.readTree(event.getDetail() == null ? "{}" : event.getDetail());
            boolean completedEvent = event.getEventType() == AuditEventType.STEP_COMPLETED;
            String activity = detail.path("activity").asString("");
            if (includeCodingProgress
                    && event.getEventType() == AuditEventType.RUNTIME_PROGRESS
                    && ("pi_starting".equals(activity) || "coding_starting".equals(activity))) {
                runtimeModels.put(event.getStepId(), text(detail, "model"));
                continue;
            }
            boolean codingProgress =
                    includeCodingProgress
                            && event.getEventType() == AuditEventType.RUNTIME_PROGRESS
                            && ("pi_usage".equals(activity) || "coding_usage".equals(activity));
            if (!completedEvent && !codingProgress) continue;
            Long prompt = token(detail, "promptTokens");
            Long completion = token(detail, "completionTokens");
            Long input = token(detail, "inputTokens");
            Long cacheRead = token(detail, "cacheReadTokens");
            Long cacheWrite = token(detail, "cacheWriteTokens");
            Long output = token(detail, "outputTokens");
            if (prompt == null
                    && completion == null
                    && input == null
                    && cacheRead == null
                    && cacheWrite == null
                    && output == null) continue;
            String model = text(detail, "model");
            if (model == null && codingProgress) model = runtimeModels.get(event.getStepId());
            var usage =
                    new ReportedUsage(
                            prompt,
                            completion,
                            input,
                            cacheRead,
                            cacheWrite,
                            output,
                            model,
                            detail.path("usageIncomplete").asBoolean(false));
            (codingProgress ? progress : completed).put(event.getStepId(), usage);
        }
        progress.forEach(completed::putIfAbsent);
        return new StepUsageSummary(completed);
    }

    private static Long token(JsonNode detail, String key) {
        return detail.path(key).isNumber() ? Math.max(0, detail.path(key).asLong(0)) : null;
    }

    private static String text(JsonNode detail, String key) {
        String value = detail.path(key).asString("").strip();
        return value.isEmpty() ? null : value;
    }

    Map<String, Object> tokenTotals(boolean developerFlow) {
        if (developerFlow
                && usageByStep.values().stream().anyMatch(ReportedUsage::codingTokensPresent))
            return codingTokenTotals();
        long prompt = usageByStep.values().stream().mapToLong(usage -> value(usage.prompt())).sum();
        long completion =
                usageByStep.values().stream().mapToLong(usage -> value(usage.completion())).sum();
        Map<String, Object> totals = new LinkedHashMap<>();
        // A run with no LLM step renders nothing rather than a row of zeroes.
        totals.put("present", prompt + completion > 0);
        totals.put("prompt", UiFormat.count(prompt));
        totals.put("completion", UiFormat.count(completion));
        totals.put("total", UiFormat.count(prompt + completion));
        return totals;
    }

    private Map<String, Object> codingTokenTotals() {
        var breakdown = new LinkedHashMap<String, Long>();
        long total = 0;
        for (var usage : usageByStep.values()) {
            mergeReported(breakdown, "input", usage.input());
            mergeReported(breakdown, "cacheRead", usage.cacheRead());
            mergeReported(breakdown, "cacheWrite", usage.cacheWrite());
            mergeReported(breakdown, "output", usage.output());
            total += usage.inputTotal() + usage.outputTotal();
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("present", total > 0);
        totals.put("coding", true);
        totals.put("total", compactTokenCount(total));
        for (String category : List.of("input", "cacheRead", "cacheWrite", "output")) {
            if (breakdown.containsKey(category)) {
                totals.put(category, compactTokenCount(breakdown.get(category)));
            }
        }
        return totals;
    }

    private static void mergeReported(Map<String, Long> totals, String category, Long count) {
        if (count != null) totals.merge(category, count, Long::sum);
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static String compactTokenCount(long value) {
        long divisor = value >= 1_000_000 ? 1_000_000 : value >= 1_000 ? 1_000 : 1;
        if (divisor == 1) return UiFormat.count(value);
        String suffix = divisor == 1_000_000 ? "m" : "k";
        return BigDecimal.valueOf(value)
                        .divide(BigDecimal.valueOf(divisor), 1, RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString()
                + suffix;
    }

    private record ReportedUsage(
            Long prompt,
            Long completion,
            Long input,
            Long cacheRead,
            Long cacheWrite,
            Long output,
            String model,
            boolean usageIncomplete) {
        boolean factoryTokensPresent() {
            return prompt != null || completion != null;
        }

        boolean codingTokensPresent() {
            return input != null || cacheRead != null || cacheWrite != null || output != null;
        }

        long inputTotal() {
            return prompt != null ? prompt : value(input) + value(cacheRead) + value(cacheWrite);
        }

        long outputTotal() {
            return completion != null ? completion : value(output);
        }
    }
}
