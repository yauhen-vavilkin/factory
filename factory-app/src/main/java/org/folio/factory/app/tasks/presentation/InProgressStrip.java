package org.folio.factory.app.tasks.presentation;

/** The one-line shortcut to the running task on My tasks; run is "model · effort · time so far". */
public record InProgressStrip(String key, String jiraUrl, String title, String run, String runUrl) {
}
