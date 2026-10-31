package org.folio.factory.app.web;

import org.folio.factory.devfactory.runtime.TokenUsage;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

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
        INPUT(TokenUsage::inputTokens, "inputPerMillion", "input", "Input (uncached)"),
        CACHE_READ(TokenUsage::cacheReadTokens, "cacheReadPerMillion", "cacheRead", "Cache read"),
        CACHE_WRITE(TokenUsage::cacheWriteTokens, "cacheWritePerMillion", "cacheWrite", "Cache write"),
        OUTPUT(TokenUsage::outputTokens, "outputPerMillion", "output", "Output");

        /** Key of each category in the run page's token totals, mapped to its label, in display order. */
        static final Map<String, String> LABELS = labels();

        final Function<TokenUsage, Long> tokens;
        final String rateField;
        final String key;
        private final String label;

        Category(Function<TokenUsage, Long> tokens, String rateField, String key, String label) {
            this.tokens = tokens;
            this.rateField = rateField;
            this.key = key;
            this.label = label;
        }

        public String label() {
            return label;
        }

        private static Map<String, String> labels() {
            Map<String, String> labels = new LinkedHashMap<>();
            for (var category : values()) {
                labels.put(category.key, category.label);
            }
            return Collections.unmodifiableMap(labels);
        }
    }

    public static AttemptCharge calculate(JsonNode reported, JsonNode rate, boolean complete) {
        var usage = TokenUsage.from(reported);
        var breakdown = breakdown(usage);
        boolean allReported = breakdown.size() == Category.values().length;
        boolean known = complete && !usage.usageIncomplete() && allReported;
        boolean hasPricedTokens = false;
        BigDecimal cost = BigDecimal.ZERO;
        for (var entry : breakdown.entrySet()) {
            BigDecimal categoryCost = categoryCost(usage, rate, entry.getKey(), entry.getValue());
            if (categoryCost == null) {
                known = false;
            } else if (entry.getValue() > 0) {
                hasPricedTokens = true;
                cost = cost.add(categoryCost);
            }
        }
        long tokens = breakdown.values().stream().mapToLong(Long::longValue).sum();
        String currency = rate.path("currency").asString("");
        boolean hasCurrency = !currency.isBlank();
        boolean subtotalPresent = hasCurrency && (hasPricedTokens || allReported && tokens == 0);
        return new AttemptCharge(
                tokens, Map.copyOf(breakdown), known && hasCurrency, subtotalPresent, cost, currency);
    }

    /** The count of each category the usage reports, never below zero; an unreported category is left out. */
    private static Map<Category, Long> breakdown(TokenUsage usage) {
        var breakdown = new EnumMap<Category, Long>(Category.class);
        for (var category : Category.values()) {
            var value = category.tokens.apply(usage);
            if (value != null) {
                breakdown.put(category, Math.max(0, value));
            }
        }
        return breakdown;
    }

    private static BigDecimal categoryCost(TokenUsage usage, JsonNode rate, Category category, long tokens) {
        boolean hasCacheTtlRates = rate.has("cacheWrite5mPerMillion") || rate.has("cacheWrite1hPerMillion");
        if (category == Category.CACHE_WRITE && tokens > 0 && hasCacheTtlRates) {
            return cacheWriteCost(usage, rate, tokens);
        }
        return costForTokens(tokens, rate.path(category.rateField));
    }

    private static BigDecimal cacheWriteCost(TokenUsage usage, JsonNode rate, long totalTokens) {
        Long fiveMinuteTokens = usage.cacheWrite5mTokens();
        Long oneHourTokens = usage.cacheWrite1hTokens();
        if (fiveMinuteTokens == null || oneHourTokens == null) {
            return null;
        }

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
