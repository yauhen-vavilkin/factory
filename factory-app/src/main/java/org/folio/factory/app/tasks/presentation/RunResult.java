package org.folio.factory.app.tasks.presentation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.folio.factory.app.tasks.persistence.TaskAttempt;

import java.util.Optional;

/**
 * How a run of the Statistics tab ended. It reads what {@link AttemptStatus#of} reads for the run, in the same
 * order, so the bucket always agrees with the run's chip in Runs of the task page.
 */
@Getter
@RequiredArgsConstructor
public enum RunResult {

    OPENED_PULL_REQUEST("Opened a pull request", true),
    FAILED("Failed", true),
    ASKED("Asked a question", true),
    IN_PROGRESS("In progress", false),
    NEEDS_INSPECTION("Needs inspection", false);

    private final String label;
    /** False for a bucket the tab shows only when a run is in it. */
    private final boolean alwaysShown;

    /** The bucket of {@code attempt}, one of the attempts of {@code view}; empty for a withdrawn run. */
    static Optional<RunResult> of(TaskView view, TaskView.AttemptHistoryEntry attempt) {
        if (opensPullRequest(attempt)) {
            return Optional.of(OPENED_PULL_REQUEST);
        }
        return switch (attempt.state()) {
            case RESERVED, QUEUED, RUNNING -> Optional.of(IN_PROGRESS);
            case STOPPED -> Optional.of(AttemptStatus.asks(view, attempt) ? ASKED : FAILED);
            case NEEDS_INSPECTION -> Optional.of(NEEDS_INSPECTION);
            case WITHDRAWN -> Optional.empty();
            case PR_READY -> Optional.of(OPENED_PULL_REQUEST);
        };
    }

    /** The run counts as one that opened a pull request: its chip shows the pull request. */
    static boolean opensPullRequest(TaskView.AttemptHistoryEntry attempt) {
        return AttemptStatus.showsPullRequest(attempt) || attempt.state() == TaskAttempt.State.PR_READY;
    }
}
