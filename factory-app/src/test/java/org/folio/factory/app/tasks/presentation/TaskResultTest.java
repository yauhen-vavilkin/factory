package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.folio.factory.app.tasks.presentation.AttemptCases.ASKED;
import static org.folio.factory.app.tasks.presentation.AttemptCases.FAILED;
import static org.folio.factory.app.tasks.presentation.AttemptCases.PULL_REQUEST;
import static org.folio.factory.app.tasks.presentation.AttemptCases.attempt;
import static org.folio.factory.app.tasks.presentation.AttemptCases.task;

/** Each bucket of a task agrees with the chip the task has on My tasks for the same case. */
class TaskResultTest {

    @Test
    void anOpenPullRequestIsOpenAlsoWhileItsRunStillWorks() {
        assertBucket(task(attempt(1, TaskAttempt.State.PR_READY, null, PULL_REQUEST)), FeedbackStanding.NONE,
                TaskResult.PULL_REQUEST_OPEN, "PR #6");
        assertBucket(task(attempt(1, TaskAttempt.State.RUNNING, null, PULL_REQUEST)), FeedbackStanding.WORKING,
                TaskResult.PULL_REQUEST_OPEN, "PR #6");
    }

    @Test
    void aPullRequestIsMergedOrClosedOnceItsFeedbackRunHasSeenIt() {
        var task = task(attempt(1, TaskAttempt.State.STOPPED, FAILED, null),
                attempt(2, TaskAttempt.State.PR_READY, null, PULL_REQUEST));

        assertBucket(task, FeedbackStanding.MERGED, TaskResult.MERGED, "Merged PR #6");
        assertBucket(task, FeedbackStanding.CLOSED, TaskResult.CLOSED, "Closed PR #6");
    }

    @Test
    void aQueuedOrWorkingRunWithoutAPullRequestIsInProgress() {
        assertBucket(task(attempt(1, TaskAttempt.State.RESERVED, null, null)), FeedbackStanding.NONE,
                TaskResult.IN_PROGRESS, "Queued");
        assertBucket(task(attempt(1, TaskAttempt.State.QUEUED, null, null)), FeedbackStanding.NONE,
                TaskResult.IN_PROGRESS, "In progress");
        // The run of the task is its third, which waits in line; the earlier runs failed.
        assertBucket(task(attempt(1, TaskAttempt.State.STOPPED, FAILED, null),
                        attempt(2, TaskAttempt.State.RESERVED, null, null)), FeedbackStanding.NONE,
                TaskResult.IN_PROGRESS, "Queued");
    }

    @Test
    void aRunFactoryCouldNotConfirmNeedsInspectionAlsoWithAPullRequest() {
        assertBucket(task(attempt(1, TaskAttempt.State.NEEDS_INSPECTION, null, PULL_REQUEST)), FeedbackStanding.NONE,
                TaskResult.NEEDS_INSPECTION, "Needs inspection");
    }

    @Test
    void everyOtherStopHasNoPullRequest() {
        assertBucket(task(attempt(1, TaskAttempt.State.STOPPED, FAILED, null)), FeedbackStanding.NONE,
                TaskResult.NO_PULL_REQUEST, "Failed");
        assertBucket(task(attempt(1, TaskAttempt.State.STOPPED, ASKED, null)), FeedbackStanding.NONE,
                TaskResult.NO_PULL_REQUEST, "Needs your answer");
        assertBucket(task(attempt(1, TaskAttempt.State.WITHDRAWN, null, null)), FeedbackStanding.NONE,
                TaskResult.NO_PULL_REQUEST, "Withdrawn");
    }

    @Test
    void theRunAfterAPullRequestThatWasWithdrawnLeavesTheTaskWithItsPullRequest() {
        assertBucket(task(attempt(1, TaskAttempt.State.PR_READY, null, PULL_REQUEST),
                        attempt(2, TaskAttempt.State.WITHDRAWN, null, null)), FeedbackStanding.MERGED,
                TaskResult.MERGED, "Merged PR #6");
    }

    private static void assertBucket(TaskView task, FeedbackStanding standing, TaskResult result, String chip) {
        var status = RunRows.row(task, standing);
        assertThat(status.chipLabel()).isEqualTo(chip);
        assertThat(TaskResult.of(task, standing)).isEqualTo(result);
    }
}
