package org.folio.factory.app.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeveloperCostEstimatorTest {
    private static final String SONNET_MODEL = "claude-sonnet-5-5";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DeveloperCostEstimator estimator = new DeveloperCostEstimator(new DeveloperPricingProperties(Map.of(
            "haiku", new DeveloperPricingProperties.Rate("claude-haiku-5-5", "USD",
                    LocalDate.parse("2026-09-19"), URI.create("https://www.anthropic.com/pricing"),
                    new BigDecimal("0.15"), new BigDecimal("0.03"), null, new BigDecimal("0.50")))));

    @Test
    void estimatesFromModelAndAvailableCategories() {
        var estimate = estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "claude-haiku-5-5", 71_200L, 2_500_000L, 0L, 35_100L)));

        assertThat(estimate).isNotNull();
        assertThat(estimate.amount()).isEqualTo("0.10323");
        assertThat(estimate.currency()).isEqualTo("USD");
    }

    @Test
    void refusesUnknownModelsAndPositiveCategoriesWithoutRates() {
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "other", 1L, 0L, 0L, 0L)))).isNull();
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "claude-haiku-5-5", 1L, 0L, 1L, 0L)))).isNull();
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "claude-haiku-5-5", null, 0L, 0L, 1L)))).isNull();
    }

    @Test
    void rejectsAmbiguousOfficialRatesForTheSameModel() {
        var configured = new DeveloperPricingProperties.Rate("claude-haiku-5-5", "USD",
                LocalDate.parse("2026-09-19"), URI.create("https://www.anthropic.com/pricing"),
                new BigDecimal("0.15"), new BigDecimal("0.03"), null, new BigDecimal("0.50"));

        assertThatThrownBy(() -> new DeveloperPricingProperties(Map.of("first", configured, "second", configured)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate pricing model claude-haiku-5-5");
    }

    @Test
    void estimatesGeminiWithCacheHitsAndSuppressesMissingUsage() throws Exception {
        DeveloperPricingProperties rates = loadRates();
        var configured = rates.rates().get("google-gemini-3-8-flash");
        assertThat(configured.model()).isEqualTo("gemini-3.8-flash");
        assertThat(configured.inputPerMillion()).isEqualByComparingTo("0.75");
        assertThat(configured.cacheReadPerMillion()).isEqualByComparingTo("0.075");
        assertThat(configured.outputPerMillion()).isEqualByComparingTo("3.75");
        assertThat(configured.cacheWritePerMillion()).isNull();

        var gemini = new DeveloperCostEstimator(rates);
        var estimate = gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                "gemini-3.8-flash", 1_000_000L, 2_000_000L, 0L, 100_000L)));
        assertThat(estimate).isNotNull();
        assertThat(estimate.amount()).isEqualTo("1.275");
        assertThat(estimate.asOf()).isEqualTo(LocalDate.parse("2026-09-21"));
        assertThat(gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                "gemini-3.8-flash", 0L, 0L, 0L, 0L)))).isNull();
        assertThat(gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                "gemini-3.8-flash", null, 0L, 0L, 1L)))).isNull();
        assertThat(gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                "gemini-3.8-flash", 1L, 0L, 1L, 0L)))).isNull();
    }

    @Test
    void sonnetYamlUsesLiteralModelAndSeparateCacheTtlRates() throws Exception {
        var configured = loadRates().rateFor(SONNET_MODEL).orElseThrow();

        assertThat(configured.asOf()).isEqualTo(LocalDate.parse("2026-10-22"));
        assertThat(configured.source()).isEqualTo(URI.create(
                "https://platform.claude.com/docs/en/about-claude/pricing"));
        assertThat(configured.inputPerMillion()).isEqualByComparingTo("2.00");
        assertThat(configured.outputPerMillion()).isEqualByComparingTo("10.00");
        assertThat(configured.cacheReadPerMillion()).isEqualByComparingTo("0.10");
        assertThat(configured.cacheWrite5mPerMillion()).isEqualByComparingTo("2.50");
        assertThat(configured.cacheWrite1hPerMillion()).isEqualByComparingTo("4.00");
        assertThat(configured.cacheWritePerMillion()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "3000000, 0, 10.70",
        "1000000, 2000000, 13.70",
        "0, 3000000, 15.20"
    })
    void pricesSonnetUsingObservedCacheTtl(long fiveMinuteTokens, long oneHourTokens, String expectedCost)
            throws Exception {
        ObjectNode usage = sonnetUsage(fiveMinuteTokens, oneHourTokens);

        AttemptCharge charge = AttemptCharge.calculate(usage, sonnetSnapshot(), true);

        assertThat(charge.known()).isTrue();
        assertThat(charge.subtotalPresent()).isTrue();
        assertThat(charge.cost()).isEqualByComparingTo(expectedCost);
        assertThat(charge.tokens()).isEqualTo(6_100_000L);
        assertThat(charge.breakdown()).containsEntry(AttemptCharge.Category.CACHE_WRITE, 3_000_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cacheWrite5mTokens", "cacheWrite1hTokens"})
    void missingCacheTtlLeavesOnlyTheObservedNonWriteSubtotal(String missingCounter) throws Exception {
        ObjectNode usage = sonnetUsage(3_000_000L, 0L);
        usage.remove(missingCounter);

        AttemptCharge charge = AttemptCharge.calculate(usage, sonnetSnapshot(), true);

        assertThat(charge.known()).isFalse();
        assertThat(charge.subtotalPresent()).isTrue();
        assertThat(charge.cost()).isEqualByComparingTo("3.20");
    }

    @ParameterizedTest
    @CsvSource({"3000000, 1", "-1, 3000001", "3000001, 0", "0, -1"})
    void inconsistentCacheTtlCannotProduceACompleteEstimate(long fiveMinuteTokens, long oneHourTokens)
            throws Exception {
        ObjectNode usage = sonnetUsage(fiveMinuteTokens, oneHourTokens);

        AttemptCharge charge = AttemptCharge.calculate(usage, sonnetSnapshot(), true);

        assertThat(charge.known()).isFalse();
        assertThat(charge.cost()).isEqualByComparingTo("3.20");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cacheWrite5mPerMillion", "cacheWrite1hPerMillion"})
    void missingCacheTtlRateDoesNotPriceOnlyPartOfTheCacheWrites(String missingRate) throws Exception {
        ObjectNode snapshot = (ObjectNode) sonnetSnapshot();
        snapshot.remove(missingRate);

        AttemptCharge charge = AttemptCharge.calculate(sonnetUsage(1_000_000L, 2_000_000L), snapshot, true);

        assertThat(charge.known()).isFalse();
        assertThat(charge.cost()).isEqualByComparingTo("3.20");
    }

    @Test
    void completeCacheTtlDoesNotOverrideIncompleteUsage() throws Exception {
        ObjectNode usage = sonnetUsage(3_000_000L, 0L);
        JsonNode snapshot = sonnetSnapshot();

        assertThat(AttemptCharge.calculate(usage, snapshot, false).known()).isFalse();
        usage.put("usageIncomplete", true);
        assertThat(AttemptCharge.calculate(usage, snapshot, true).known()).isFalse();
    }

    @Test
    void zeroCacheWritesDoNotRequireCacheTtlCounters() throws Exception {
        ObjectNode usage = sonnetUsage(0L, 0L).put("cacheWriteTokens", 0L);
        usage.remove("cacheWrite5mTokens");
        usage.remove("cacheWrite1hTokens");

        AttemptCharge charge = AttemptCharge.calculate(usage, sonnetSnapshot(), true);

        assertThat(charge.known()).isTrue();
        assertThat(charge.cost()).isEqualByComparingTo("3.20");
    }

    @Test
    void historicalAggregateRateSnapshotStillPricesUsageWithoutCacheTtl() throws Exception {
        ObjectNode snapshot = JSON.valueToTree(new AttemptRateSnapshot(loadRates()).snapshot(SONNET_MODEL));
        snapshot.remove("cacheWrite5mPerMillion");
        snapshot.remove("cacheWrite1hPerMillion");
        snapshot.put("cacheWritePerMillion", new BigDecimal("2.50"));
        ObjectNode usage = sonnetUsage(3_000_000L, 0L);
        usage.remove("cacheWrite5mTokens");
        usage.remove("cacheWrite1hTokens");

        AttemptCharge charge = AttemptCharge.calculate(usage, snapshot, true);

        assertThat(charge.known()).isTrue();
        assertThat(charge.cost()).isEqualByComparingTo("10.70");
    }

    @Test
    void aggregateEstimatorCannotAssumeACacheTtlForSonnet() throws Exception {
        var sonnetEstimator = new DeveloperCostEstimator(loadRates());
        var usage = new DeveloperCostEstimator.Usage(
                SONNET_MODEL, 1_000_000L, 2_000_000L, 3_000_000L, 100_000L);

        assertThat(sonnetEstimator.estimate(List.of(usage))).isNull();
    }

    @Test
    void estimatesLunaMaxReasoningOutputAndCacheWritesAtOfficialRates() throws Exception {
        DeveloperPricingProperties rates = loadRates();
        var configured = rates.rates().get("openai-gpt-5-6-luna");
        assertThat(configured.model()).isEqualTo("gpt-5.6-luna-2026-07-09");
        assertThat(configured.inputPerMillion()).isEqualByComparingTo("0.20");
        assertThat(configured.cacheReadPerMillion()).isEqualByComparingTo("0.02");
        assertThat(configured.cacheWritePerMillion()).isEqualByComparingTo("0.25");
        assertThat(configured.outputPerMillion()).isEqualByComparingTo("1.20");
        assertThat(configured.source()).isEqualTo(URI.create(
                "https://developers.openai.com/api/docs/models/gpt-5.6-luna"));

        var luna = new DeveloperCostEstimator(rates);
        var estimate = luna.estimate(List.of(new DeveloperCostEstimator.Usage(
                "gpt-5.6-luna-2026-07-09", 1_000_000L, 2_000_000L, 3_000_000L, 100_000L)));
        assertThat(estimate).isNotNull();
        assertThat(estimate.amount()).isEqualTo("1.11");
        assertThat(estimate.asOf()).isEqualTo(LocalDate.parse("2026-09-21"));
    }

    private static DeveloperPricingProperties loadRates() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        Path rateCard = directory.resolve("config/developer-pricing.yaml");
        if (!Files.exists(rateCard)) {
            rateCard = directory.getParent().resolve("config/developer-pricing.yaml");
        }
        var source = new YamlPropertySourceLoader().load("pricing", new FileSystemResource(rateCard)).getFirst();
        return new Binder(ConfigurationPropertySources.from(source))
                .bind("factory.developer-pricing", DeveloperPricingProperties.class)
                .get();
    }

    private static JsonNode sonnetSnapshot() throws Exception {
        return JSON.valueToTree(new AttemptRateSnapshot(loadRates()).snapshot(SONNET_MODEL));
    }

    private static ObjectNode sonnetUsage(long fiveMinuteTokens, long oneHourTokens) {
        return JSON.createObjectNode()
                .put("inputTokens", 1_000_000L)
                .put("cacheReadTokens", 2_000_000L)
                .put("cacheWriteTokens", 3_000_000L)
                .put("cacheWrite5mTokens", fiveMinuteTokens)
                .put("cacheWrite1hTokens", oneHourTokens)
                .put("outputTokens", 100_000L);
    }
}
