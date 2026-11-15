package org.folio.factory.devfactory.tasks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStopsTest {

    @Test void aStopReasonWithTheAgentsQuestionAsksForAnAnswer() {
        assertThat(TaskStops.asksForAnswer("clarify-implementation: " + TaskStops.DECISION_REASON + "Which API?"))
                .isTrue();
    }

    @Test void aFailedStartingBuildSaysTheCauseFirstThenWhatFactoryFoundThenWhatToFix() {
        assertThat(TaskStops.startingBuildFailedReason("Dependency resolution failed: could not transfer Saxon-HE"))
                .isEqualTo("The build of the base branch failed before the agent started, so nothing was spent."
                        + " Dependency resolution failed: could not transfer Saxon-HE. Open the run to see the build"
                        + " output, then fix the base branch or the check command in"
                        + " config/developer-repositories.yaml.")
                .doesNotContain(TaskStops.SHARED_ENVIRONMENT_MARKER);
    }

    @Test void otherStopReasonsDoNotAskForAnAnswer() {
        assertThat(TaskStops.asksForAnswer(null)).isFalse();
        assertThat(TaskStops.asksForAnswer("verification: VERIFICATION_FAILED: 2 tests failed")).isFalse();
        assertThat(TaskStops.asksForAnswer("verification: tests mention " + TaskStops.DECISION_REASON)).isFalse();
    }
}
