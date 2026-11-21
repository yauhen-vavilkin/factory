package org.folio.factory.app.tasks.presentation;

/**
 * The one action a row under Started tasks offers, by the state of its latest attempt. PR_FEEDBACK and PR_WAITING
 * open the run at its PR feedback part; PR_WAITING says that new PR comments or a question wait there.
 */
public enum RunAction {
    OPEN_RUN,
    PR_FEEDBACK,
    PR_WAITING,
    ANSWER,
    RETRY,
    MARK_STOPPED,
    NONE;

    /** The row waits for the developer: an answer, the next attempt, PR feedback, or a look at a run that
     * did not end. */
    public boolean yours() {
        return this == ANSWER || this == RETRY || this == MARK_STOPPED || this == PR_WAITING;
    }
}
