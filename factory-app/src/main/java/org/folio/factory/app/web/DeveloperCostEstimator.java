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

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DeveloperCostEstimator {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private final ModelCatalog models;

    Estimate estimate(Collection<Usage> usages) {
        BigDecimal total = BigDecimal.ZERO;
        ModelPrice selected = null;
        boolean observed = false;
        for (Usage usage : usages) {
            if (!usage.present() || usage.model() == null) return null;
            // Pi can normalize absent provider usage to four zeros. Do not show that as a free run.
            if (usage.input() == 0
                    && usage.cacheRead() == 0
                    && usage.cacheWrite() == 0
                    && usage.output() == 0) return null;
            var rate = models.pricedEntry(usage.modelId(), usage.model()).map(Model::price).orElse(null);
            if (rate == null
                    || selected != null
                            && (!selected.currency().equals(rate.currency())
                                    || !selected.asOf().equals(rate.asOf())
                                    || !selected.source().equals(rate.source()))) return null;
            selected = rate;
            BigDecimal amount = charge(usage.input(), rate.inputPerMillion());
            if (amount == null) return null;
            total = total.add(amount);
            amount = charge(usage.cacheRead(), rate.cacheReadPerMillion());
            if (amount == null) return null;
            total = total.add(amount);
            amount = charge(usage.cacheWrite(), rate.cacheWritePerMillion());
            if (amount == null) return null;
            total = total.add(amount);
            amount = charge(usage.output(), rate.outputPerMillion());
            if (amount == null) return null;
            total = total.add(amount);
            observed = true;
        }
        if (!observed || selected == null) return null;
        return new Estimate(
                total.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                selected.currency(),
                selected.asOf(),
                selected.source().toString());
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

    record Estimate(String amount, String currency, LocalDate asOf, String source) {}
}
