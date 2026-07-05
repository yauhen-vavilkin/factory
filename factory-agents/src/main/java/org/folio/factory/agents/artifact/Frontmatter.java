package org.folio.factory.agents.artifact;

import tools.jackson.databind.JsonNode;

/**
 * A parsed Markdown artifact: machine-readable YAML frontmatter plus
 * human-readable body.
 */
public record Frontmatter(JsonNode metadata, String body) {
}
