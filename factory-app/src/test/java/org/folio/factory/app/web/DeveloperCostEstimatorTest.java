package org.folio.factory.app.web;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
}
