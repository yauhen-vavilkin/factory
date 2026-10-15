package org.folio.factory.devfactory.feedback;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.folio.factory.devfactory.feedback.FeedbackActions.Action.*;
class FeedbackActionsTest {
    @Test void environmentProblemsNeverOfferAutomaticCodeRepair() {
        var state=FeedbackArtifacts.JSON.readValue("{\"phase\":\"FAILURE\"}", FeedbackState.class);state.failureKind="ENVIRONMENT";
        assertThat(FeedbackActions.allowed(state)).contains(RETRY_CHECKS).doesNotContain(RETRY_CODE,FINISH);
        state.failureKind="CANDIDATE_COMPILE";
        assertThat(FeedbackActions.allowed(state)).contains(RETRY_CODE,RETRY_CHECKS);
        state.cleanupBlocked(state.phase(), "cleanup failed");
        assertThat(FeedbackActions.allowed(state)).containsExactly(RETRY_CLEANUP);
    }
}
