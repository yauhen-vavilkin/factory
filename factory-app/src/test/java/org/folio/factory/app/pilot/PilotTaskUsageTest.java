package org.folio.factory.app.pilot;

import org.folio.factory.app.pilot.presentation.PilotTaskUsage;
import org.folio.factory.app.web.AttemptCharge;
import org.folio.factory.app.web.AttemptUsageSummary;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Numerical preservation of logical-task tokenomics: feedback rounds, retries,
 * streamed cumulative snapshots, mixed currencies, cache-write TTL pricing,
 * unknown rates and partial usage each contribute exactly once, and any
 * incomplete contribution keeps the task total incomplete.
 */
class PilotTaskUsageTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Object> USD_RATE = Map.of(
            "currency", "USD", "inputPerMillion", 2, "cacheReadPerMillion", 0.1, "outputPerMillion", 10);

    private static AuditEvent usageEvent(UUID executionId, AuditEventType type, String attemptId, int round,
                                         Map<String, Object> rate, Map<String, Object> tokens) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("attemptId", attemptId);
        detail.put("round", round);
        detail.put("model", "claude-sonnet-5-5");
        detail.put("rate", rate);
        if (type == AuditEventType.RUNTIME_PROGRESS) {
            detail.put("activity", "coding_usage");
        }
        detail.putAll(tokens);
        var event = new AuditEvent(executionId, type, "implement", "test", JSON.writeValueAsString(detail));
        ReflectionTestUtils.setField(event, "occurredAt", Instant.now());
        return event;
    }

    private static AttemptUsageSummary.Totals totals(List<AuditEvent> events) {
        return AttemptUsageSummary.totals(events, false);
    }

    @Test
    void taskTotalsSumEachAttemptContributionExactlyOnce() {
        var firstExecution = UUID.randomUUID();
        var retryExecution = UUID.randomUUID();
        var europeanExecution = UUID.randomUUID();
        var europeanRate = Map.<String, Object>of(
                "currency", "EUR", "inputPerMillion", 2, "cacheReadPerMillion", 0.1, "outputPerMillion", 10);
        var initial = usageEvent(firstExecution, AuditEventType.STEP_COMPLETED, "a1", -1, USD_RATE,
                Map.of("inputTokens", 100_000, "outputTokens", 20_000,
                        "cacheReadTokens", 0, "cacheWriteTokens", 0));
        var feedbackRound = usageEvent(firstExecution, AuditEventType.STEP_COMPLETED, "a1-r1", 1, USD_RATE,
                Map.of("inputTokens", 60_000, "outputTokens", 12_000,
                        "cacheReadTokens", 0, "cacheWriteTokens", 0));
        var streamedCumulative = usageEvent(retryExecution, AuditEventType.RUNTIME_PROGRESS, "a2", -1, USD_RATE,
                Map.of("inputTokens", 100_000, "outputTokens", 10_000,
                        "cacheReadTokens", 0, "cacheWriteTokens", 0));
        var retryTerminal = usageEvent(retryExecution, AuditEventType.STEP_COMPLETED, "a2", -1, USD_RATE,
                Map.of("inputTokens", 150_000, "outputTokens", 30_000,
                        "cacheReadTokens", 0, "cacheWriteTokens", 0, "usageIncomplete", true));
        var european = usageEvent(europeanExecution, AuditEventType.RUNTIME_PROGRESS, "a3", -1, europeanRate,
                Map.of("inputTokens", 250_000, "outputTokens", 0,
                        "cacheReadTokens", 0, "cacheWriteTokens", 0));

        var taskUsage = PilotTaskUsage.aggregate(
                List.of(totals(List.of(initial, feedbackRound)),
                        totals(List.of(streamedCumulative, retryTerminal)),
                        totals(List.of(european))),
                false);

        assertThat(taskUsage.estimatesByCurrency().get("USD")).isEqualByComparingTo("1.24");
        assertThat(taskUsage.estimatesByCurrency().get("EUR")).isEqualByComparingTo("0.50");
        assertThat(taskUsage.complete()).isFalse();
        assertThat(taskUsage.tokens()).isEqualTo(622_000L);
        assertThat(taskUsage.tokensByCategory())
                .containsEntry(AttemptCharge.Category.INPUT, 560_000L)
                .containsEntry(AttemptCharge.Category.OUTPUT, 62_000L)
                .containsEntry(AttemptCharge.Category.CACHE_READ, 0L)
                .containsEntry(AttemptCharge.Category.CACHE_WRITE, 0L);
        assertThat(taskUsage.models()).containsExactly("claude-sonnet-5-5");
        assertThat(totals(List.of(initial)).costByCurrency().get("USD")).isEqualByComparingTo("0.40");
        assertThat(totals(List.of(feedbackRound)).costByCurrency().get("USD")).isEqualByComparingTo("0.24");
        assertThat(totals(List.of(streamedCumulative, retryTerminal)).costByCurrency().get("USD"))
                .isEqualByComparingTo("0.60");
        assertThat(totals(List.of(streamedCumulative, retryTerminal)).tokens()).isEqualTo(180_000L);
    }

    @Test
    void cacheWriteTtlPricingSurvivesTaskAggregation() {
        var ttlRate = Map.<String, Object>of(
                "currency", "USD", "inputPerMillion", 2, "cacheReadPerMillion", 0.1,
                "cacheWrite5mPerMillion", 2.5, "cacheWrite1hPerMillion", 4, "outputPerMillion", 10);
        var ttlSplit = usageEvent(UUID.randomUUID(), AuditEventType.STEP_COMPLETED, "sonnet", -1, ttlRate,
                Map.of("inputTokens", 1_000_000, "cacheReadTokens", 2_000_000,
                        "cacheWriteTokens", 3_000_000, "cacheWrite5mTokens", 1_000_000,
                        "cacheWrite1hTokens", 2_000_000, "outputTokens", 100_000));
        var plainAttempt = usageEvent(UUID.randomUUID(), AuditEventType.STEP_COMPLETED, "plain", -1, USD_RATE,
                Map.of("inputTokens", 1_000_000, "cacheReadTokens", 0,
                        "cacheWriteTokens", 0, "outputTokens", 0));

        var taskUsage = PilotTaskUsage.aggregate(
                List.of(totals(List.of(ttlSplit)), totals(List.of(plainAttempt))), false);

        assertThat(taskUsage.estimatesByCurrency().get("USD")).isEqualByComparingTo("15.70");
        assertThat(taskUsage.complete()).isTrue();
        assertThat(taskUsage.tokensByCategory())
                .containsEntry(AttemptCharge.Category.CACHE_WRITE, 3_000_000L)
                .containsEntry(AttemptCharge.Category.INPUT, 2_000_000L);
    }

    @Test
    void unknownRateAndUnattributedLegacyStepUsageKeepTheTaskIncomplete() {
        var missingOutputRate = Map.<String, Object>of(
                "currency", "USD", "inputPerMillion", 2, "cacheReadPerMillion", 0.1);
        var unpricedOutput = usageEvent(UUID.randomUUID(), AuditEventType.STEP_COMPLETED, "h1", -1,
                missingOutputRate,
                Map.of("inputTokens", 100_000, "outputTokens", 20_000,
                        "cacheReadTokens", 0, "cacheWriteTokens", 0));
        var legacyStep = new AuditEvent(UUID.randomUUID(), AuditEventType.STEP_COMPLETED, "implement", "test",
                "{\"inputTokens\":5000,\"outputTokens\":1000,\"model\":\"claude-sonnet-5-5\"}");

        var legacyTotals = totals(List.of(legacyStep));
        assertThat(legacyTotals.present()).isTrue();
        assertThat(legacyTotals.partial()).isTrue();
        assertThat(legacyTotals.tokens()).isZero();
        assertThat(legacyTotals.costByCurrency()).isEmpty();

        var taskUsage = PilotTaskUsage.aggregate(
                List.of(totals(List.of(unpricedOutput)), legacyTotals), false);

        assertThat(taskUsage.estimatesByCurrency().get("USD")).isEqualByComparingTo("0.20");
        assertThat(taskUsage.complete()).isFalse();
        assertThat(taskUsage.tokens()).isEqualTo(120_000L);
    }

    @Test
    void unstartedTaskHasEmptyEstimatesAndPaidStartWithoutUsageStaysIncomplete() {
        var unstartedUsage = PilotTaskUsage.aggregate(List.of(), false);

        assertThat(unstartedUsage.estimatesByCurrency()).isEmpty();
        assertThat(unstartedUsage.tokens()).isZero();
        assertThat(unstartedUsage.complete()).isTrue();
        assertThat(unstartedUsage.provisional()).isFalse();

        var paidStartWithoutUsage = PilotTaskUsage.aggregate(
                List.of(AttemptUsageSummary.totals(List.of(), false)), true);

        assertThat(paidStartWithoutUsage.estimatesByCurrency()).isEmpty();
        assertThat(paidStartWithoutUsage.complete()).isFalse();
    }
}
