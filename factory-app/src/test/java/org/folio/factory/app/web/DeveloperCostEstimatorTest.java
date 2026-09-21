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
            "flash", new DeveloperPricingProperties.Rate("openai-compatible", "glm-5.3-flash", "USD",
                    LocalDate.parse("2026-09-19"), URI.create("https://docs.z.ai/guides/overview/pricing"),
                    new BigDecimal("0.15"), new BigDecimal("0.03"), null, new BigDecimal("0.50")))));

    @Test void estimatesOnlyFromExactConfiguredIdentityAndAvailableCategories() {
        var estimate = estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "openai-compatible", "glm-5.3-flash", 71_200L, 2_500_000L, 0L, 35_100L)));
        assertThat(estimate).isNotNull();
        assertThat(estimate.amount()).isEqualTo("0.10323");
        assertThat(estimate.currency()).isEqualTo("USD");
    }

    @Test void refusesUnknownModelsAndPositiveCategoriesWithoutRates() {
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "openai-compatible", "other", 1L, 0L, 0L, 0L)))).isNull();
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "openai-compatible", "glm-5.3-flash", 1L, 0L, 1L, 0L)))).isNull();
        assertThat(estimator.estimate(List.of(new DeveloperCostEstimator.Usage(
                "openai-compatible", "glm-5.3-flash", null, 0L, 0L, 1L)))).isNull();
    }

    @Test void estimatesGeminiWithCacheHitsAndSuppressesMissingUsage() throws Exception {
        var directory = Path.of("").toAbsolutePath();
        var card = directory.resolve("config/developer-pricing.yaml");
        if (!Files.exists(card)) card = directory.getParent().resolve("config/developer-pricing.yaml");
        var source = new YamlPropertySourceLoader().load("pricing", new FileSystemResource(card)).getFirst();
        var rates = new Binder(ConfigurationPropertySources.from(source))
                .bind("factory.developer-pricing", DeveloperPricingProperties.class).get();
        var configured = rates.rates().get("codemie-gemini-3-8-flash");
        assertThat(configured.provider()).isEqualTo("codemie");
        assertThat(configured.model()).isEqualTo("gemini-3.8-flash");
        assertThat(configured.inputPerMillion()).isEqualByComparingTo("0.75");
        assertThat(configured.cacheReadPerMillion()).isEqualByComparingTo("0.075");
        assertThat(configured.outputPerMillion()).isEqualByComparingTo("3.75");
        assertThat(configured.cacheWritePerMillion()).isNull();
        var gemini = new DeveloperCostEstimator(rates);
        var estimate = gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                "codemie", "gemini-3.8-flash", 1_000_000L, 2_000_000L, 0L, 100_000L)));
        assertThat(estimate).isNotNull();
        assertThat(estimate.amount()).isEqualTo("1.275");
        assertThat(estimate.asOf()).isEqualTo(LocalDate.parse("2026-09-21"));
        assertThat(gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                "codemie", "gemini-3.8-flash", 0L, 0L, 0L, 0L)))).isNull();
        assertThat(gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                "codemie", "gemini-3.8-flash", null, 0L, 0L, 1L)))).isNull();
        assertThat(gemini.estimate(List.of(new DeveloperCostEstimator.Usage(
                "codemie", "gemini-3.8-flash", 1L, 0L, 1L, 0L)))).isNull();
    }
}
