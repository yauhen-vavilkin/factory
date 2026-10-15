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
}
