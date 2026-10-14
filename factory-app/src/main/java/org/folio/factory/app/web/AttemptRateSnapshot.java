package org.folio.factory.app.web;

import org.folio.factory.devfactory.feedback.AttemptPricing;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AttemptRateSnapshot implements AttemptPricing {
    private final DeveloperPricingProperties prices;

    public AttemptRateSnapshot(DeveloperPricingProperties prices) {
        this.prices = prices;
    }

    public Map<String, Object> snapshot(String model) {
        var rate = prices.rateFor(model).orElse(null);
        if (rate == null) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        result.put("model", rate.model());
        result.put("currency", rate.currency());
        result.put("asOf", rate.asOf().toString());
        result.put("source", rate.source().toString());
        result.put("inputPerMillion", rate.inputPerMillion());
        result.put("cacheReadPerMillion", rate.cacheReadPerMillion());
        result.put("cacheWritePerMillion", rate.cacheWritePerMillion());
        result.put("outputPerMillion", rate.outputPerMillion());
        return result;
    }
}
