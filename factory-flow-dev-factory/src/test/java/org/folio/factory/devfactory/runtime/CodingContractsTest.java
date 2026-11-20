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
                new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)), null, null, List.of());

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
                "repo", "owner/repo", "main", "main"), request.constraints(), null, List.of());

        assertThatThrownBy(floating::requireReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Coding request is not ready");
    }

    @Test void outcomesEnforcePortableDecisionAndFailureShapes() {
        var mismatch = CodingOutcome.needsDecision(new CodingOutcome.Decision(
                CodingOutcome.DecisionKind.REPOSITORY_MISMATCH,
                "Is this the correct repository?", List.of(), "No owning module is present"), "", Map.of());
        assertThat(mismatch.status()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        assertThat(mismatch.decision().kind()).isEqualTo(CodingOutcome.DecisionKind.REPOSITORY_MISMATCH);
        assertThatThrownBy(() -> new CodingOutcome(CodingOutcome.Status.COMPLETED, "Done",
                mismatch.decision(), null, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CodingOutcome.failed("", "", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void theCodingPromptPointsTheAgentAtTheRepositorysOwnRules() {
        String prompt = CodingProtocol.prompt(request());

        assertThat(prompt).contains("CLAUDE.md", "AGENTS.md", "contribution policy", "NEWS");
        // Factory, not the agent, commits and pushes, whatever the repository's own rules say.
        assertThat(prompt).contains("Factory's rules win", "do not commit");
    }

    @Test void aCompletedAttemptEndsWithTheFourSectionReportThatThePullRequestReads() {
        String prompt = CodingProtocol.prompt(request());

        // PullRequestRenderer reads these headings and status words from the agent's final message.
        assertThat(prompt).contains("\"## Changes\"", "\"## Acceptance criteria\"", "\"## Assumptions\"",
                "\"## Could not read\"", "met", "not met", "not checked");
        // The decision protocol still comes after the report template.
        assertThat(prompt.indexOf("FACTORY_DECISION_REQUIRED:")).isGreaterThan(prompt.indexOf("## Could not read"));
    }

    @Test void operatorInstructionsAppearOnceAtTheEndOfThePrompt() {
        var request = withRunSettings(request());

        String prompt = CodingProtocol.prompt(request);

        assertThat(prompt).endsWith("Instructions from the operator:\nRead the ADR through the wiki REST URL.");
        assertThat(prompt.split("Read the ADR through the wiki REST URL", -1)).hasSize(2);
        assertThat(CodingProtocol.prompt(request())).doesNotContain("Instructions from the operator");
    }

    @Test void runSettingsNeverReachTheModelButTravelWithTheRequest() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var request = withRunSettings(request());

        String prompt = CodingProtocol.prompt(request);

        String requestJson = prompt.substring(prompt.indexOf("Factory CodingRequest JSON:\n") + 28,
                prompt.indexOf("\n\nInstructions from the operator:"));
        assertThat(json.readTree(requestJson).has("runSettings")).isFalse();
        assertThat(requestJson).doesNotContain("glm-5.3", "effort", "profileId");
        // The request itself keeps the settings, so dev_coding_request.json carries them to PR feedback.
        var copy = json.readValue(json.writeValueAsString(request), CodingRequest.class);
        assertThat(copy.runSettings()).isEqualTo(request.runSettings());
        assertThat(copy.withDecision(new CodingRequest.ConfirmedDecision("d", "PR_FEEDBACK", "q", "a"))
                .runSettings()).isEqualTo(request.runSettings());
    }

    private static CodingRequest withRunSettings(CodingRequest base) {
        return new CodingRequest(base.issueKey(), base.summary(), base.description(), base.comments(),
                base.linkedIssues(), base.acceptanceCriteria(), base.confirmedDecisions(), base.repository(),
                base.constraints(), new CodingRequest.RunSettings("glm-5.3", "max",
                        "Read the ADR through the wiki REST URL."), List.of());
    }

    private static CodingRequest request() {
        return new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults(), null, List.of());
    }
}
