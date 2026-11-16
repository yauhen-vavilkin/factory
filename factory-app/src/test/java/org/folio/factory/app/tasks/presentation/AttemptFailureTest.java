package org.folio.factory.app.tasks.presentation;

import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptFailureTest {

    @Test
    void theHeadlineNamesTheAttemptAndTheReasonHasNoLayerPrefixes() {
        var failure = AttemptFailure.of(3, "shared: DEVELOPMENT_FAILED: Development stopped: docker exited with 125");

        assertThat(failure.headline()).isEqualTo("Run 3 failed");
        assertThat(failure.reason()).isEqualTo("Docker exited with 125.");
    }

    @Test
    void reasonsStoredByOlderRunsLoseTheirPrefixesAndSayTheKnownCauseInPlainWords() {
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: Implementation failed: Development stopped:"
                + " Command exceeded output limit")).isEqualTo(TaskStops.OUTPUT_LIMIT_REASON);
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: Claude Code did not finish within the time limit of"
                + " 30 minutes, so Factory stopped it. Retry with a lower effort or a smaller task, or raise the limit"
                + " (FACTORY_DEV_FACTORY_TIMEOUT_SECONDS, FACTORY_TASKS_EXECUTION_DEADLINE)."))
                .isEqualTo("The agent did not finish in 30 minutes, so Factory stopped it. Start a new run with"
                        + " a lower effort or narrower instructions, or raise the time limit.")
                .doesNotContain("FACTORY_");
        // The page links the limits under the reason, so a reason stored with "(see Configuration)" drops it.
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: The agent did not finish in 1 minute, so Factory"
                + " stopped it. Start a new run with a lower effort or narrower instructions, or raise the time"
                + " limit (see Configuration)."))
                .isEqualTo(TaskStops.timeLimitReason(1))
                .doesNotContain("see Configuration");
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: Claude Code reached the 200-turn limit before it"
                + " finished the task. Retry with another model or effort."))
                .isEqualTo(TaskStops.turnLimitReason(200));
    }

    @Test
    void oneTurnIsOneTurnAlsoInAReasonStoredBeforeTheGrammarFix() {
        assertThat(TaskStops.turnLimitReason(1)).startsWith("The agent reached its limit of 1 turn before it finished.");
        assertThat(TaskStops.turnLimitReason(2)).startsWith("The agent reached its limit of 2 turns before it finished.");
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: The agent reached its limit of 1 turns before it"
                + " finished. Start a new run with narrower instructions."))
                .isEqualTo(TaskStops.turnLimitReason(1));
    }

    @Test
    void reasonsFactoryStoresInItsOwnWordsNeedNoEngineWords() {
        assertThat(AttemptFailure.reason("engine execution ended in status FAILED_ESCALATED"))
                .isEqualTo("The run stopped before a step could say why. Open the run to see where it stopped,"
                        + " then start a new run.")
                .doesNotContain("engine", "execution", "FAILED");
        assertThat(AttemptFailure.reason("Marked as stopped by the operator; a pull request may already exist from"
                + " task/MODBAR-1 to folio-org/mod-bar"))
                .isEqualTo("You closed the run as failed; a pull request may already exist from task/MODBAR-1 to"
                        + " folio-org/mod-bar.");
    }

    @Test
    void anAttemptThatChangedNoFileSaysSoAlsoInAReasonStoredBeforeTheNewWords() {
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: " + TaskStops.NO_CHANGES_REASON))
                .isEqualTo("The agent finished without changing any file. Start a new run with clearer"
                        + " instructions.");
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: Implementation failed: Candidate contains no changes"))
                .isEqualTo(TaskStops.NO_CHANGES_REASON);
    }

    @Test
    void aReasonThatSaysTheAgentRanOutOfTimeOrTurnsLinksToTheLimits() {
        assertThat(AttemptFailure.limitsUrl(TaskStops.timeLimitReason(30))).isEqualTo("/developer/configuration#limits");
        assertThat(AttemptFailure.limitsUrl(AttemptFailure.reason("DEVELOPMENT_FAILED: Claude Code reached the"
                + " 200-turn limit before it finished the task."))).isEqualTo("/developer/configuration#limits");
        assertThat(AttemptFailure.limitsUrl(TaskStops.OUTPUT_LIMIT_REASON)).isNull();
        assertThat(AttemptFailure.limitsUrl(null)).isNull();
    }

    @Test
    void theReasonOfAQuestionIsTheQuestion() {
        assertThat(AttemptFailure.reason("clarify-implementation: " + TaskStops.DECISION_REASON
                + "Remove old routes too?")).isEqualTo("Remove old routes too?");
    }

    @Test
    void aStartingBuildFailureStoredByAnOlderRunSaysWhatToFix() {
        assertThat(AttemptFailure.reason("BLOCKED_ENVIRONMENT: Starting build failed before model spend"))
                .isEqualTo(TaskStops.startingBuildFailedReason(""))
                .startsWith("The build of the base branch failed before the agent started, so nothing was spent.")
                .contains("config/developer-repositories.yaml");
    }

    @Test
    void aReplayedCodingStepStoredByAnOlderRunLosesTheAttemptId() {
        assertThat(AttemptFailure.reason("implement: Paid coding was already started for task attempt"
                + " df739401-1111-2222-3333-444455556666; a replayed or retried engine step cannot start a second"
                + " initial coding invocation"))
                .isEqualTo(TaskStops.REPLAYED_CODING_REASON)
                .isEqualTo("Factory restarted or repeated this step while the agent was coding, so the work of this"
                        + " run is lost. Start a new run.");
        assertThat(AttemptFailure.reason("implement: " + TaskStops.REPLAYED_CODING_REASON))
                .isEqualTo(TaskStops.REPLAYED_CODING_REASON);
    }

    @Test
    void aReasonStoredWhileThePagesSaidAttemptReadsInTodaysWordsAndKeepsQuotedOutput() {
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: The agent finished without changing any file. Start a"
                + " new attempt with clearer instructions.")).isEqualTo(TaskStops.NO_CHANGES_REASON);
        var deadline = AttemptFailure.reason("verify: The attempt used all of its time before verification, so"
                + " Factory stopped it. Start a new attempt with a lower effort or narrower instructions, or raise"
                + " the time limit of the whole attempt.");

        assertThat(deadline).isEqualTo(TaskStops.deadlineReason("verification"));
        assertThat(AttemptFailure.limitsUrl(deadline)).isNotNull();
        assertThat(AttemptFailure.reason("verify: the build said: download attempt 2 of 3 failed"))
                .contains("download attempt 2 of 3 failed");
        assertThat(AttemptFailure.reason("clarify-implementation: " + TaskStops.DECISION_REASON
                + "Start a new attempt or keep the branch?")).isEqualTo("Start a new attempt or keep the branch?");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "The agent finished without changing any file. Start a new attempt with clearer instructions."
            + " | Start a new run with clearer instructions.",
        "A command printed more output than Factory accepts, so Factory stopped the attempt. Start a new attempt."
            + " | so Factory stopped the run. Start a new run.",
        "Factory could not process the Claude Code output (IOException), so it stopped the attempt. Retry the task."
            + " | so it stopped the run. Retry the task.",
        "Factory restarted or repeated this step while the agent was coding, so the work of this attempt is lost."
            + " | so the work of this run is lost.",
        "The attempt used all of its time before coding, so Factory stopped it. Raise the time limit of the whole"
            + " attempt. | The run used all of its time before coding",
        "The agent ran too long. Lower the effort, or raise the time limit of the whole attempt."
            + " | or raise the time limit of the whole run.",
        "Claude Code did not complete the attempt (EXIT_2) | Claude Code did not complete the run (EXIT_2).",
        "the task attempt no longer owns the installation's active slot; a stopped, superseded or released attempt"
            + " must not continue | The run no longer owns the installation's active slot; a stopped, superseded or"
            + " released run must not continue."})
    void everyOldPhraseOfFactoryReadsInTodaysWords(String stored, String today) {
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: " + stored)).contains(today).doesNotContain("attempt");
    }

    @Test
    void aWholeAttemptDeadlineSaysTheAttemptUsedItsTimeAndLinksTheLimits() {
        var reason = AttemptFailure.reason("DEADLINE_EXCEEDED: the whole-attempt deadline expired before"
                + " verification; queue and human-review waiting are excluded from the budget");

        assertThat(reason).isEqualTo(TaskStops.deadlineReason("verification"))
                .isEqualTo("The run used all of its time before verification, so Factory stopped it. Start a new"
                        + " run with a lower effort or narrower instructions, or raise the time limit of the whole"
                        + " run.");
        assertThat(AttemptFailure.limitsUrl(reason)).isEqualTo("/developer/configuration#limits");
        assertThat(AttemptFailure.reason("DEADLINE_EXCEEDED: " + TaskStops.deadlineReason("coding")))
                .isEqualTo(TaskStops.deadlineReason("coding"));
    }

    @Test
    void aWholeAttemptDeadlineThatTheGuardStoredAtVerifyOrPublishNeedsNoStageInFront() {
        // The task guard stores "<step id>: <reason>", and the step ids are verify and publish.
        assertDeadline("verify: " + TaskStops.deadlineReason("verification"), "verification");
        assertDeadline("publish: " + TaskStops.deadlineReason("publication"), "publication");
        assertDeadline("verify: the whole-attempt deadline expired before verification; queue and human-review"
                + " waiting are excluded from the budget", "verification");
        assertDeadline("publish: the whole-attempt deadline expired before publication; queue and human-review"
                + " waiting are excluded from the budget", "publication");
    }

    private static void assertDeadline(String stored, String stage) {
        var reason = AttemptFailure.reason(stored);

        assertThat(reason).isEqualTo(TaskStops.deadlineReason(stage)).doesNotContain("failed:");
        assertThat(AttemptFailure.limitsUrl(reason)).isEqualTo("/developer/configuration#limits");
    }

    @Test
    void failedChecksSayWhetherTheyFailedOrCouldNotRun() {
        assertThat(AttemptFailure.reason("VERIFICATION_FAILED: 2 of 300 tests failed"))
                .isEqualTo("Checks failed: 2 of 300 tests failed.");
        assertThat(AttemptFailure.reason("VERIFICATION_NOT_RUN: no test reports were written"))
                .isEqualTo("Checks could not run: no test reports were written.");
    }

    @Test
    void aStageOtherThanImplementationStaysInTheReason() {
        assertThat(AttemptFailure.reason("verification: 2 tests in error"))
                .isEqualTo("Verification failed: 2 tests in error.");
        assertThat(AttemptFailure.reason("DELIVERY_BLOCKED: GitHub said no"))
                .isEqualTo("Publication failed: GitHub said no.");
        assertThat(AttemptFailure.firstSentence(AttemptFailure.reason(
                "DELIVERY_BLOCKED: " + DeliveryWorker.pullRequestRefusedReason(403))))
                .isEqualTo("Publication failed: Factory pushed the branch, but GitHub refused to open the pull request"
                        + " (HTTP 403).");
        assertThat(AttemptFailure.reason("VERIFIED: the change has no diff")).isEqualTo("The change has no diff.");
    }

    @Test
    void anUnknownReasonPassesThroughAsASentence() {
        assertThat(AttemptFailure.reason("Docker said: Error: boom"))
                .isEqualTo("Docker said: Error: boom.");
        assertThat(AttemptFailure.reason("Done!")).isEqualTo("Done!");
        assertThat(AttemptFailure.reason(null)).isEqualTo("No reason was recorded.");
    }

    @Test
    void theFirstSentenceIsWhatARowWithOneLineShows() {
        assertThat(AttemptFailure.firstSentence(TaskStops.timeLimitReason(30)))
                .isEqualTo("The agent did not finish in 30 minutes, so Factory stopped it.");
        assertThat(AttemptFailure.firstSentence("next in line")).isEqualTo("next in line");
    }
}
