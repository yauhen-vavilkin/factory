package org.folio.factory.app.tasks.presentation;

/** The one-line shortcut to the running task on My tasks; run is "model · effort", elapsed its time so far. */
public record InProgressStrip(String key, String jiraUrl, String title, String run, String elapsed,
                              String runUrl) {
}
