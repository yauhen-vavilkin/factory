package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunContextTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID FIRST_RUN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_RUN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD_RUN = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String PR = "https://github.com/folio-org/mod-bar/pull/14";

    @Test
    void theLatestFailedAttemptOffersTheNextAttemptAndNamesItsFailure() {
        var view = task(TaskView.TaskState.STOPPED, "VERIFICATION_FAILED: 2 tests failed", true,
                attempt(1, FIRST_RUN, TaskAttempt.State.STOPPED, "VERIFICATION_FAILED: 2 tests failed", null));

        var run = RunContext.of(view, FIRST_RUN).orElseThrow();

        assertThat(run.crumb()).isEqualTo("Attempt 1");
        assertThat(run.pageTitle()).isEqualTo("MODBAR-1 · attempt 1");
        assertThat(run.settings()).isEqualTo("glm-5.3 · max");
        assertThat(run.chipLabel()).isEqualTo("Failed");
        assertThat(run.failure().headline()).isEqualTo("Attempt 1 failed");
        assertThat(run.failureNote()).isEqualTo("No pull request was created.");
        assertThat(run.retryLabel()).isEqualTo("Start attempt 2");
        assertThat(run.retryUrl()).isEqualTo("/tasks/" + TASK_ID + "#retry");
        assertThat(run.standing()).isNull();
        assertThat(run.inFlight()).isFalse();
    }

    @Test
    void anOlderAttemptLinksTheLatestAndSaysWhereItStands() {
        var view = task(TaskView.TaskState.PR_READY, null, false,
                attempt(1, FIRST_RUN, TaskAttempt.State.STOPPED, "DEVELOPMENT_FAILED: boom", null),
                attempt(2, SECOND_RUN, TaskAttempt.State.STOPPED, "DEVELOPMENT_FAILED: boom", null),
                attempt(3, THIRD_RUN, TaskAttempt.State.PR_READY, null, List.of("A reviewer should check it.")));

        var run = RunContext.of(view, FIRST_RUN).orElseThrow();

        assertThat(run.standing()).isEqualTo(new RunContext.Standing("Attempt 1 of 3. Latest: ", "attempt 3",
                "/executions/" + THIRD_RUN, ", Draft PR #14."));
        assertThat(run.retryLabel()).isNull();
        var latest = RunContext.of(view, THIRD_RUN).orElseThrow();
        assertThat(latest.chipLabel()).isEqualTo("Draft PR #14");
        assertThat(latest.chipUrl()).isEqualTo(PR);
        assertThat(latest.standing()).isNull();
    }

    @Test
    void aQuestionNeedsTheAnswerOnTheTaskPageOnlyWhileTheTaskWaitsForIt() {
        var question = "coding: " + TaskStops.DECISION_REASON + "Which client should change?";
        var asked = attempt(1, FIRST_RUN, TaskAttempt.State.STOPPED, question, null);
        var waiting = RunContext.of(task(TaskView.TaskState.STOPPED, question, true, asked), FIRST_RUN).orElseThrow();
        assertThat(waiting.chipLabel()).isEqualTo("Needs your answer");
        assertThat(waiting.answerUrl()).isEqualTo("/tasks/" + TASK_ID + "#answer");
        assertThat(waiting.failure()).isNull();
        assertThat(waiting.retryLabel()).isNull();

        var answered = RunContext.of(task(TaskView.TaskState.WORKING, null, false, asked,
                attempt(2, SECOND_RUN, TaskAttempt.State.RUNNING, null, null)), FIRST_RUN).orElseThrow();
        assertThat(answered.chipLabel()).isEqualTo("Asked a question");
        assertThat(answered.answerUrl()).isNull();
        assertThat(answered.standing().tail()).isEqualTo(", in progress.");
    }

    @Test
    void aRunOfNoAttemptOfTheTaskHasNoContext() {
        var view = task(TaskView.TaskState.WORKING, null, false,
                attempt(1, FIRST_RUN, TaskAttempt.State.RUNNING, null, null));
        assertThat(RunContext.of(view, SECOND_RUN)).isEmpty();
        assertThat(RunContext.of(view, FIRST_RUN).orElseThrow().inFlight()).isTrue();
    }

    private static TaskView task(TaskView.TaskState state, String reason, boolean retry,
                                 TaskView.AttemptHistoryEntry... attempts) {
        return new TaskView(TASK_ID, "MODBAR-1", "https://jira.example.org/browse/MODBAR-1", "Fix the bar",
                "MODBAR", "Open", state, null, reason, null, null, "mod-bar",
                state == TaskView.TaskState.PR_READY ? PR : null, null, true, false, 1, "input", "destination",
                List.of(attempts), usage(), new TaskView.AllowedActions(false, retry));
    }

    private static TaskView.AttemptHistoryEntry attempt(int ordinal, UUID executionId, TaskAttempt.State state,
                                                        String reason, List<String> draftReasons) {
        return new TaskView.AttemptHistoryEntry(UUID.randomUUID(), ordinal, TaskAttempt.Kind.INITIAL,
                TaskAttempt.AdmittedVia.MANUAL, state, null, reason,
                state == TaskAttempt.State.PR_READY ? PR : null, draftReasons, executionId,
                Instant.parse("2026-11-10T17:48:00Z"), Instant.parse("2026-11-10T17:48:00Z"), true, 600, usage(),
                "{\"profileId\":\"glm-5.3\",\"effort\":\"max\"}");
    }

    private static TaskUsage usage() {
        return new TaskUsage(0, Map.of(), new TreeMap<String, BigDecimal>(), true, false, List.of());
    }
}
