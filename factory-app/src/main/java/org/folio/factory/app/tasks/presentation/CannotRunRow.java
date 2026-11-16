package org.folio.factory.app.tasks.presentation;

/**
 * A task assigned to the developer that cannot run here, under Cannot run here on My tasks.
 *
 * @param chip           the short cause on the row, for example "No repository for KEYCLOAK"
 * @param reason         the whole cause as a sentence, shown on hover
 * @param needsRepository the task's Jira project has no repository, or several, in the repository file
 */
public record CannotRunRow(String key, String jiraUrl, String title, String jiraStatus, String chip,
                           String reason, boolean needsRepository) {
}
