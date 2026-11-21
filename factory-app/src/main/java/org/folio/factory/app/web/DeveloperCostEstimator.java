package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import org.folio.factory.devfactory.runtime.Model;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.runtime.ModelPrice;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DeveloperCostEstimator {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private final ModelCatalog models;

    Estimate estimate(Collection<Usage> usages) {
        BigDecimal total = BigDecimal.ZERO;
        ModelPrice selected = null;
        for (Usage usage : usages) {
            var rate = rate(usage, selected);
            var amount = rate == null ? null : charge(usage, rate);
            if (amount == null) {
                return null;
            }
            selected = rate;
            total = total.add(amount);
        }
        if (selected == null) {
            return null;
        }
        return new Estimate(
                total.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                selected.currency(),
                selected.asOf(),
                selected.source().toString());
    }

    /**
     * The price of one usage; null when its figures are missing or all zero, when its model has no price, or when
     * the price comes from another source than the one {@code selected} by the usages before it.
     */
    private ModelPrice rate(Usage usage, ModelPrice selected) {
        if (!usage.present() || usage.model() == null || zeroUsage(usage)) {
            return null;
        }
        var rate = models.pricedEntry(usage.modelId(), usage.model()).map(Model::price).orElse(null);
        return rate == null || selected != null && !sameSource(selected, rate) ? null : rate;
    }

    /** Pi can normalize absent provider usage to four zeros. Do not show that as a free run. */
    private static boolean zeroUsage(Usage usage) {
        return usage.input() == 0
                && usage.cacheRead() == 0
                && usage.cacheWrite() == 0
                && usage.output() == 0;
    }

    private static boolean sameSource(ModelPrice selected, ModelPrice rate) {
        return selected.currency().equals(rate.currency())
                && selected.asOf().equals(rate.asOf())
                && selected.source().equals(rate.source());
    }

    /** The four categories of one usage summed; null when any one of them has no price. */
    private static BigDecimal charge(Usage usage, ModelPrice rate) {
        var amounts = Stream.of(charge(usage.input(), rate.inputPerMillion()),
                charge(usage.cacheRead(), rate.cacheReadPerMillion()),
                charge(usage.cacheWrite(), rate.cacheWritePerMillion()),
                charge(usage.output(), rate.outputPerMillion())).toList();
        return amounts.contains(null) ? null : amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal charge(Long tokens, BigDecimal perMillion) {
        if (tokens == null) return null;
        if (tokens == 0) return BigDecimal.ZERO;
        return perMillion == null
                ? null
                : BigDecimal.valueOf(tokens)
                        .multiply(perMillion)
                        .divide(MILLION, 12, RoundingMode.HALF_UP);
    }

    /** modelId is null for Pi and for runs started before 2026-11-03. */
    record Usage(String modelId, String model, Long input, Long cacheRead, Long cacheWrite, Long output) {
        boolean present() {
            return input != null && cacheRead != null && cacheWrite != null && output != null;
        }
    }

    record Estimate(String amount, String currency, LocalDate asOf, String source) {
        /** The amount in the console's one cost format. */
        public String label() {
            return UiFormat.money(new BigDecimal(amount), currency);
        }
    }
}
