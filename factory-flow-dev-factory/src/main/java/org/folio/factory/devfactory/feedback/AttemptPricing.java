package org.folio.factory.devfactory.feedback;

import java.util.Map;

/** Application-provided rate card snapshot; no billing authority is inferred. */
public interface AttemptPricing {
    /** The price of the run's model entry; modelId is null for Pi, then the model string finds the entry. */
    Map<String, Object> snapshot(String modelId, String model);
}
