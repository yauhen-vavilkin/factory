package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskAttempt;

/**
 * The chip and the headline of one attempt, the same on the task page, the run page, the Attempts rows and the
 * Runs rows of My tasks. A pull request the run opened wins over the attempt record, which the dispatcher closes
 * a little later, so the pages never go back to "In progress" while the pull request is open; only an attempt that
 * needs inspection keeps its own chip. {@code chipUrl} opens the pull request and is null for any other chip.
 */
public record AttemptStatus(String chipLabel, String chipClass, String chipUrl, String headline) {

    public static final String STARTING = "Starting the run";
    private static final String NEEDS_INSPECTION = "Needs inspection";

    /**
     * The status of the attempt the task stands for: its pull request reads as merged or closed once the run of
     * its PR feedback has seen that.
     */
    static AttemptStatus of(TaskView view, TaskView.AttemptHistoryEntry attempt, FeedbackStanding standing) {
        var url = attempt.pullRequestUrl();
        if (url == null || standing != FeedbackStanding.MERGED && standing != FeedbackStanding.CLOSED) {
            return of(view, attempt);
        }
        var merged = standing == FeedbackStanding.MERGED;
        var number = TaskLabels.shortPullRequestLabel(url, false);
        return new AttemptStatus((merged ? "Merged " : "Closed ") + number,
                merged ? "chip chip-success" : "chip chip-neutral", url,
                TaskLabels.pullRequestLabel(url) + (merged ? " is merged" : " was closed without merge"));
    }

    /** The status of {@code attempt}, one of the attempts of {@code view}. */
    public static AttemptStatus of(TaskView view, TaskView.AttemptHistoryEntry attempt) {
        if (attempt.pullRequestUrl() != null && attempt.state() != TaskAttempt.State.NEEDS_INSPECTION) {
            return pullRequest(attempt);
        }
        return switch (attempt.state()) {
            case RESERVED -> neutral("Queued", queuePlace(view, attempt));
            case QUEUED, RUNNING -> new AttemptStatus("In progress", "chip chip-info", null,
                    TaskLabels.workingHeadline(attempt.stage()));
            case STOPPED -> stopped(view, attempt);
            case NEEDS_INSPECTION -> new AttemptStatus(NEEDS_INSPECTION, "chip chip-warning", null,
                    "Factory could not confirm how the run ended");
            case WITHDRAWN -> neutral("Withdrawn", "Withdrawn before it started");
            case PR_READY -> pullRequest(attempt);
        };
    }

    /** Factory could not confirm how the run of this attempt ended. */
    public boolean needsInspection() {
        return NEEDS_INSPECTION.equals(chipLabel);
    }

    private static AttemptStatus pullRequest(TaskView.AttemptHistoryEntry attempt) {
        var draft = attempt.draftReasons() != null;
        var headline = TaskLabels.pullRequestLabel(attempt.pullRequestUrl())
                + (draft ? " is open as a draft" : " is ready for review");
        return new AttemptStatus(TaskLabels.shortPullRequestLabel(attempt.pullRequestUrl(), draft),
                draft ? "chip chip-neutral" : "chip chip-success", attempt.pullRequestUrl(), headline);
    }

    private static AttemptStatus stopped(TaskView view, TaskView.AttemptHistoryEntry attempt) {
        var waits = attempt == view.attempts().getLast() && view.needsAnswer();
        if (!attempt.asked() && !waits) {
            return new AttemptStatus("Failed", "chip chip-danger", null, "Attempt " + attempt.ordinal() + " failed");
        }
        return new AttemptStatus(waits ? TaskLabels.NEEDS_ANSWER : "Asked a question", "chip chip-warning", null,
                "The agent stopped with a question");
    }

    /** "Next in line" or "3rd in line" for an attempt that waits for the dispatcher. */
    private static String queuePlace(TaskView view, TaskView.AttemptHistoryEntry attempt) {
        var place = attempt == view.attempts().getLast() ? view.queuePosition() : null;
        return place == null || place == 1 ? "Next in line" : TaskLabels.ordinal(place) + " in line";
    }

    private static AttemptStatus neutral(String label, String headline) {
        return new AttemptStatus(label, "chip chip-neutral", null, headline);
    }
}
