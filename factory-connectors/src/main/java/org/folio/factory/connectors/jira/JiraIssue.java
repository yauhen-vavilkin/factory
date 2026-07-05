package org.folio.factory.connectors.jira;

import tools.jackson.databind.JsonNode;

import java.util.List;

public record JiraIssue(
        String key,
        String summary,
        String description,
        String status,
        String issueType,
        List<String> labels,
        JsonNode raw) {
}
