package org.folio.factory.core.agent;

/**
 * Read-only view of an artifact version handed to an agent worker.
 */
public record ArtifactContent(String name, int version, String contentType, String content) {
}
