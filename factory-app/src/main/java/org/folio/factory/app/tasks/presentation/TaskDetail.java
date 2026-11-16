package org.folio.factory.app.tasks.presentation;

import java.util.List;
import java.util.UUID;

/**
 * The task page: head, status card, the action it offers. repository says where the task is checked out and where
 * its pull request goes. status is the chip and headline of the attempt the task stands for, the same as on its
 * run page. reason and reasonNote are set when the latest attempt failed: the failure sentence and what the
 * attempt left behind; reasonUrl opens the limits when the attempt ran out of time or turns. spentTitle says why
 * the amount may be too low. clock is the time of the attempt that works now, null while none works. run opens the
 * latest run, at its PR feedback once there is a pull request. turn says whether the page waits for the developer.
 */
public record TaskDetail(UUID taskId, String key, String jiraUrl, String title, String repository,
                         long version, String inputFingerprint, String destinationFingerprint,
                         AttemptStatus status, String reason, String reasonNote, String reasonUrl,
                         List<String> draftReasons, String spent, String spentNote, String spentTitle,
                         AttemptClock clock, boolean retryAllowed, int nextAttempt, RunLink run, String prUrl,
                         String prAction, UUID markStoppedAttemptId, Turn turn) {

    private static final String PRIMARY = "btn btn-primary btn-sm";
    private static final String SECONDARY = "btn btn-secondary btn-sm";

    /** "Start run 2": the button and the heading of the next attempt. */
    public String startLabel() {
        return "Start run " + nextAttempt;
    }

    /** The pull request is the page's main button unless PR feedback waits on the run page. */
    public String prButtonClass() {
        return run != null && run.primary() ? SECONDARY : PRIMARY;
    }

    /**
     * The "Open run" button: the run page, at its PR feedback part once there is a pull request. It is the page's
     * main button when PR feedback waits for the developer there.
     */
    public record RunLink(String label, String url, boolean primary) {

        public String buttonClass() {
            return primary ? PRIMARY : SECONDARY;
        }
    }
}
