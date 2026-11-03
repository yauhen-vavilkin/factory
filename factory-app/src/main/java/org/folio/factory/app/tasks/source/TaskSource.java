package org.folio.factory.app.tasks.source;

import org.folio.factory.connectors.jira.JiraSourceConnector;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Where My tasks come from: the Jira search, its assignee and query, and the Jira instance they belong to. */
@Component
public record TaskSource(@Qualifier("jiraSourceConnector") JiraSourceConnector connector,
                         TaskSourceProperties properties, TaskJiraInstance jira) {
}
