package org.folio.factory.core.trigger;

import tools.jackson.databind.JsonNode;

/**
 * A manual trigger that already passed flow identity, input schema, payload size
 * and preflight checks out of band. Only this type is accepted by
 * {@link PipelineRouter#routePrepared(PreparedManualTrigger)}, so creation cannot
 * bypass preparation; dedup and the database budget are still re-checked at
 * creation time.
 */
public record PreparedManualTrigger(String flowId, JsonNode payload, String dedupKey) {
}
