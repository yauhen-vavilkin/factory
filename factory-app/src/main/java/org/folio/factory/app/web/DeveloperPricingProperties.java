package org.folio.factory.app.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;

@ConfigurationProperties("factory.developer-pricing")
public record DeveloperPricingProperties(Map<String, Rate> rates) {

    public DeveloperPricingProperties {
        rates = rates == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(rates));
        var identities = new HashSet<String>();
        rates.forEach((id, rate) -> {
            if (id == null || id.isBlank() || rate == null) throw new IllegalArgumentException("Pricing rate id and value are required");
            rate.validate(id);
            if (!identities.add(rate.provider() + "\n" + rate.model()))
                throw new IllegalArgumentException("Duplicate pricing identity for " + rate.provider() + "/" + rate.model());
        });
    }

    public record Rate(String provider, String model, String currency, LocalDate asOf, URI source,
                       BigDecimal inputPerMillion, BigDecimal cacheReadPerMillion,
                       BigDecimal cacheWritePerMillion, BigDecimal outputPerMillion) {
        private void validate(String id) {
            if (blank(provider) || blank(model)) throw new IllegalArgumentException("Pricing rate " + id + " requires provider and model");
            if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Pricing rate " + id + " requires an ISO currency");
            if (asOf == null) throw new IllegalArgumentException("Pricing rate " + id + " requires as-of");
            if (source == null || !"https".equalsIgnoreCase(source.getScheme())) throw new IllegalArgumentException("Pricing rate " + id + " requires an HTTPS source");
            for (BigDecimal value : new BigDecimal[]{inputPerMillion, cacheReadPerMillion, cacheWritePerMillion, outputPerMillion})
                if (value != null && value.signum() < 0) throw new IllegalArgumentException("Pricing rate " + id + " cannot be negative");
        }

        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
}
