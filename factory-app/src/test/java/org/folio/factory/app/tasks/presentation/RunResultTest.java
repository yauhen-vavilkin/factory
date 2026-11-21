package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.folio.factory.app.tasks.presentation.AttemptCases.ASKED;
import static org.folio.factory.app.tasks.presentation.AttemptCases.FAILED;
import static org.folio.factory.app.tasks.presentation.AttemptCases.PULL_REQUEST;
import static org.folio.factory.app.tasks.presentation.AttemptCases.attempt;
import static org.folio.factory.app.tasks.presentation.AttemptCases.task;

/** Each bucket of a run agrees with the chip of its row in Runs of the task page for the same case. */
class RunResultTest {

    @Test
    void aRunThatOpenedAPullRequestCountsSoAlsoWhileItStillWorks() {
        assertBucket(attempt(1, TaskAttempt.State.PR_READY, null, PULL_REQUEST), RunResult.OPENED_PULL_REQUEST,
                "PR #6");
        assertBucket(attempt(1, TaskAttempt.State.RUNNING, null, PULL_REQUEST), RunResult.OPENED_PULL_REQUEST,
                "PR #6");
    }

    @Test
    void aStoppedRunFailedOrAskedAQuestion() {
        assertBucket(attempt(1, TaskAttempt.State.STOPPED, FAILED, null), RunResult.FAILED, "Failed");
        assertBucket(attempt(1, TaskAttempt.State.STOPPED, ASKED, null), RunResult.ASKED, "Needs your answer");
    }

    @Test
    void anAnsweredQuestionStillCountsAsAQuestion() {
        var asked = attempt(1, TaskAttempt.State.STOPPED, ASKED, null);
        var task = task(asked, attempt(2, TaskAttempt.State.STOPPED, FAILED, null));

        assertThat(AttemptStatus.of(task, asked).chipLabel()).isEqualTo("Asked a question");
        assertThat(RunResult.of(task, asked)).contains(RunResult.ASKED);
    }

    @Test
    void aQueuedOrWorkingRunIsInProgress() {
        assertBucket(attempt(1, TaskAttempt.State.RESERVED, null, null), RunResult.IN_PROGRESS, "Queued");
        assertBucket(attempt(1, TaskAttempt.State.RUNNING, null, null), RunResult.IN_PROGRESS, "In progress");
    }

    @Test
    void aRunFactoryCouldNotConfirmNeedsInspectionAlsoWithAPullRequest() {
        var inspected = attempt(1, TaskAttempt.State.NEEDS_INSPECTION, null, PULL_REQUEST);

        assertBucket(inspected, RunResult.NEEDS_INSPECTION, "Needs inspection");
        // The model table counts the pull requests the same way, so its column agrees with the Runs figure.
        assertThat(RunResult.opensPullRequest(inspected)).isFalse();
        assertThat(RunResult.opensPullRequest(attempt(1, TaskAttempt.State.PR_READY, null, null))).isTrue();
    }

    @Test
    void aWithdrawnRunIsNotCounted() {
        var withdrawn = attempt(1, TaskAttempt.State.WITHDRAWN, null, null);

        assertThat(AttemptStatus.of(task(withdrawn), withdrawn).chipLabel()).isEqualTo("Withdrawn");
        assertThat(RunResult.of(task(withdrawn), withdrawn)).isEqualTo(Optional.empty());
    }

    private static void assertBucket(TaskView.AttemptHistoryEntry attempt, RunResult result, String chip) {
        var task = task(attempt);
        assertThat(AttemptStatus.of(task, attempt).chipLabel()).isEqualTo(chip);
        assertThat(RunResult.of(task, attempt)).contains(result);
    }
}
