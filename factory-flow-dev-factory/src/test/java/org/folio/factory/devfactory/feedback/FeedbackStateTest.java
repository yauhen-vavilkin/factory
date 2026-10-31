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
    void savedStateIsWrittenBackByteForByte() {
        // Written by the code before FeedbackState used Lombok getters.
        var saved = """
                {"answer":"Use A","attemptId":"attempt-3","cleanupResumePhase":"VERIFY","cleanupSteps":["pr-feedback-1"],"commitSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","completionReason":"MERGED","failureKind":"CANDIDATE","finishedAt":"2026-10-30T10:30:00Z","lastCleanedInvocation":"inv-1","manual":true,"options":["A","B"],"phase":"FAILURE","pr":{"repository":"fork/repo","number":7,"branch":"factory/task","baseBranch":"main","headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","url":"https://github.com/fork/repo/pull/7","open":true},"question":"Which behavior?","rate":{"currency":"USD"},"reason":"Checks failed","resumePhase":"CODE","round":3,"roundStartedAt":"2026-10-30T10:05:00Z","runtimeName":"claude-code","selected":[{"id":"inline:1","author":"reviewer","body":"Correct value","path":"value.txt","line":1,"outdated":false,"updatedAt":"now","url":"url"}],"snapshotId":"snapshot-1","startedAt":"2026-10-30T10:00:00Z","summary":"Verified changes","taskSummary":"Task summary","usage":{"inputTokens":10,"outputTokens":3},"verification":null}""";

        assertThat(JSON.writeValueAsString(JSON.readValue(saved, FeedbackState.class))).isEqualTo(saved);
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
