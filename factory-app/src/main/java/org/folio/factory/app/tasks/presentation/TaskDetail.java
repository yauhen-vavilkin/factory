package org.folio.factory.app.tasks.presentation;

import java.util.List;
import java.util.UUID;

/**
 * The task page: head, status card, the action it offers. repository says where the task is checked out and where
 * its pull request goes. status is the chip and headline of the attempt the task stands for, the same as on its
 * run page. reason and reasonNote are set when the latest attempt failed: the failure sentence and what the
 * attempt left behind. spentTitle says why the amount may be too low. clock is the time of the attempt that works
 * now, null while none works. run opens the latest run, at its PR feedback once there is a pull request.
 */
public record TaskDetail(UUID taskId, String key, String jiraUrl, String title, String repository,
                         long version, String inputFingerprint, String destinationFingerprint,
                         AttemptStatus status, String reason, String reasonNote,
                         List<String> draftReasons, String spent, String spentNote, String spentTitle,
                         AttemptClock clock, boolean retryAllowed, int nextAttempt, RunLink run, String prUrl,
                         String prAction, UUID markStoppedAttemptId) {

    /** The button to the run page: "Open run", or "PR feedback" to the PR feedback part of it. */
    public record RunLink(String label, String url) {
    }
}
