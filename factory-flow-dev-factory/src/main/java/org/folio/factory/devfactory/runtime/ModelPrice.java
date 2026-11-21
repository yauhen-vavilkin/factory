package org.folio.factory.devfactory.runtime;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.stream.Stream;

/**
 * The list price of one model entry in USD (or another ISO currency) per million tokens. It is an
 * estimate from a public price page, not a bill. A missing category means its cost is unknown.
 */
public record ModelPrice(String currency, LocalDate asOf, URI source, BigDecimal inputPerMillion,
                         BigDecimal cacheReadPerMillion, BigDecimal cacheWritePerMillion,
                         BigDecimal cacheWrite5mPerMillion, BigDecimal cacheWrite1hPerMillion,
                         BigDecimal outputPerMillion) {

    /** Throws IllegalArgumentException that starts with {@code where} and names the field to fix. */
    void validate(String where) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(where + "price.currency must be an ISO code such as USD.");
        }
        if (asOf == null) {
            throw new IllegalArgumentException(where + "price.as-of must be the date of the price, like 2026-11-03.");
        }
        if (source == null || !"https".equalsIgnoreCase(source.getScheme())) {
            throw new IllegalArgumentException(where + "price.source must be the https address of the price page.");
        }
        boolean negative = Stream.of(inputPerMillion, cacheReadPerMillion, cacheWritePerMillion,
                        cacheWrite5mPerMillion, cacheWrite1hPerMillion, outputPerMillion)
                .anyMatch(rate -> rate != null && rate.signum() < 0);
        if (negative) {
            throw new IllegalArgumentException(where + "a price cannot be negative.");
        }
    }
}
