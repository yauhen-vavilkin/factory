package org.folio.factory.app.tasks.presentation;

/** The one action a row under Runs offers, by the state of its latest attempt. */
public enum RunAction {
    OPEN_RUN("Open run"),
    PR_FEEDBACK("PR feedback"),
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
}
