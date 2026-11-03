package org.folio.factory.app.tasks.presentation;

import java.util.UUID;

/** One task under Triggered: its latest attempt, cost and the actions it allows. */
public record TriggeredRow(UUID taskId, String key, String jiraUrl, String title, String repository,
                           boolean addedByKey, String stateLabel, String stateClass, String link, String detail,
                           String prUrl, String prLabel, boolean retryAllowed, UUID markStoppedAttemptId,
                           String cost, String costNote, String costNoteTitle, String when, String whenTitle,
                           String whenNote) {
}
