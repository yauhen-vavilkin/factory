package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.ConnectorHealth;
import tools.jackson.databind.JsonNode;

/**
 * Fails-closed write boundary for the Jira connector. Read access (issue,
 * comment, changelog and link retrieval) is independent of external-write
 * policy, so task intake keeps working with writes disabled; every mutating
 * operation is refused instead of silently dropped.
 */
public final class JiraWriteGuard implements JiraConnector, ConnectorHealth {

    public static final String WRITES_DISABLED_MESSAGE =
            "Jira writes are disabled (factory.connectors.external-writes-enabled=false); "
                    + "read-only Jira access is active";

    private final JiraConnector delegate;

    public JiraWriteGuard(JiraConnector delegate) {
        this.delegate = delegate;
    }

    @Override
    public JiraIssue getIssue(String issueKey) {
        return delegate.getIssue(issueKey);
    }

    @Override
    public JsonNode getComments(String issueKey, int limit) {
        return delegate.getComments(issueKey, limit);
    }

    @Override
    public JiraIssue getLinkedIssue(String issueKey) {
        return delegate.getLinkedIssue(issueKey);
    }

    @Override
    public JsonNode getFields() {
        return delegate.getFields();
    }

    @Override
    public void addComment(String issueKey, String body) {
        throw new IllegalStateException(WRITES_DISABLED_MESSAGE);
    }

    @Override
    public void transitionIssue(String issueKey, String transitionName) {
        throw new IllegalStateException(WRITES_DISABLED_MESSAGE);
    }

    @Override
    public String connectorName() {
        return "jira";
    }

    @Override
    public boolean isConfigured() {
        return delegate instanceof ConnectorHealth health && health.isConfigured();
    }
}
