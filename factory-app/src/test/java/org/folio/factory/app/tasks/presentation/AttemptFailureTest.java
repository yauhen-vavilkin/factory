package org.folio.factory.app.tasks.presentation;

import org.folio.factory.devfactory.tasks.TaskStops;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptFailureTest {

    @Test
    void theHeadlineNamesTheAttemptAndTheReasonHasNoLayerPrefixes() {
        var failure = AttemptFailure.of(3, "shared: DEVELOPMENT_FAILED: Development stopped: docker exited with 125");

        assertThat(failure.headline()).isEqualTo("Attempt 3 failed");
        assertThat(failure.reason()).isEqualTo("Docker exited with 125.");
    }

    @Test
    void reasonsStoredByOlderRunsLoseTheirPrefixesAndSayTheKnownCauseInPlainWords() {
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: Implementation failed: Development stopped:"
                + " Command exceeded output limit")).isEqualTo(TaskStops.OUTPUT_LIMIT_REASON);
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: Claude Code did not finish within the time limit of"
                + " 30 minutes, so Factory stopped it. Retry with a lower effort or a smaller task, or raise the limit"
                + " (FACTORY_DEV_FACTORY_TIMEOUT_SECONDS, FACTORY_TASKS_EXECUTION_DEADLINE)."))
                .isEqualTo("The agent did not finish in 30 minutes, so Factory stopped it. Start a new attempt with"
                        + " a lower effort or narrower instructions, or raise the time limit (see Configuration).")
                .doesNotContain("FACTORY_");
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: Claude Code reached the 200-turn limit before it"
                + " finished the task. Retry with another model or effort."))
                .isEqualTo(TaskStops.turnLimitReason(200));
    }

    @Test
    void oneTurnIsOneTurnAlsoInAReasonStoredBeforeTheGrammarFix() {
        assertThat(TaskStops.turnLimitReason(1)).startsWith("The agent reached its limit of 1 turn before it finished.");
        assertThat(TaskStops.turnLimitReason(2)).startsWith("The agent reached its limit of 2 turns before it finished.");
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: The agent reached its limit of 1 turns before it"
                + " finished. Start a new attempt with narrower instructions."))
                .isEqualTo(TaskStops.turnLimitReason(1));
    }

    @Test
    void reasonsFactoryStoresInItsOwnWordsNeedNoEngineWords() {
        assertThat(AttemptFailure.reason("engine execution ended in status FAILED_ESCALATED"))
                .isEqualTo("The run stopped before a step could say why. Open the run to see where it stopped,"
                        + " then start a new attempt.")
                .doesNotContain("engine", "execution", "FAILED");
        assertThat(AttemptFailure.reason("Marked as stopped by the operator; a pull request may already exist from"
                + " task/MODBAR-1 to folio-org/mod-bar"))
                .isEqualTo("You marked the attempt as stopped; a pull request may already exist from task/MODBAR-1 to"
                        + " folio-org/mod-bar.");
    }

    @Test
    void anAttemptThatChangedNoFileSaysSoAlsoInAReasonStoredBeforeTheNewWords() {
        assertThat(AttemptFailure.reason("DEVELOPMENT_FAILED: " + TaskStops.NO_CHANGES_REASON))
                .isEqualTo("The agent finished without changing any file. Start a new attempt with clearer"
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
    void aStageOtherThanImplementationStaysInTheReason() {
        assertThat(AttemptFailure.reason("VERIFICATION_FAILED: 2 tests in error"))
                .isEqualTo("Verification failed: 2 tests in error.");
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
