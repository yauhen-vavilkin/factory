package org.folio.factory.app.tasks.presentation;

/**
 * The one action a row under Started tasks offers, by the state of its latest attempt. PR_FEEDBACK and PR_WAITING
 * open the run at its PR feedback part; PR_WAITING says that new PR comments or a question wait there.
 */
public enum RunAction {
    OPEN_RUN("Open run"),
    PR_FEEDBACK("Open run"),
    PR_WAITING("Open run"),
    ANSWER("Answer"),
    RETRY("Retry"),
    MARK_STOPPED("Mark as stopped…"),
    NONE("");

    private final String label;

    RunAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The row waits for the developer: an answer, the next attempt, PR feedback, or a look at a run that
     * did not end. */
    public boolean yours() {
        return this == ANSWER || this == RETRY || this == MARK_STOPPED || this == PR_WAITING;
    }
}
