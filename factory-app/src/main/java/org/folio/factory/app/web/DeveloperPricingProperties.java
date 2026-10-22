package org.folio.factory.app.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

@ConfigurationProperties("factory.developer-pricing")
public record DeveloperPricingProperties(Map<String, Rate> rates) {

    public DeveloperPricingProperties {
        if (rates == null) {
            rates = Map.of();
        }
        var models = new HashSet<String>();
        for (var entry : rates.entrySet()) {
            String id = entry.getKey();
            Rate rate = entry.getValue();
            if (id == null || id.isBlank() || rate == null) {
                throw new IllegalArgumentException("Pricing rate id and value are required");
            }
            rate.validate(id);
            if (!models.add(rate.model())) {
                throw new IllegalArgumentException("Duplicate pricing model " + rate.model());
            }
        }
        rates = Map.copyOf(rates);
    }

    public Optional<Rate> rateFor(String model) {
        return rates.values().stream().filter(rate -> rate.model().equals(model)).findFirst();
    }

    public record Rate(
            String model,
            String currency,
            LocalDate asOf,
            URI source,
            BigDecimal inputPerMillion,
            BigDecimal cacheReadPerMillion,
            BigDecimal cacheWritePerMillion,
            BigDecimal outputPerMillion,
            BigDecimal cacheWrite5mPerMillion,
            BigDecimal cacheWrite1hPerMillion) {
        @ConstructorBinding
        public Rate {
        }

        public Rate(
                String model,
                String currency,
                LocalDate asOf,
                URI source,
                BigDecimal inputPerMillion,
                BigDecimal cacheReadPerMillion,
                BigDecimal cacheWritePerMillion,
                BigDecimal outputPerMillion) {
            this(
                    model, currency, asOf, source, inputPerMillion, cacheReadPerMillion,
                    cacheWritePerMillion, outputPerMillion, null, null);
        }

        private void validate(String id) {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("Pricing rate " + id + " requires model");
            }
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException(
                        "Pricing rate " + id + " requires an ISO currency");
            }
            if (asOf == null) {
                throw new IllegalArgumentException("Pricing rate " + id + " requires as-of");
            }
            if (source == null || !"https".equalsIgnoreCase(source.getScheme())) {
                throw new IllegalArgumentException(
                        "Pricing rate " + id + " requires an HTTPS source");
            }
            BigDecimal[] categoryRates = {
                inputPerMillion, cacheReadPerMillion, cacheWritePerMillion, outputPerMillion,
                cacheWrite5mPerMillion, cacheWrite1hPerMillion
            };
            for (BigDecimal rate : categoryRates) {
                if (rate != null && rate.signum() < 0) {
                    throw new IllegalArgumentException(
                            "Pricing rate " + id + " cannot be negative");
                }
            }
        }
    }
}
