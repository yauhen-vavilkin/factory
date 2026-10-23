package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;
import org.folio.factory.connectors.jira.JiraSourceModels.ResolvedSource;
import org.folio.factory.connectors.jira.JiraSourceModels.SourceReference;
import org.folio.factory.connectors.jira.JiraSourceModels.Sprint;

/**
 * Read-only discovery surface for the pilot team source: resolve a board or
 * saved filter, read a sprint and page through issue search results.
 * Implementations must never perform Jira writes.
 */
public interface JiraSourceConnector {

    SourceReference parseSourceReference(String raw);

    ResolvedSource resolveSource(SourceReference reference);

    Sprint getSprint(String sprintId);

    IssuePage searchIssues(String jql, String cursor);
}
