package org.folio.factory.devfactory.tasks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStopsTest {

    @Test void aStopReasonWithTheAgentsQuestionAsksForAnAnswer() {
        assertThat(TaskStops.asksForAnswer("clarify-implementation: " + TaskStops.DECISION_REASON + "Which API?"))
                .isTrue();
    }

    @Test void otherStopReasonsDoNotAskForAnAnswer() {
        assertThat(TaskStops.asksForAnswer(null)).isFalse();
        assertThat(TaskStops.asksForAnswer("verification: VERIFICATION_FAILED: 2 tests failed")).isFalse();
        assertThat(TaskStops.asksForAnswer("verification: tests mention " + TaskStops.DECISION_REASON)).isFalse();
    }
}
