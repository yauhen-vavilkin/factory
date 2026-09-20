package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevelopContinuationWorkerTest {
    private final FrontmatterCodec codec = new FrontmatterCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void amendedAnswerStartsOneFreshAttemptWithTheConfirmedDecision() {
        DevelopWorker develop = mock(DevelopWorker.class);
        var decision = decision();
        var requestArtifact = CodingDecisionArtifacts.Request.from(decision);
        var secondOutcome = CodingOutcome.completed("Implemented after clarification", Map.of());
        when(develop.retry(any(), any(), any())).thenReturn(new DevelopWorker.Attempt(null, secondOutcome,
                "{\"state\":\"CANDIDATE_UNVERIFIED\"}", "{\"state\":\"BASELINE_PASSED\"}", "", "", Map.of()));

        var worker = new DevelopContinuationWorker(develop, codec);
        var result = worker.execute(context(requestArtifact, "Use the public API"));

        CodingRequest next = json.readValue(result.outputs().get(IntakeResolveWorker.CODING_REQUEST),
                CodingRequest.class);
        assertThat(next.confirmedDecisions()).singleElement().satisfies(confirmed -> {
            assertThat(confirmed.requestId()).isEqualTo(requestArtifact.requestId());
            assertThat(confirmed.kind()).isEqualTo("PRODUCT_REQUIREMENTS");
            assertThat(confirmed.question()).isEqualTo(decision.question());
            assertThat(confirmed.answer()).isEqualTo("Use the public API");
        });
        assertThat(next.repository()).isEqualTo(request().repository());
        assertThat(next.repository().baseSha()).isEqualTo(request().repository().baseSha());
        assertThat(json.readValue(result.outputs().get(DevelopWorker.OUTCOME), CodingOutcome.class).status())
                .isEqualTo(CodingOutcome.Status.COMPLETED);
        verify(develop).retry(any(), eq(next), any());
    }

    @Test void repositoryMismatchPreservesReviewAndStopsWithoutAnotherAttempt() {
        DevelopWorker develop = mock(DevelopWorker.class);
        var decision = new CodingOutcome.Decision(CodingOutcome.DecisionKind.REPOSITORY_MISMATCH,
                "Is this the wrong repository?", List.of(), "Expected module is absent");
        var requestArtifact = CodingDecisionArtifacts.Request.from(decision);

        var result = new DevelopContinuationWorker(develop, codec)
                .execute(context(requestArtifact, decision, "Use owner/other-repo"));

        CodingRequest next = json.readValue(result.outputs().get(IntakeResolveWorker.CODING_REQUEST),
                CodingRequest.class);
        assertThat(next.confirmedDecisions()).singleElement().satisfies(confirmed -> {
            assertThat(confirmed.kind()).isEqualTo("REPOSITORY_MISMATCH");
            assertThat(confirmed.answer()).isEqualTo("Use owner/other-repo");
        });
        assertThat(next.repository()).isEqualTo(request().repository());
        CodingOutcome terminal = json.readValue(result.outputs().get(DevelopWorker.OUTCOME), CodingOutcome.class);
        assertThat(terminal.status()).isEqualTo(CodingOutcome.Status.FAILED);
        assertThat(terminal.failure().code()).isEqualTo("REPOSITORY_MISMATCH");
        var candidate = json.readTree(result.outputs().get(DevelopWorker.CANDIDATE));
        assertThat(candidate.path("state").asString()).isEqualTo("REPOSITORY_MISMATCH");
        assertThat(candidate.path("evidence").asString()).isEqualTo("Expected module is absent");
        assertThat(candidate.path("humanAnswer").asString()).isEqualTo("Use owner/other-repo");
        assertThat(candidate.path("repository").asString()).isEqualTo("repo");
        assertThat(candidate.path("baseSha").asString()).isEqualTo("a".repeat(40));
        verifyNoInteractions(develop);
    }

    @Test void approvalWithoutAnAnswerDoesNotAuthorizeAnotherAttempt() {
        DevelopWorker develop = mock(DevelopWorker.class);
        var requestArtifact = CodingDecisionArtifacts.Request.from(decision());

        var result = new DevelopContinuationWorker(develop, codec)
                .execute(context(requestArtifact, CodingDecisionArtifacts.UNANSWERED));

        assertThat(json.readValue(result.outputs().get(DevelopWorker.OUTCOME), CodingOutcome.class).status())
                .isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        verify(develop, never()).retry(any(), any(), any());
    }

    @Test void aSecondDecisionOutcomeIsTerminalForThisExecution() {
        DevelopWorker develop = mock(DevelopWorker.class);
        var requestArtifact = CodingDecisionArtifacts.Request.from(decision());
        var second = CodingOutcome.needsDecision(new CodingOutcome.Decision(
                CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "One more question?", List.of(), "Evidence"),
                Map.of());
        when(develop.retry(any(), any(), any())).thenReturn(new DevelopWorker.Attempt(null, second,
                "{\"state\":\"NEEDS_DECISION\"}", "{}", "", "", Map.of()));

        var result = new DevelopContinuationWorker(develop, codec)
                .execute(context(requestArtifact, "Use the public API"));

        assertThat(json.readValue(result.outputs().get(DevelopWorker.OUTCOME), CodingOutcome.class).status())
                .isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        verify(develop).retry(any(), any(), any());
    }

    private AgentContext context(CodingDecisionArtifacts.Request decisionRequest, String answer) {
        return context(decisionRequest, decision(), answer);
    }

    private AgentContext context(CodingDecisionArtifacts.Request decisionRequest,
                                 CodingOutcome.Decision decision, String answer) {
        CodingOutcome outcome = CodingOutcome.needsDecision(decision, Map.of());
        return new AgentContext(UUID.randomUUID(), "continue-implementation", Map.of(
                IntakeResolveWorker.CODING_REQUEST, artifact(IntakeResolveWorker.CODING_REQUEST,
                        json.writeValueAsString(request())),
                DevelopWorker.OUTCOME, artifact(DevelopWorker.OUTCOME, json.writeValueAsString(outcome)),
                DevelopWorker.CANDIDATE, artifact(DevelopWorker.CANDIDATE, "{\"state\":\"NEEDS_DECISION\"}"),
                DevelopWorker.READINESS, artifact(DevelopWorker.READINESS, "{\"state\":\"BASELINE_PASSED\"}"),
                CodingDecisionArtifacts.REQUEST, artifact(CodingDecisionArtifacts.REQUEST,
                        CodingDecisionArtifacts.renderRequest(codec, decisionRequest)),
                CodingDecisionArtifacts.ANSWER, artifact(CodingDecisionArtifacts.ANSWER,
                        CodingDecisionArtifacts.renderAnswer(codec,
                                new CodingDecisionArtifacts.Answer(decisionRequest.requestId(), answer)))),
                null, Map.of(), List.of());
    }

    private static ArtifactContent artifact(String name, String content) {
        return new ArtifactContent(name, 1, "application/json", content);
    }

    private static CodingOutcome.Decision decision() {
        return new CodingOutcome.Decision(CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS,
                "Which public API should change?", List.of("A", "B"), "Both APIs exist");
    }

    private static CodingRequest request() {
        return new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults());
    }
}
