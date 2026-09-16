package org.folio.factory.connectors.jira;

import tools.jackson.databind.JsonNode;

public interface JiraConnector {

    /**
     * Full issue payload including the changelog expansion, parent, subtasks and
     * direct issue-link metadata. Read-only; used for task intake.
     */
    JiraIssue getIssue(String issueKey);

    /**
     * The {@code limit} most recent comments of an issue as the raw Jira comment
     * page ({@code comments} array plus {@code total}).
     */
    JsonNode getComments(String issueKey, int limit);

    /**
     * Bounded fields (summary, status, description, issue type) of one directly
     * linked issue. Read-only; used for first-order link context.
     */
    JiraIssue getLinkedIssue(String issueKey);

    /** Field metadata ({@code id}, {@code name}, ...) used to find instance-specific custom fields by name. */
    JsonNode getFields();

    void addComment(String issueKey, String body);

    /**
     * Transitions the issue using the human-readable transition name
     * (e.g. "QA Complete"). No-op names that don't resolve raise an error.
     */
    void transitionIssue(String issueKey, String transitionName);
}
