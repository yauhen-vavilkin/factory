package org.folio.factory.core.trigger;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * A normalised trigger event from any source: Jira webhooks, GitHub webhooks, CI
 * hooks or manual initiation. The router matches {@code type} and payload filters
 * against registered flow trigger contracts.
 */
public record TriggerEvent(String type, String source, JsonNode payload, Instant receivedAt) {

    public static TriggerEvent of(String type, String source, JsonNode payload) {
        return new TriggerEvent(type, source, payload, Instant.now());
    }
}
