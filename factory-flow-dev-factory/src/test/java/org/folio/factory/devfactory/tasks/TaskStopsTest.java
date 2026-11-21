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

    @Test void aFailedInstallInTheAgentsContainerSaysTheCauseThenYarnsErrorThenWhatToCheck() {
        var error = "Couldn't find package \"@folio/x@^1.0.0\" on the \"npm\" registry.";

        assertThat(TaskStops.codingInstallFailedReason(error))
                .isEqualTo("The dependency install in the agent's container failed, so the agent did not start."
                        + " Couldn't find package \"@folio/x@^1.0.0\" on the \"npm\" registry. Check that the"
                        + " agent's container can reach the npm registry and that its image has the Node version"
                        + " the module needs, then start a new run.")
                .startsWith(TaskStops.CODING_INSTALL_FAILED_START)
                .doesNotContain(TaskStops.SHARED_ENVIRONMENT_MARKER);
        // A PR feedback round may hold the change that breaks the install; it offers its own buttons, no new run.
        assertThat(TaskStops.feedbackInstallFailedCause("Dependency install failed with exit code 1"))
                .isEqualTo("The dependency install in the agent's container failed, so the agent did not start."
                        + " Dependency install failed with exit code 1. Fix package.json or yarn.lock on the pull"
                        + " request branch, or check that the agent's container can reach the npm registry.");
    }

    @Test void aLockfileThatCouldNotBePinnedSaysNothingWasSpentAndWhatToDo() {
        assertThat(TaskStops.lockfileNotPinnedReason("The generated yarn.lock is unusable: yarn.lock exceeds 4 MiB"))
                .isEqualTo("The build of the base branch made a yarn.lock that Factory could not use, so the agent"
                        + " did not start and nothing was spent. The generated yarn.lock is unusable: yarn.lock"
                        + " exceeds 4 MiB. Start a new run, and if it stops the same way, add a yarn.lock to the"
                        + " base branch.")
                .startsWith(TaskStops.LOCKFILE_NOT_PINNED_START)
                .doesNotContain(TaskStops.SHARED_ENVIRONMENT_MARKER);
    }

    @Test void checksWhoseInstallFailedOnTheAgentsChangesSayYarnsErrorAndTheTaskSaysWhatToTellTheAgent() {
        var error = "Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`.";

        assertThat(TaskStops.checksInstallFailedCause(error)).isEqualTo("the dependency install of the agent's"
                + " changes failed. Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`.");
        assertThat(TaskStops.checksInstallFailedReason(error)).isEqualTo("the dependency install of the agent's"
                + " changes failed. Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`."
                + " Start a new run and tell the agent to update yarn.lock with every change to package.json and"
                + " to use only packages that the npm registry has.");
    }

    @Test void otherStopReasonsDoNotAskForAnAnswer() {
        assertThat(TaskStops.asksForAnswer(null)).isFalse();
        assertThat(TaskStops.asksForAnswer("verification: VERIFICATION_FAILED: 2 tests failed")).isFalse();
        assertThat(TaskStops.asksForAnswer("verification: tests mention " + TaskStops.DECISION_REASON)).isFalse();
    }
}
