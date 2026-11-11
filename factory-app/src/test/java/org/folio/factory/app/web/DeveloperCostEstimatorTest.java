package org.folio.factory.app.web;

import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.runtime.ModelEntry;
import org.folio.factory.devfactory.runtime.ModelPrice;
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

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperCostEstimatorTest {
    private static final String SONNET = "codemie-sonnet-5.5";
    private static final String SONNET_MODEL = "claude-sonnet-5-5";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DeveloperCostEstimator estimator = estimatorFor("claude-haiku-5-5",
            "https://www.anthropic.com/pricing", "0.15", "0.03", "0.50");

    @Test
    void estimatesFromModelAndAvailableCategories() {
        var estimate = estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "priced", "claude-haiku-5-5", 71_200L, 2_500_000L, 0L, 35_100L)));

        assertThat(estimate).isNotNull();
        assertThat(estimate.amount()).isEqualTo("0.10323");
        assertThat(estimate.currency()).isEqualTo("USD");
        // Pi runs and runs from before the models file record no entry id: the model string finds the entry.
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                null, "claude-haiku-5-5", 71_200L, 2_500_000L, 0L, 35_100L)))).isEqualTo(estimate);
    }

    @Test
    void refusesUnknownModelsAndPositiveCategoriesWithoutRates() {
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                null, "other", 1L, 0L, 0L, 0L)))).isNull();
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "priced", "claude-haiku-5-5", 1L, 0L, 1L, 0L)))).isNull();
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "priced", "claude-haiku-5-5", null, 0L, 0L, 1L)))).isNull();
    }

    @Test
    void aModelStringThatTwoEntriesShareIsPricedOnlyByTheEntryId() {
        var twoSonnets = new DeveloperCostEstimator(catalogOf(sonnetEntry(SONNET), sonnetEntry("anthropic-sonnet-5.5")));

        assertThat(twoSonnets.estimate(List.of(new DeveloperCostEstimator.Usage(
                null, SONNET_MODEL, 1_000_000L, 0L, 0L, 100_000L)))).isNull();
        assertThat(twoSonnets.estimate(List.of(new DeveloperCostEstimator.Usage(
                SONNET, SONNET_MODEL, 1_000_000L, 0L, 0L, 100_000L))).amount()).isEqualTo("3");
    }

    @Test
    void estimatesCacheHitsWithoutACacheWriteRateAndSuppressesMissingUsage() {
        var price = new ModelPrice("USD", LocalDate.parse("2026-09-21"), URI.create("https://example.test/gemini"),
                new BigDecimal("0.75"), new BigDecimal("0.075"), null, null, null, new BigDecimal("3.75"));
        var gemini = new DeveloperCostEstimator(catalogOf(entry("codemie-gemini-3.8-flash", "gemini-3.8-flash",
                price)));

        var estimate = gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                null, "gemini-3.8-flash", 1_000_000L, 2_000_000L, 0L, 100_000L)));
        assertThat(estimate).isNotNull();
        assertThat(estimate.amount()).isEqualTo("1.275");
        assertThat(estimate.asOf()).isEqualTo(LocalDate.parse("2026-09-21"));
        assertThat(gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                null, "gemini-3.8-flash", 0L, 0L, 0L, 0L)))).isNull();
        assertThat(gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                null, "gemini-3.8-flash", null, 0L, 0L, 1L)))).isNull();
        assertThat(gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                null, "gemini-3.8-flash", 1L, 0L, 1L, 0L)))).isNull();
    }

    @Test
    void theCommittedModelsFileLoadsAndPricesSonnetCacheWritesPerTtl() throws Exception {
        var configured = loadCatalog().find(SONNET).orElseThrow();

        // The prices themselves change with the provider; the file only has to bind them.
        assertThat(configured.model()).isEqualTo(SONNET_MODEL);
        assertThat(configured.price().cacheWrite5mPerMillion()).isNotNull();
        assertThat(configured.price().cacheWrite1hPerMillion()).isNotNull();
        assertThat(configured.price().cacheWritePerMillion()).isNull();
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
        ObjectNode snapshot = (ObjectNode) sonnetSnapshot();
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
    void aggregateEstimatorCannotAssumeACacheTtlForSonnet() {
        var sonnetEstimator = new DeveloperCostEstimator(catalogOf(sonnetEntry(SONNET)));
        var usage = new DeveloperCostEstimator.Usage(
                SONNET, SONNET_MODEL, 1_000_000L, 2_000_000L, 3_000_000L, 100_000L);

        assertThat(sonnetEstimator.estimate(List.of(usage))).isNull();
    }

    @Test
    void estimatesOutputAndCacheWritesAtOneAggregateCacheWriteRate() {
        var price = new ModelPrice("USD", LocalDate.parse("2026-09-21"), URI.create("https://example.test/luna"),
                new BigDecimal("0.20"), new BigDecimal("0.02"), new BigDecimal("0.25"), null, null,
                new BigDecimal("1.20"));
        var luna = new DeveloperCostEstimator(catalogOf(entry("codemie-gpt-5.6-luna", "gpt-5.6-luna-2026-07-09",
                price)));
        var estimate = luna.estimate(List.of(new DeveloperCostEstimator.Usage(
                null, "gpt-5.6-luna-2026-07-09", 1_000_000L, 2_000_000L, 3_000_000L, 100_000L)));
        assertThat(estimate).isNotNull();
        assertThat(estimate.amount()).isEqualTo("1.11");
        assertThat(estimate.asOf()).isEqualTo(LocalDate.parse("2026-09-21"));
    }

    /** An estimator with one model entry, "priced", that runs {@code model} at these USD rates per million. */
    static DeveloperCostEstimator estimatorFor(String model, String source, String input, String cacheRead,
                                               String output) {
        var price = new ModelPrice("USD", LocalDate.parse("2026-09-19"), URI.create(source), new BigDecimal(input),
                new BigDecimal(cacheRead), null, null, null, new BigDecimal(output));
        var entry = new ModelEntry("priced", "anthropic-messages", "https://api.anthropic.com", "api-key",
                "PRICED_KEY", model, List.of("low"), "low", null, price);
        return new DeveloperCostEstimator(new ModelCatalog(new ModelCatalog.Properties(List.of(entry), null),
                "claude-code", name -> null));
    }

    /** Sonnet with separate cache write rates for the 5 minute and the 1 hour cache. */
    private static ModelEntry sonnetEntry(String id) {
        var price = new ModelPrice("USD", LocalDate.parse("2026-11-03"), URI.create("https://example.test/sonnet"),
                new BigDecimal("2.00"), new BigDecimal("0.10"), null, new BigDecimal("2.50"), new BigDecimal("4.00"),
                new BigDecimal("10.00"));
        return entry(id, SONNET_MODEL, price);
    }

    private static ModelEntry entry(String id, String model, ModelPrice price) {
        return new ModelEntry(id, "anthropic-messages", "https://gateway.example.test", "api-key", "PRICED_KEY",
                model, List.of("low"), "low", null, price);
    }

    private static ModelCatalog catalogOf(ModelEntry... entries) {
        return new ModelCatalog(new ModelCatalog.Properties(List.of(entries), null), "claude-code", name -> null);
    }

    private static ModelCatalog loadCatalog() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        Path models = directory.resolve("config/developer-models.yaml");
        if (!Files.exists(models)) {
            models = directory.getParent().resolve("config/developer-models.yaml");
        }
        var source = new YamlPropertySourceLoader().load("models", new FileSystemResource(models)).getFirst();
        var file = new Binder(ConfigurationPropertySources.from(source))
                .bind("factory.dev-factory", ModelCatalog.Properties.class)
                .get();
        return new ModelCatalog(file, "claude-code", name -> null);
    }

    private static JsonNode sonnetSnapshot() {
        return JSON.valueToTree(new AttemptRateSnapshot(catalogOf(sonnetEntry(SONNET))).snapshot(SONNET, SONNET_MODEL));
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
