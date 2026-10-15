package org.folio.factory.devfactory.feedback;
import java.util.Map;
/** Application-provided rate card snapshot; no billing authority is inferred. */
public interface AttemptPricing { Map<String,Object> snapshot(String model); }
