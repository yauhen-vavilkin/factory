package org.folio.factory.core.trigger;

import tools.jackson.databind.JsonNode;

/**
 * A manual trigger that {@link PipelineRouter#prepareManual} built after the flow identity, input
 * schema, payload size and preflight checks. The type states that intent but does not enforce it:
 * anyone can build one, and {@link PipelineRouter#routePrepared(PreparedManualTrigger)} re-checks
 * only the flow identity, dedup and the database budget, so callers must get it from prepareManual.
 */
public record PreparedManualTrigger(String flowId, JsonNode payload, String dedupKey) {
}
