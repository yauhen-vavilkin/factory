package org.folio.factory.core.trigger;

import tools.jackson.databind.JsonNode;

/** Plugin-owned, read-only admission checks, before creating a new execution. */
@FunctionalInterface
public interface TriggerPreflight {
    void requireReady(String flowId, JsonNode payload);
}
