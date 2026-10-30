package org.folio.factory.app.web;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/** Pricing of one saved cumulative usage observation; unknown categories stay unknown. */
public record AttemptCharge(
        long tokens,
        Map<Category, Long> breakdown,
        boolean known,
        boolean subtotalPresent,
        BigDecimal cost,
        String currency) {
    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000);

    public enum Category {
        INPUT("inputTokens", "inputPerMillion"),
        CACHE_READ("cacheReadTokens", "cacheReadPerMillion"),
        CACHE_WRITE("cacheWriteTokens", "cacheWritePerMillion"),
        OUTPUT("outputTokens", "outputPerMillion");

        final String tokenField;
        final String rateField;

        Category(String tokenField, String rateField) {
            this.tokenField = tokenField;
            this.rateField = rateField;
        }
    }

    public static AttemptCharge calculate(JsonNode usage, JsonNode rate, boolean complete) {
        boolean known = complete && !usage.path("usageIncomplete").asBoolean(false);
        boolean hasPricedTokens = false;
        long tokens = 0;
        var breakdown = new EnumMap<Category, Long>(Category.class);
        BigDecimal cost = BigDecimal.ZERO;
        for (var category : Category.values()) {
            var value = usage.path(category.tokenField);
            if (!value.isNumber()) {
                known = false;
                continue;
            }
            long count = Math.max(0, value.asLong());
            breakdown.put(category, count);
            tokens += count;
            BigDecimal categoryCost = categoryCost(usage, rate, category, count);
            if (categoryCost == null) {
                known = false;
                continue;
            }
            if (count > 0) {
                hasPricedTokens = true;
                cost = cost.add(categoryCost);
            }
        }
        String currency = rate.path("currency").asString("");
        boolean hasCurrency = !currency.isBlank();
        boolean allCategoriesAreZero = breakdown.size() == Category.values().length && tokens == 0;
        boolean subtotalPresent = hasCurrency && (hasPricedTokens || allCategoriesAreZero);
        return new AttemptCharge(
                tokens, Map.copyOf(breakdown), known && hasCurrency, subtotalPresent, cost, currency);
    }

    private static BigDecimal categoryCost(JsonNode usage, JsonNode rate, Category category, long tokens) {
        boolean hasCacheTtlRates = rate.has("cacheWrite5mPerMillion") || rate.has("cacheWrite1hPerMillion");
        if (category == Category.CACHE_WRITE && tokens > 0 && hasCacheTtlRates) {
            return cacheWriteCost(usage, rate, tokens);
        }
        return costForTokens(tokens, rate.path(category.rateField));
    }

    private static BigDecimal cacheWriteCost(JsonNode usage, JsonNode rate, long totalTokens) {
        JsonNode fiveMinuteUsage = usage.path("cacheWrite5mTokens");
        JsonNode oneHourUsage = usage.path("cacheWrite1hTokens");
        if (!fiveMinuteUsage.isIntegralNumber() || !oneHourUsage.isIntegralNumber()) {
            return null;
        }

        long fiveMinuteTokens = fiveMinuteUsage.asLong();
        long oneHourTokens = oneHourUsage.asLong();
        if (fiveMinuteTokens < 0 || oneHourTokens < 0 || fiveMinuteTokens > totalTokens
                || oneHourTokens != totalTokens - fiveMinuteTokens) {
            return null;
        }

        BigDecimal fiveMinuteCost = costForTokens(fiveMinuteTokens, rate.path("cacheWrite5mPerMillion"));
        BigDecimal oneHourCost = costForTokens(oneHourTokens, rate.path("cacheWrite1hPerMillion"));
        if (fiveMinuteCost == null || oneHourCost == null) {
            return null;
        }
        return fiveMinuteCost.add(oneHourCost);
    }

    /** A missing rate leaves positive usage unpriced; zero usage does not require a rate. */
    private static BigDecimal costForTokens(long tokens, JsonNode rate) {
        if (tokens == 0) {
            return BigDecimal.ZERO;
        }
        if (!rate.isNumber()) {
            return null;
        }
        return BigDecimal.valueOf(tokens).multiply(rate.decimalValue()).divide(TOKENS_PER_MILLION);
    }
}
