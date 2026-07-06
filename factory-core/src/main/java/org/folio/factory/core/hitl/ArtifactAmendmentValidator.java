package org.folio.factory.core.hitl;

/**
 * Validates reviewer-amended artifact content before a HITL decision is accepted.
 * Implementations are discovered as Spring beans; flow modules register format
 * checks (e.g. frontmatter structure) for the artifacts they own.
 */
public interface ArtifactAmendmentValidator {

    /**
     * @throws IllegalArgumentException when the amended content is not acceptable
     */
    void validate(String artifactName, String content);
}
