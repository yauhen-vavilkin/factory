package org.folio.factory.app.tasks.presentation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Locale;

/**
 * Where a task of the Statistics tab stands. It reads what {@link AttemptStatus#of} reads for the attempt the task
 * stands for, in the same order, so the bucket always agrees with the task's chip on My tasks.
 */
@Getter
@RequiredArgsConstructor
public enum TaskResult {

    MERGED("Merged", true),
    PULL_REQUEST_OPEN("Pull request open", true),
    CLOSED("Closed without merge", true),
    IN_PROGRESS("In progress", false),
    NEEDS_INSPECTION("Needs inspection", false),
    NO_PULL_REQUEST("No pull request", true);

    private final String label;
    /** False for a bucket the tab shows only when a task is in it. */
    private final boolean alwaysShown;

    /** The bucket of a task with at least one attempt; {@code standing} is where its PR feedback stands. */
    static TaskResult of(TaskView view, FeedbackStanding standing) {
        var attempt = view.standingAttempt().orElseThrow();
        if (AttemptStatus.showsPullRequest(attempt)) {
            return pullRequest(standing);
        }
        return switch (attempt.state()) {
            case RESERVED, QUEUED, RUNNING -> IN_PROGRESS;
            case NEEDS_INSPECTION -> NEEDS_INSPECTION;
            case PR_READY -> PULL_REQUEST_OPEN;
            case STOPPED, WITHDRAWN -> NO_PULL_REQUEST;
        };
    }

    /** The task reached a pull request, whether it is open, merged or closed. */
    public boolean reachedPullRequest() {
        return this == MERGED || this == PULL_REQUEST_OPEN || this == CLOSED;
    }

    /** "pull-request-open": the part of the class names of its bar and legend key. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static TaskResult pullRequest(FeedbackStanding standing) {
        return switch (standing) {
            case MERGED -> MERGED;
            case CLOSED -> CLOSED;
            default -> PULL_REQUEST_OPEN;
        };
    }
}
