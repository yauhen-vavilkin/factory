package org.folio.factory.devfactory.worker;

import lombok.RequiredArgsConstructor;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.PinnedLockfile;

import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Baseline, one portable coding attempt, and trusted freeze only on COMPLETED. */
@RequiredArgsConstructor
public class DevelopWorker implements AgentWorker {
    public static final String ID = "dev-develop";
    public static final String CANDIDATE = "dev_candidate.json";
    public static final String READINESS = "dev_readiness.json";
    public static final String LOCKFILE = "dev_lockfile.json";
    public static final String OUTCOME = "dev_coding_outcome.json";

    private final InitialCodingAttempt codingAttempt;
    private final FrontmatterCodec codec;
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        var brief =
                codec.parse(context.requireInput(IntakeResolveWorker.TASK_BRIEF).content())
                        .metadata();
        String state = brief.path("state").asString("");
        if (!state.equals(IntakeResolveWorker.INTAKE_READY)) return blocked(state, "Intake is not ready");
        CodingRequest request =
                json.readValue(
                        context.requireInput(IntakeResolveWorker.CODING_REQUEST).content(),
                        CodingRequest.class);
        var attempt = codingAttempt.start(context, request);
        return initialResult(attempt);
    }

    private AgentResult initialResult(InitialCodingAttempt.Attempt attempt) {
        return new AgentResult(
                Map.of(
                        CANDIDATE,
                        attempt.candidate(),
                        READINESS,
                        attempt.readiness(),
                        LOCKFILE,
                        attempt.lockfile(),
                        OUTCOME,
                        json.writeValueAsString(attempt.outcome()),
                        CodingDecisionArtifacts.REQUEST,
                        attempt.decisionRequest(),
                        CodingDecisionArtifacts.ANSWER,
                        attempt.decisionAnswer()),
                attempt.metrics());
    }

    private AgentResult blocked(String state, String reason) {
        CodingOutcome outcome = CodingOutcome.failed(state, reason, Map.of());
        var decision = CodingDecisionArtifacts.Request.none();
        return new AgentResult(
                Map.of(
                        CANDIDATE,
                        placeholder(json, state, reason),
                        READINESS,
                        json.writeValueAsString(Map.of()),
                        LOCKFILE,
                        PinnedLockfile.none("NOT_RUN", "Development did not start"),
                        OUTCOME,
                        json.writeValueAsString(outcome),
                        CodingDecisionArtifacts.REQUEST,
                        CodingDecisionArtifacts.renderRequest(codec, decision),
                        CodingDecisionArtifacts.ANSWER,
                        CodingDecisionArtifacts.renderAnswer(
                                codec,
                                new CodingDecisionArtifacts.Answer(
                                        decision.requestId(), CodingDecisionArtifacts.UNANSWERED))),
                Map.of());
    }

    /** The candidate artifact written when no candidate was frozen. */
    static String placeholder(JsonMapper json, String state, String reason) {
        return json.writeValueAsString(
                Map.of("state", state, "reason", reason == null ? "" : reason));
    }
}
