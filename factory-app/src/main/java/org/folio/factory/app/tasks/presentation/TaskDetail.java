package org.folio.factory.app.tasks.presentation;

import java.util.List;
import java.util.UUID;

/**
 * The task page: head, status card, the action it offers. repository says where the task is checked out and where
 * its pull request goes. reason and reasonNote are set when the latest attempt failed: the failure sentence and
 * what the attempt left behind. spentTitle says why the amount may be too low. runUrl opens the latest run.
 */
public record TaskDetail(UUID taskId, String key, String jiraUrl, String title, String repository,
                         long version, String inputFingerprint, String destinationFingerprint,
                         String stateLabel, String stateClass, String headline, String reason, String reasonNote,
                         List<String> draftReasons, String spent, String spentNote, String spentTitle,
                         boolean retryAllowed, int nextAttempt, String runUrl, String prUrl, String prAction,
                         UUID markStoppedAttemptId) {
}
