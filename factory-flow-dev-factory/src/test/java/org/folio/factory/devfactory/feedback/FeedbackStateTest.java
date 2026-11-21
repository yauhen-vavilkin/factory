package org.folio.factory.devfactory.feedback;

import org.folio.factory.devfactory.verification.VerificationFailure;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;

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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "CANDIDATE|1|300|2|0|Checks failed: some tests failed.",
            "UNKNOWN|1|10|1|1|Checks failed: some tests failed.",
            "CANDIDATE_COMPILE|0||||Checks failed: the code does not compile.",
            "ENVIRONMENT|0||||Checks could not run: the build environment failed (Docker, network, disk space or"
                    + " time limit).",
            "INVALID_EVIDENCE|0||||Checks could not run: the test reports could not be read.",
            "INSUFFICIENT_EVIDENCE|0||||Checks could not run: no test reports were written.",
            "UNKNOWN|0||||Checks failed: the build failed with exit code 1."})
    void failedChecksSayTheirCauseInWordsWithoutTheCountTheRoundShows(VerificationFailure kind, int reports,
                                                                      Integer tests, Integer failures, Integer errors,
                                                                      String reason) {
        var state = JSON.readValue("{\"phase\":\"VERIFY\"}", FeedbackState.class);
        var receipt = failedReceipt(kind, reports, tests, failures, errors);

        state.verified(receipt);

        assertThat(state.phase()).isEqualTo(FeedbackState.Phase.FAILURE);
        assertThat(state.failureKind()).isEqualTo(kind.name());
        assertThat(state.reason()).isEqualTo(reason);
        assertThat(FeedbackActions.allowed(state)).contains(FeedbackActions.Action.RETRY_CODE);
        // The words are not stored with the receipt.
        assertThat(JSON.valueToTree(receipt).has("failureReason")).isFalse();
    }

    private static VerificationReceipt failedReceipt(VerificationFailure kind, int reports, Integer tests,
                                                     Integer failures, Integer errors) {
        var now = Instant.now();
        return new VerificationReceipt("execution", "repo", "a".repeat(40), "b".repeat(40), "c".repeat(64), "plan",
                "image", List.of("mvn", "test"), "checks", now, now, 1, reports, tests, failures, errors, "FAIL",
                "output", kind, 0, List.of(), List.of(), null, null);
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
