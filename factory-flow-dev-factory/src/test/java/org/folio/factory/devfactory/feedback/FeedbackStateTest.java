package org.folio.factory.devfactory.feedback;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.JSON;

class FeedbackStateTest {
    @Test
    void historicalFieldsAndDefaultsSurviveRoundTrip() {
        var historical = JSON.readTree("""
                {"phase":"QUESTION","round":2,"attemptId":"old-attempt",
                 "question":"Which behavior?","options":["A","B"],"selected":[],
                 "usage":{"inputTokens":10},"rate":{"currency":"USD"}}
                """);
        var state = JSON.treeToValue(historical, FeedbackState.class);
        var saved = JSON.valueToTree(state);
        historical.properties().forEach(entry -> assertThat(saved.get(entry.getKey())).isEqualTo(entry.getValue()));
        assertThat(state.completionReason()).isEmpty();
        assertThat(state.cleanupSteps()).isEmpty();
        assertThat(state.waiting()).isTrue();
        assertThat(JSON.treeToValue(saved, FeedbackState.class).phase()).isEqualTo(FeedbackState.Phase.QUESTION);
    }

    @Test
    void invalidOperationCannotAdvanceState() {
        var state = new FeedbackState();
        assertThatThrownBy(state::prepared).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(state::publishPrepared).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> state.apply(FeedbackActions.Action.ANSWER, null, "", null, "answer"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state.phase()).isEqualTo(FeedbackState.Phase.INIT);
        assertThat(state.attemptId()).isEmpty();
    }

    @Test
    void cleanupOfInterruptedCodeRequiresAnExplicitNewAttempt() {
        var state = JSON.readValue("{\"phase\":\"CODE\",\"attemptId\":\"original\"}", FeedbackState.class);
        state.cleanupBlocked(FeedbackState.Phase.INTERRUPTED, "cleanup uncertain");
        assertThat(FeedbackActions.allowed(state)).containsExactly(FeedbackActions.Action.RETRY_CLEANUP);
        state.cleaned("recovery");
        assertThat(state.phase()).isEqualTo(FeedbackState.Phase.INTERRUPTED);
        assertThat(state.attemptId()).isEqualTo("original");
        state.apply(FeedbackActions.Action.RETRY_CODE, null, "", null, "");
        assertThat(state.phase()).isEqualTo(FeedbackState.Phase.CODE);
        assertThat(state.attemptId()).isNotEqualTo("original");
    }
}
