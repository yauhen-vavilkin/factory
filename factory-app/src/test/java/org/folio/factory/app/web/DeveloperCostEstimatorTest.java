package org.folio.factory.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperCostEstimatorTest {
    private final DeveloperCostEstimator estimator = new DeveloperCostEstimator(new DeveloperPricingProperties(Map.of(
            "haiku", new DeveloperPricingProperties.Rate("claude-haiku-5-5", "USD",
                    LocalDate.parse("2026-09-19"), URI.create("https://www.anthropic.com/pricing"),
                    new BigDecimal("0.15"), new BigDecimal("0.03"), null, new BigDecimal("0.50")))));

    @Test void estimatesFromModelAndAvailableCategories() {
        var estimate = estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "claude-haiku-5-5", 71_200L, 2_500_000L, 0L, 35_100L)));
        assertThat(estimate).isNotNull();
        assertThat(estimate.amount()).isEqualTo("0.10323");
        assertThat(estimate.currency()).isEqualTo("USD");
    }

    @Test void refusesUnknownModelsAndPositiveCategoriesWithoutRates() {
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "other", 1L, 0L, 0L, 0L)))).isNull();
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "claude-haiku-5-5", 1L, 0L, 1L, 0L)))).isNull();
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "claude-haiku-5-5", null, 0L, 0L, 1L)))).isNull();
    }

    @Test void rejectsAmbiguousOfficialRatesForTheSameModel() {
        var configured = new DeveloperPricingProperties.Rate("claude-haiku-5-5", "USD",
                LocalDate.parse("2026-09-19"), URI.create("https://www.anthropic.com/pricing"),
                new BigDecimal("0.15"), new BigDecimal("0.03"), null, new BigDecimal("0.50"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DeveloperPricingProperties(
                Map.of("first", configured, "second", configured)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate pricing model claude-haiku-5-5");
    }

    @Test void estimatesGeminiWithCacheHitsAndSuppressesMissingUsage() throws Exception {
        var directory = Path.of("").toAbsolutePath();
        var card = directory.resolve("config/developer-pricing.yaml");
        if (!Files.exists(card)) card = directory.getParent().resolve("config/developer-pricing.yaml");
        var source = new YamlPropertySourceLoader().load("pricing", new FileSystemResource(card)).getFirst();
        var rates = new Binder(ConfigurationPropertySources.from(source))
                .bind("factory.developer-pricing", DeveloperPricingProperties.class).get();
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

    @Test void pricesSonnetFromYamlOnlyWithObservedCacheTtl() throws Exception {
        var directory = Path.of("").toAbsolutePath();
        var card = directory.resolve("config/developer-pricing.yaml");
        if (!Files.exists(card)) card = directory.getParent().resolve("config/developer-pricing.yaml");
        var source = new YamlPropertySourceLoader().load("pricing", new FileSystemResource(card)).getFirst();
        var rates = new Binder(ConfigurationPropertySources.from(source))
                .bind("factory.developer-pricing", DeveloperPricingProperties.class).get();
        var configured = rates.rateFor("claude-sonnet-5-5").orElseThrow();
        assertThat(configured.asOf()).isEqualTo(LocalDate.parse("2026-10-22"));
        assertThat(configured.source()).isEqualTo(URI.create(
                "https://platform.claude.com/docs/en/about-claude/pricing"));
        assertThat(configured.cacheWritePerMillion()).isNull();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var rate = json.valueToTree(new AttemptRateSnapshot(rates).snapshot("claude-sonnet-5-5"));
        var usage = json.createObjectNode().put("inputTokens", 1_000_000L)
                .put("cacheReadTokens", 2_000_000L).put("cacheWriteTokens", 3_000_000L)
                .put("cacheWrite5mTokens", 3_000_000L).put("cacheWrite1hTokens", 0L)
                .put("outputTokens", 100_000L);
        var fiveMinutes = AttemptCharge.calculate(usage, rate, true);
        assertThat(fiveMinutes.known()).isTrue();
        assertThat(fiveMinutes.cost()).isEqualByComparingTo("10.70");
        assertThat(fiveMinutes.tokens()).isEqualTo(6_100_000L);
        usage.put("cacheWrite5mTokens", 1_000_000L).put("cacheWrite1hTokens", 2_000_000L);
        assertThat(AttemptCharge.calculate(usage, rate, true).cost()).isEqualByComparingTo("13.70");
        usage.put("cacheWrite5mTokens", 0L).put("cacheWrite1hTokens", 3_000_000L);
        assertThat(AttemptCharge.calculate(usage, rate, true).cost()).isEqualByComparingTo("15.20");
        usage.remove("cacheWrite1hTokens");
        var unknown = AttemptCharge.calculate(usage, rate, true);
        assertThat(unknown.known()).isFalse();
        assertThat(unknown.cost()).isEqualByComparingTo("3.20"); // Only the observed non-write subtotal.
        usage.put("cacheWrite1hTokens", 1L);
        assertThat(AttemptCharge.calculate(usage, rate, true).known()).isFalse();
        usage.put("cacheWrite5mTokens", 3_000_000L).put("cacheWrite1hTokens", 0L)
                .put("usageIncomplete", true);
        assertThat(AttemptCharge.calculate(usage, rate, true).known()).isFalse();
        assertThat(AttemptCharge.calculate(usage, rate, false).known()).isFalse();
        assertThat(new DeveloperCostEstimator(rates).estimate(List.of(new DeveloperCostEstimator.Usage(
                "claude-sonnet-5-5", 1_000_000L, 2_000_000L, 3_000_000L, 100_000L)))).isNull();
    }

    @Test void estimatesLunaMaxReasoningOutputAndCacheWritesAtOfficialRates() throws Exception {
        var directory = Path.of("").toAbsolutePath();
        var card = directory.resolve("config/developer-pricing.yaml");
        if (!Files.exists(card)) card = directory.getParent().resolve("config/developer-pricing.yaml");
        var source = new YamlPropertySourceLoader().load("pricing", new FileSystemResource(card)).getFirst();
        var rates = new Binder(ConfigurationPropertySources.from(source))
                .bind("factory.developer-pricing", DeveloperPricingProperties.class).get();
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
}
