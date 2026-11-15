package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.execution.StopReasons;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class StopCauseTest {

    private static final TaskAttempt.State STOPPED = TaskAttempt.State.STOPPED;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theLimitsOfTheAgentAreTimeAndTurnsAlsoInTheWordsOfOlderRuns() {
        assertThat(StopCause.of(STOPPED, "DEVELOPMENT_FAILED: " + TaskStops.timeLimitReason(60)))
                .isEqualTo(StopCause.TIME_LIMIT);
        assertThat(StopCause.of(STOPPED, "verify: " + TaskStops.deadlineReason("verification")))
                .isEqualTo(StopCause.TIME_LIMIT);
        assertThat(StopCause.of(STOPPED, "DEVELOPMENT_FAILED: Claude Code did not finish within the time limit of"
                + " 30 minutes, so Factory stopped it.")).isEqualTo(StopCause.TIME_LIMIT);
        assertThat(StopCause.of(STOPPED, "DEVELOPMENT_FAILED: " + TaskStops.turnLimitReason(400)))
                .isEqualTo(StopCause.TURN_LIMIT);
        assertThat(StopCause.of(STOPPED, "DEVELOPMENT_FAILED: Claude Code reached the 200-turn limit before it"
                + " finished the task.")).isEqualTo(StopCause.TURN_LIMIT);
    }

    @Test
    void aQuestionNoChangesAndFailedChecksHaveTheirOwnCause() {
        assertThat(StopCause.of(STOPPED, "clarify-implementation: " + TaskStops.DECISION_REASON
                + "Remove the old routes too?")).isEqualTo(StopCause.ASKED);
        assertThat(StopCause.of(STOPPED, "DEVELOPMENT_FAILED: " + TaskStops.NO_CHANGES_REASON))
                .isEqualTo(StopCause.NO_CHANGES);
        assertThat(StopCause.of(STOPPED, "DEVELOPMENT_FAILED: Candidate contains no changes"))
                .isEqualTo(StopCause.NO_CHANGES);
        assertThat(StopCause.of(STOPPED, checks("\"testCount\":300,\"failureCount\":2,\"errorCount\":0")))
                .isEqualTo(StopCause.CHECKS_FAILED);
        assertThat(StopCause.of(STOPPED, checks("\"failureKind\":\"CANDIDATE_COMPILE\",\"exitCode\":1")))
                .isEqualTo(StopCause.CHECKS_FAILED);
    }

    /** Checks that could not run did not judge the code, so they count as the environment, not as failed checks. */
    @Test
    void whatFactoryOrTheEnvironmentStoppedIsOneCause() {
        assertThat(StopCause.of(STOPPED, checks("\"failureKind\":\"ENVIRONMENT\",\"exitCode\":-1")))
                .isEqualTo(StopCause.FACTORY_OR_ENVIRONMENT);
        assertThat(StopCause.of(STOPPED, "BLOCKED_ENVIRONMENT: " + TaskStops.startingBuildFailedReason("")))
                .isEqualTo(StopCause.FACTORY_OR_ENVIRONMENT);
        assertThat(StopCause.of(STOPPED, "BLOCKED_ENVIRONMENT: Model glm-9 no longer allows effort max."))
                .isEqualTo(StopCause.FACTORY_OR_ENVIRONMENT);
        assertThat(StopCause.of(STOPPED, "implement: " + TaskStops.REPLAYED_CODING_REASON))
                .isEqualTo(StopCause.FACTORY_OR_ENVIRONMENT);
        assertThat(StopCause.of(STOPPED, "shared: DEVELOPMENT_FAILED: The model endpoint answered 401."))
                .isEqualTo(StopCause.FACTORY_OR_ENVIRONMENT);
        assertThat(StopCause.of(STOPPED, StopReasons.engineGaveUp("publish", 3, "429 Too Many Requests")))
                .isEqualTo(StopCause.FACTORY_OR_ENVIRONMENT);
        assertThat(StopCause.of(STOPPED, TaskOutcomeService.RUN_ENDED + "COMPLETED"))
                .isEqualTo(StopCause.FACTORY_OR_ENVIRONMENT);
        assertThat(StopCause.of(STOPPED, "DELIVERY_BLOCKED: " + DeliveryWorker.pullRequestRefusedReason(403)))
                .isEqualTo(StopCause.FACTORY_OR_ENVIRONMENT);
        assertThat(StopCause.of(TaskAttempt.State.NEEDS_INSPECTION, "workload inactivity could not be confirmed"))
                .isEqualTo(StopCause.FACTORY_OR_ENVIRONMENT);
    }

    /** A stop the person made, a refused patch and a command that printed too much are the agent's or yours. */
    @Test
    void everyOtherStopIsOther() {
        assertThat(StopCause.of(STOPPED, TaskOutcomeService.MARKED_STOPPED + " at 10:00.")).isEqualTo(StopCause.OTHER);
        assertThat(StopCause.of(STOPPED, "DEVELOPMENT_FAILED: " + TaskStops.secretInChangesReason("a.yml")))
                .isEqualTo(StopCause.OTHER);
        assertThat(StopCause.of(STOPPED, "DEVELOPMENT_FAILED: " + TaskStops.OUTPUT_LIMIT_REASON))
                .isEqualTo(StopCause.OTHER);
        assertThat(StopCause.of(STOPPED, "DEVELOPMENT_FAILED: The agent stopped with an error."))
                .isEqualTo(StopCause.OTHER);
        assertThat(StopCause.of(STOPPED, null)).isEqualTo(StopCause.OTHER);
    }

    @Test
    void eachCauseHasThePlansWords() {
        assertThat(StopCause.values()).extracting(StopCause::getLabel).containsExactly("Time limit", "Turn limit",
                "Asked a question", "No changes", "Checks failed", "Stopped by Factory or the environment", "Other");
    }

    private static String checks(String fields) {
        return StopReasons.ofResult(JSON.readTree("{\"state\":\"VERIFICATION_FAILED\"," + fields + "}"));
    }
}
