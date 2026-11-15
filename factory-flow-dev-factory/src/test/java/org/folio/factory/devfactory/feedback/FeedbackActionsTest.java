package org.folio.factory.devfactory.feedback;
import org.folio.factory.devfactory.verification.VerificationFailure;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.folio.factory.devfactory.feedback.FeedbackActions.Action.*;
class FeedbackActionsTest {
    @Test void everyFailedCheckOffersTheAgentFixAndTheChecksAgain() {
        var state=FeedbackArtifacts.JSON.readValue("{\"phase\":\"FAILURE\"}", FeedbackState.class);
        for(var kind:VerificationFailure.values()) {
            if(kind==VerificationFailure.NONE) continue;
            state.failureKind=kind.name();
            assertThat(FeedbackActions.allowed(state)).as(kind.name()).contains(RETRY_CODE,RETRY_CHECKS).doesNotContain(FINISH);
        }
        // Commit preparation and Factory's own operations are not the agent's to fix.
        state.failureKind="VERIFICATION";
        assertThat(FeedbackActions.allowed(state)).contains(RETRY_CHECKS).doesNotContain(RETRY_CODE);
        state.failureKind="OPERATION";
        assertThat(FeedbackActions.allowed(state)).doesNotContain(RETRY_CODE,RETRY_CHECKS);
        state.cleanupBlocked(state.phase(), "cleanup failed");
        assertThat(FeedbackActions.allowed(state)).containsExactly(RETRY_CLEANUP);
    }
}
