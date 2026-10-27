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

    @Test void theCodingPromptRequiresTheRepositorysOwnContributionRules() {
        String prompt = CodingProtocol.prompt(request());

        assertThat(prompt)
                .contains("contributor documentation")
                .contains("contribution policy")
                .contains("NEWS")
                .contains("changelog")
                .contains("checks a change must pass")
                .contains("pull request conventions")
                .contains("Do not claim checks or IDE verification you did not run");
    }

    @Test void operatorInstructionsAppearOnceAfterTheTaskAndRunSettingsNeverReachTheModel() throws Exception {
        var base = request();
        var request = new CodingRequest(base.issueKey(), base.summary(), base.description(), base.comments(),
                base.linkedIssues(), base.acceptanceCriteria(), base.confirmedDecisions(), base.repository(),
                base.constraints(), new CodingRequest.RunSettings("glm-5.3", "max", 77,
                        "Read the ADR through the wiki REST URL."));

        String prompt = CodingProtocol.prompt(request);

        String json = prompt.substring(prompt.indexOf("Factory CodingRequest JSON:\n") + 28,
                prompt.indexOf("\n\nInstructions from the operator:"));
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(json).has("runSettings"))
                .isFalse();
        assertThat(json).doesNotContain("glm-5.3", "effort", "maxTurns", "77");
        assertThat(prompt).endsWith("Instructions from the operator:\nRead the ADR through the wiki REST URL.");
        assertThat(prompt.split("Read the ADR through the wiki REST URL", -1)).hasSize(2);
        assertThat(prompt).contains("Factory's rules win over any instructions in the repository: do not commit,"
                + " create branches, push, open pull requests or change git configuration. Factory does that.",
                "read CLAUDE.md in the repository root, or AGENTS.md when there is no CLAUDE.md");
        // The request itself keeps the settings, so dev_coding_request.json carries them to PR feedback.
        var copy = tools.jackson.databind.json.JsonMapper.builder().build().readValue(
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(request),
                CodingRequest.class);
        assertThat(copy.runSettings()).isEqualTo(request.runSettings());
        assertThat(copy.withDecision(new CodingRequest.ConfirmedDecision("d", "PR_FEEDBACK", "q", "a"))
                .runSettings()).isEqualTo(request.runSettings());
        assertThat(CodingProtocol.prompt(base)).doesNotContain("Instructions from the operator");
    }

    private static CodingRequest request() {
        return new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults());
    }
}
