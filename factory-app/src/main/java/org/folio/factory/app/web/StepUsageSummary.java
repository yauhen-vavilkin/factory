package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.devfactory.runtime.TokenUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Step metrics for technical rows and totals from flows without attempt-level accounting. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class StepUsageSummary {
    private final Map<String, ReportedUsage> usageByStep;

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
        for (var event : events) {
            if (event.getStepId() == null) continue;
            var stepId = event.getStepId();
            var detail = jsonMapper.readTree(event.getDetail() == null ? "{}" : event.getDetail());
            switch (usageEvent(event, detail, includeCodingProgress)) {
                case RUNTIME_STARTING -> runtimeModels.put(stepId, text(detail, "model"));
                case CODING_USAGE -> reportedUsage(detail, runtimeModels.get(stepId))
                        .ifPresent(usage -> progress.put(stepId, usage));
                case STEP_COMPLETED -> reportedUsage(detail, null)
                        .ifPresent(usage -> completed.put(stepId, usage));
                case OTHER -> { }
            }
        }
        progress.forEach(completed::putIfAbsent);
        return new StepUsageSummary(completed);
    }

    private static UsageEvent usageEvent(AuditEvent event, JsonNode detail, boolean includeCodingProgress) {
        if (event.getEventType() == AuditEventType.STEP_COMPLETED) {
            return UsageEvent.STEP_COMPLETED;
        }
        if (!includeCodingProgress || event.getEventType() != AuditEventType.RUNTIME_PROGRESS) {
            return UsageEvent.OTHER;
        }
        return switch (detail.path("activity").asString("")) {
            case "pi_starting", "coding_starting" -> UsageEvent.RUNTIME_STARTING;
            case "pi_usage", "coding_usage" -> UsageEvent.CODING_USAGE;
            default -> UsageEvent.OTHER;
        };
    }

    /** Empty when the event reports no token counts at all. */
    private static Optional<ReportedUsage> reportedUsage(JsonNode detail, String runtimeModel) {
        var usage = TokenUsage.from(detail);
        if (!usage.hasCounts()) {
            return Optional.empty();
        }
        var model = text(detail, "model");
        return Optional.of(new ReportedUsage(atLeastZero(usage.promptTokens()), atLeastZero(usage.completionTokens()),
                atLeastZero(usage.inputTokens()), atLeastZero(usage.cacheReadTokens()),
                atLeastZero(usage.cacheWriteTokens()), atLeastZero(usage.outputTokens()),
                model == null ? runtimeModel : model, usage.usageIncomplete()));
    }

    private static Long atLeastZero(Long count) {
        return count == null ? null : Math.max(0, count);
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

    private enum UsageEvent { RUNTIME_STARTING, CODING_USAGE, STEP_COMPLETED, OTHER }

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
