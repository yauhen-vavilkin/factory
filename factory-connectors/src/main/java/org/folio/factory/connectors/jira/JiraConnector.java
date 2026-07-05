package org.folio.factory.connectors.jira;

public interface JiraConnector {

    JiraIssue getIssue(String issueKey);

    void addComment(String issueKey, String body);

    /**
     * Transitions the issue using the human-readable transition name
     * (e.g. "QA Complete"). No-op names that don't resolve raise an error.
     */
    void transitionIssue(String issueKey, String transitionName);
}
