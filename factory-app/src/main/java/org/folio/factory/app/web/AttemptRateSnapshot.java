package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.devfactory.feedback.AttemptPricing;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AttemptRateSnapshot implements AttemptPricing {
    private final ModelCatalog models;

    @Override
    public Map<String, Object> snapshot(String modelId, String model) {
        var entry = models.pricedEntry(modelId, model).orElse(null);
        if (entry == null) {
            return Map.of();
        }
        var price = entry.price();
        var result = new LinkedHashMap<String, Object>();
        result.put("modelId", entry.id());
        result.put("model", entry.model());
        result.put("currency", price.currency());
        result.put("asOf", price.asOf().toString());
        result.put("source", price.source().toString());
        result.put("inputPerMillion", price.inputPerMillion());
        result.put("cacheReadPerMillion", price.cacheReadPerMillion());
        result.put("cacheWritePerMillion", price.cacheWritePerMillion());
        result.put("outputPerMillion", price.outputPerMillion());
        if (price.cacheWrite5mPerMillion() != null) {
            result.put("cacheWrite5mPerMillion", price.cacheWrite5mPerMillion());
        }
        if (price.cacheWrite1hPerMillion() != null) {
            result.put("cacheWrite1hPerMillion", price.cacheWrite1hPerMillion());
        }
        return result;
    }
}
