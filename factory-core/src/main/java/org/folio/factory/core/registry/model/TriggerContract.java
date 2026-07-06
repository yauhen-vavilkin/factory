package org.folio.factory.core.registry.model;

import java.util.Map;

/**
 * Declares an event type this flow reacts to. Filters map JSON pointer expressions
 * into the event payload to expected string values, e.g.
 * {@code "/issue/fields/status/name": "Ready for QA"}.
 */
public record TriggerContract(String eventType, Map<String, String> filters) {

    public TriggerContract {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
