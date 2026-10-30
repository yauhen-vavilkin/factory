package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;

/**
 * Read-only discovery surface for My tasks: page through issue search results.
 * Implementations must never perform Jira writes.
 */
public interface JiraSourceConnector {

    IssuePage searchIssues(String jql, String cursor);
}
