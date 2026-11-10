package org.folio.factory.app.tasks.presentation;

import java.util.UUID;

/**
 * One task under Runs: its latest attempt as a chip, cost and age, and the one action its state allows.
 * chipUrl is set when the chip is a pull request and opens GitHub; costTitle says why a cost may be too low.
 */
public record TriggeredRow(UUID taskId, String key, String jiraUrl, String title, String chipLabel,
                           String chipClass, String chipUrl, String detail, String cost, String costTitle,
                           String when,
                           String whenTitle, RunAction action, String actionUrl, UUID markStoppedAttemptId) {
}
