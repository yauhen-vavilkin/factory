package org.folio.factory.connectors.jira;

import java.time.Instant;
import java.util.List;

/**
 * Immutable discovery models for My tasks. Identity is anchored to
 * the immutable Jira issue id; the issue key is display data that can change.
 */
public final class JiraSourceModels {

    private JiraSourceModels() {
    }

    public record DiscoveredIssue(
            String id,
            String key,
            String projectKey,
            List<String> components,
            Instant createdAt,
            JiraIssue issue) {

        public DiscoveredIssue {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Discovered issue requires an immutable id");
            }
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Discovered issue " + id + " requires a key");
            }
            if (projectKey == null || projectKey.isBlank()) {
                throw new IllegalArgumentException("Discovered issue " + key + " requires a project key");
            }
            if (createdAt == null) {
                throw new IllegalArgumentException("Discovered issue " + key + " requires a created time");
            }
            if (issue == null) {
                throw new IllegalArgumentException("Discovered issue " + key + " requires issue content");
            }
            components = components == null ? List.of() : List.copyOf(components);
        }
    }

    public record IssuePage(List<DiscoveredIssue> issues, String nextCursor) {

        public IssuePage {
            if (issues == null) {
                throw new IllegalArgumentException("Issue page requires issues");
            }
            issues = List.copyOf(issues);
        }
    }
}
