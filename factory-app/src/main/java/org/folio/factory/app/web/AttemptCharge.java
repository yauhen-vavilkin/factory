package org.folio.factory.app.web;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/** Pricing of one saved cumulative usage observation; unknown categories stay unknown. */
record AttemptCharge(
        long tokens,
        Map<Category, Long> breakdown,
        boolean known,
        boolean subtotalPresent,
        BigDecimal cost,
        String currency) {
    enum Category {
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

    static AttemptCharge calculate(JsonNode usage, JsonNode rate, boolean complete) {
        boolean known = complete && !usage.path("usageIncomplete").asBoolean(false);
        boolean priced = false;
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
            if (category == Category.CACHE_WRITE && count > 0
                    && (rate.has("cacheWrite5mPerMillion") || rate.has("cacheWrite1hPerMillion"))) {
                var amount = cacheWriteCharge(usage, rate, count);
                if (amount == null) known = false;
                else {
                    priced = true;
                    cost = cost.add(amount);
                }
                continue;
            }
            var price = rate.path(category.rateField);
            if (count != 0 && !price.isNumber()) known = false;
            else if (count != 0) {
                priced = true;
                cost =
                        cost.add(
                                BigDecimal.valueOf(count)
                                        .multiply(price.decimalValue())
                                        .divide(BigDecimal.valueOf(1_000_000)));
            }
        }
        String currency = rate.path("currency").asString("");
        known &= !currency.isBlank();
        boolean subtotal =
                (priced || breakdown.size() == Category.values().length && tokens == 0)
                        && !currency.isBlank();
        return new AttemptCharge(tokens, Map.copyOf(breakdown), known, subtotal, cost, currency);
    }

    private static BigDecimal cacheWriteCharge(JsonNode usage, JsonNode rate, long total) {
        var fiveMinutes = usage.path("cacheWrite5mTokens");
        var oneHour = usage.path("cacheWrite1hTokens");
        if (!fiveMinutes.isIntegralNumber() || !oneHour.isIntegralNumber()
                || fiveMinutes.asLong() < 0 || oneHour.asLong() < 0
                || fiveMinutes.asLong() > total || oneHour.asLong() != total - fiveMinutes.asLong())
            return null;
        BigDecimal amount = BigDecimal.ZERO;
        for (String ttl : new String[] {"5m", "1h"}) {
            long count = usage.path("cacheWrite" + ttl + "Tokens").asLong();
            var price = rate.path("cacheWrite" + ttl + "PerMillion");
            if (count > 0 && !price.isNumber()) return null;
            if (count > 0)
                amount = amount.add(BigDecimal.valueOf(count).multiply(price.decimalValue())
                        .divide(BigDecimal.valueOf(1_000_000)));
        }
        return amount;
    }
}
