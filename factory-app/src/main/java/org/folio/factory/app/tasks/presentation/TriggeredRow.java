package org.folio.factory.app.tasks.presentation;

import java.util.UUID;

/**
 * One task under Runs: its latest attempt as a chip, cost and age, and the one action its state allows.
 * chipUrl is set when the chip is a pull request and opens GitHub; costTitle says why a cost may be too low.
 * detail is one line (the first sentence of a failure) and detailTitle its whole text.
 */
public record TriggeredRow(UUID taskId, String key, String jiraUrl, String title, String chipLabel,
                           String chipClass, String chipUrl, String detail, String detailTitle, String cost,
                           String costTitle, String when,
                           String whenTitle, RunAction action, String actionUrl, UUID markStoppedAttemptId) {
}
