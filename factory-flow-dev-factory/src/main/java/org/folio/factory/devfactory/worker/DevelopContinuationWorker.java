package org.folio.factory.devfactory.worker;

import lombok.RequiredArgsConstructor;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;

import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retries a product clarification once; repository mismatches stop with an explicit non-candidate
 * outcome. Task attempts never continue automatically: an explicit retry is a
 * new execution.
 */
@RequiredArgsConstructor
public class DevelopContinuationWorker implements AgentWorker {
    public static final String ID = "dev-develop-continuation";

    private final InitialCodingAttempt develop;
    private final FrontmatterCodec codec;
    private final TaskAttemptPolicy taskPolicy;
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        var request = json.readValue(
                context.requireInput(IntakeResolveWorker.CODING_REQUEST).content(), CodingRequest.class);
        var outcome = json.readValue(context.requireInput(DevelopWorker.OUTCOME).content(), CodingOutcome.class);
        if (outcome.status() != CodingOutcome.Status.NEEDS_DECISION) {
            return passThrough(context, request, outcome);
        }
        var clarification = readClarification(context);
        if (taskPolicy.find(context.executionId()).isPresent()) {
            stopAnsweredTask(context, clarification);
            return passThrough(context, request, outcome);
        }
        if (clarification.isUnanswered()) {
            return passThrough(context, request, outcome);
        }
        return continueWithAnswer(context, request, outcome, clarification);
    }

    private Clarification readClarification(AgentContext context) {
        var decisionRequest = CodingDecisionArtifacts.parseRequest(
                codec, context.requireInput(CodingDecisionArtifacts.REQUEST).content());
        var answer = CodingDecisionArtifacts.parseAnswer(
                codec, context.requireInput(CodingDecisionArtifacts.ANSWER).content());
        if (!answer.requestId().equals(decisionRequest.requestId())) {
            throw new AgentExecutionException("Coding decision answer is stale");
        }
        return new Clarification(decisionRequest, answer);
    }

    private void stopAnsweredTask(AgentContext context, Clarification clarification) {
        if (clarification.isUnanswered()) {
            return;
        }
        taskPolicy.recordStop(context.executionId(), context.stepId(),
                "a clarification answer cannot continue a task attempt automatically;"
                        + " an explicit retry is a new execution");
    }

    private AgentResult continueWithAnswer(
            AgentContext context, CodingRequest request, CodingOutcome outcome, Clarification clarification) {
        var decisionRequest = clarification.request();
        var next = request.withDecision(new CodingRequest.ConfirmedDecision(decisionRequest.requestId(),
                decisionRequest.kind(), decisionRequest.question(), clarification.answer().answer()));
        if (outcome.decision().kind() == CodingOutcome.DecisionKind.REPOSITORY_MISMATCH) {
            return repositoryMismatch(context, next, outcome, decisionRequest, clarification.answer());
        }
        var readiness = json.readTree(context.requireInput(DevelopWorker.READINESS).content());
        var attempt = develop.retry(context, next, readiness);
        return new AgentResult(
                Map.of(
                        IntakeResolveWorker.CODING_REQUEST, json.writeValueAsString(next),
                        DevelopWorker.OUTCOME, json.writeValueAsString(attempt.outcome()),
                        DevelopWorker.CANDIDATE, attempt.candidate(),
                        DevelopWorker.READINESS, attempt.readiness()),
                attempt.metrics());
    }

    private AgentResult passThrough(
            AgentContext context, CodingRequest request, CodingOutcome outcome) {
        return new AgentResult(
                Map.of(
                        IntakeResolveWorker.CODING_REQUEST, json.writeValueAsString(request),
                        DevelopWorker.OUTCOME, json.writeValueAsString(outcome),
                        DevelopWorker.CANDIDATE,
                                context.requireInput(DevelopWorker.CANDIDATE).content(),
                        DevelopWorker.READINESS,
                                context.requireInput(DevelopWorker.READINESS).content()),
                Map.of());
    }

    private AgentResult repositoryMismatch(
            AgentContext context,
            CodingRequest request,
            CodingOutcome previous,
            CodingDecisionArtifacts.Request decision,
            CodingDecisionArtifacts.Answer answer) {
        String reason =
                "Repository target remains unresolved after human review: " + decision.question();
        CodingOutcome terminal =
                CodingOutcome.failed("REPOSITORY_MISMATCH", reason, previous.metrics());
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("state", "REPOSITORY_MISMATCH");
        candidate.put("reason", reason);
        candidate.put("requestId", decision.requestId());
        candidate.put("evidence", decision.evidence());
        candidate.put("humanAnswer", answer.answer());
        candidate.put("repository", request.repository().key());
        candidate.put("baseSha", request.repository().baseSha());
        return new AgentResult(
                Map.of(
                        IntakeResolveWorker.CODING_REQUEST, json.writeValueAsString(request),
                        DevelopWorker.OUTCOME, json.writeValueAsString(terminal),
                        DevelopWorker.CANDIDATE, json.writeValueAsString(candidate),
                        DevelopWorker.READINESS,
                                context.requireInput(DevelopWorker.READINESS).content()),
                Map.of());
    }

    private record Clarification(CodingDecisionArtifacts.Request request, CodingDecisionArtifacts.Answer answer) {

        boolean isUnanswered() {
            return CodingDecisionArtifacts.UNANSWERED.equals(answer.answer());
        }
    }
}
