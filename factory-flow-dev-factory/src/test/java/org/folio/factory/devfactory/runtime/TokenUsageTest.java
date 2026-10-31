package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenUsageTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Token metrics as ClaudeCodeCodingRuntimeTest and PiCodingRuntimeTest assert them today. */
    private static final List<Map<String, Object>> RUNTIME_METRICS = List.of(
            Map.of("inputTokens", 40L, "outputTokens", 5L, "cacheReadTokens", 3L, "cacheWriteTokens", 2L),
            Map.of("inputTokens", 40L, "outputTokens", 5L, "cacheWriteTokens", 2L, "cacheWrite5mTokens", 2L,
                    "cacheWrite1hTokens", 0L, "usageIncomplete", true),
            Map.of("inputTokens", 0L, "outputTokens", 5L),
            Map.of("inputTokens", 35L, "cacheReadTokens", 4L, "outputTokens", 7L),
            Map.of("inputTokens", 1000L, "usageIncomplete", true));

    @Test
    void writesBackExactlyTheMetricsTheRuntimesReport() {
        for (var metrics : RUNTIME_METRICS) {
            assertThat(TokenUsage.from(metrics).toMetrics()).isEqualTo(metrics);
            JsonNode node = JSON.valueToTree(metrics);
            assertThat(TokenUsage.from(node).toMetrics()).isEqualTo(metrics);
        }
    }

    @Test
    void factoryTotalsAddInputAndCacheTokensUnlessTheRuntimeReportedThem() {
        var derived = TokenUsage.withFactoryTotals(RUNTIME_METRICS.get(0));
        assertThat(derived).containsAllEntriesOf(RUNTIME_METRICS.get(0))
                .containsEntry("promptTokens", 45L).containsEntry("completionTokens", 5L);

        var reported = new LinkedHashMap<String, Object>(RUNTIME_METRICS.get(0));
        reported.put("promptTokens", 7L);
        assertThat(TokenUsage.withFactoryTotals(reported))
                .containsEntry("promptTokens", 7L).containsEntry("completionTokens", 5L);

        assertThat(TokenUsage.withFactoryTotals(Map.of("model", "m"))).isEqualTo(Map.of("model", "m"));
    }

    @Test
    void sumsReportsAndKeepsUnreportedCountsAbsent() {
        var first = TokenUsage.from(Map.of("inputTokens", 10L, "outputTokens", 1L));
        var second = TokenUsage.from(Map.of("inputTokens", 5L, "cacheReadTokens", 2L));

        assertThat(first.plus(second).toMetrics())
                .isEqualTo(Map.of("inputTokens", 15L, "outputTokens", 1L, "cacheReadTokens", 2L));
        assertThat(TokenUsage.NONE.plus(TokenUsage.NONE).toMetrics()).isEmpty();
        assertThat(first.incomplete().toMetrics()).containsEntry("usageIncomplete", true);
    }

    @Test
    void ignoresValuesThatAreNotWholeNumbers() {
        var node = JSON.readTree("{\"inputTokens\":\"many\",\"outputTokens\":null,\"cacheReadTokens\":2.5}");
        var usage = TokenUsage.from(node);

        assertThat(usage.hasCodingTokens()).isFalse();
        assertThat(usage.toMetrics()).isEmpty();
        assertThat(TokenUsage.namesCodingTokens(node)).isTrue();
        assertThat(TokenUsage.namesCodingTokens(JSON.readTree("{\"promptTokens\":3}"))).isFalse();
    }

    @Test
    void keepsTheKeyOrderEachRuntimeHasAlwaysWritten() {
        var usage = new TokenUsage(40L, 3L, 2L, 2L, 0L, 5L, null, null, true);

        assertThat(List.copyOf(usage.toClaudeMetrics().keySet())).containsExactly("inputTokens", "outputTokens",
                "cacheReadTokens", "cacheWriteTokens", "cacheWrite5mTokens", "cacheWrite1hTokens", "usageIncomplete");
        assertThat(List.copyOf(usage.toMetrics().keySet())).containsExactly("inputTokens", "cacheReadTokens",
                "cacheWriteTokens", "cacheWrite5mTokens", "cacheWrite1hTokens", "outputTokens", "usageIncomplete");
    }
}
