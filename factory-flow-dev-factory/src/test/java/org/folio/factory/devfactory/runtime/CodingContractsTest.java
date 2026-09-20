package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodingContractsTest {

    @Test void requestKeepsExplicitContextAndDoesNotInventAcceptanceCriteria() {
        var request = new CodingRequest("TASK-1", "Summary", "", List.of(
                new CodingRequest.Comment("A", "2026-09-20", "Use the existing API")), List.of(
                new CodingRequest.LinkedIssue("blocks", "TASK-2", "Dependency", "Open")), null, null,
                new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)), null);

        request.requireReady();
        assertThat(request.acceptanceCriteria()).isEmpty();
        assertThat(request.confirmedDecisions()).isEmpty();
        assertThat(request.constraints()).isEqualTo(CodingRequest.Constraints.defaults());
        assertThat(request.repository().baseSha()).isEqualTo("a".repeat(40));
    }

    @Test void aConfirmedAnswerCreatesANewRequestWithoutChangingThePinnedRepository() {
        var request = request();
        var next = request.withDecision(new CodingRequest.ConfirmedDecision(
                "id", "PRODUCT_REQUIREMENTS", "Which API?", "Use the public API"));

        assertThat(request.confirmedDecisions()).isEmpty();
        assertThat(next.confirmedDecisions()).extracting(CodingRequest.ConfirmedDecision::answer)
                .containsExactly("Use the public API");
        assertThat(next.repository()).isEqualTo(request.repository());
    }

    @Test void requestRejectsAnythingOtherThanAnExactPinnedCommit() {
        var request = request();
        var floating = new CodingRequest(request.issueKey(), request.summary(), request.description(),
                request.comments(), request.linkedIssues(), request.acceptanceCriteria(),
                request.confirmedDecisions(), new CodingRequest.RepositoryTarget(
                "repo", "owner/repo", "main", "main"), request.constraints());

        assertThatThrownBy(floating::requireReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Coding request is not ready");
    }

    @Test void outcomesEnforcePortableDecisionAndFailureShapes() {
        var mismatch = CodingOutcome.needsDecision(new CodingOutcome.Decision(
                CodingOutcome.DecisionKind.REPOSITORY_MISMATCH,
                "Is this the correct repository?", List.of(), "No owning module is present"), Map.of());
        assertThat(mismatch.status()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        assertThat(mismatch.decision().kind()).isEqualTo(CodingOutcome.DecisionKind.REPOSITORY_MISMATCH);
        assertThatThrownBy(() -> new CodingOutcome(CodingOutcome.Status.COMPLETED, "Done",
                mismatch.decision(), null, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodingOutcome.failed("", "", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CodingRequest request() {
        return new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults());
    }
}
