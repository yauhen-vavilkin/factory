package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Retries a product clarification once; repository mismatches stop with an explicit non-candidate outcome. */
public class DevelopContinuationWorker implements AgentWorker {
    public static final String ID = "dev-develop-continuation";

    private final DevelopWorker develop;
    private final FrontmatterCodec codec;
    private final JsonMapper json = JsonMapper.builder().build();

    public DevelopContinuationWorker(DevelopWorker develop, FrontmatterCodec codec) {
        this.develop = develop;
        this.codec = codec;
    }

    @Override public String id() { return ID; }

    @Override public AgentResult execute(AgentContext context) {
        CodingRequest request = json.readValue(
                context.requireInput(IntakeResolveWorker.CODING_REQUEST).content(), CodingRequest.class);
        CodingOutcome outcome = json.readValue(
                context.requireInput(DevelopWorker.OUTCOME).content(), CodingOutcome.class);
        if (outcome.status() != CodingOutcome.Status.NEEDS_DECISION)
            return passThrough(context, request, outcome);

        var decisionRequest = CodingDecisionArtifacts.parseRequest(codec,
                context.requireInput(CodingDecisionArtifacts.REQUEST).content());
        var answer = CodingDecisionArtifacts.parseAnswer(codec,
                context.requireInput(CodingDecisionArtifacts.ANSWER).content());
        if (!answer.requestId().equals(decisionRequest.requestId()))
            throw new AgentExecutionException("Coding decision answer is stale");
        if (CodingDecisionArtifacts.UNANSWERED.equals(answer.answer()))
            return passThrough(context, request, outcome);

        CodingRequest next = request.withDecision(new CodingRequest.ConfirmedDecision(
                decisionRequest.requestId(), decisionRequest.kind(), decisionRequest.question(), answer.answer()));
        if (outcome.decision().kind() == CodingOutcome.DecisionKind.REPOSITORY_MISMATCH)
            return repositoryMismatch(context, next, outcome, decisionRequest, answer);
        var attempt = develop.retry(context, next,
                json.readTree(context.requireInput(DevelopWorker.READINESS).content()));
        return new AgentResult(Map.of(
                IntakeResolveWorker.CODING_REQUEST, json.writeValueAsString(next),
                DevelopWorker.OUTCOME, json.writeValueAsString(attempt.outcome()),
                DevelopWorker.CANDIDATE, attempt.candidate(),
                DevelopWorker.READINESS, attempt.readiness()), attempt.metrics());
    }

    private AgentResult passThrough(AgentContext context, CodingRequest request, CodingOutcome outcome) {
        return new AgentResult(Map.of(
                IntakeResolveWorker.CODING_REQUEST, json.writeValueAsString(request),
                DevelopWorker.OUTCOME, json.writeValueAsString(outcome),
                DevelopWorker.CANDIDATE, context.requireInput(DevelopWorker.CANDIDATE).content(),
                DevelopWorker.READINESS, context.requireInput(DevelopWorker.READINESS).content()), Map.of());
    }

    private AgentResult repositoryMismatch(AgentContext context, CodingRequest request, CodingOutcome previous,
                                           CodingDecisionArtifacts.Request decision,
                                           CodingDecisionArtifacts.Answer answer) {
        String reason = "Repository target remains unresolved after human review: " + decision.question();
        CodingOutcome terminal = CodingOutcome.failed("REPOSITORY_MISMATCH", reason, previous.metrics());
        Map<String, Object> candidate = new java.util.LinkedHashMap<>();
        candidate.put("state", "REPOSITORY_MISMATCH");
        candidate.put("reason", reason);
        candidate.put("requestId", decision.requestId());
        candidate.put("evidence", decision.evidence());
        candidate.put("humanAnswer", answer.answer());
        candidate.put("repository", request.repository().key());
        candidate.put("baseSha", request.repository().baseSha());
        return new AgentResult(Map.of(
                IntakeResolveWorker.CODING_REQUEST, json.writeValueAsString(request),
                DevelopWorker.OUTCOME, json.writeValueAsString(terminal),
                DevelopWorker.CANDIDATE, json.writeValueAsString(candidate),
                DevelopWorker.READINESS, context.requireInput(DevelopWorker.READINESS).content()), Map.of());
    }
}
