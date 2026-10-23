package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.devfactory.decision.ConditionalDecisionGate;
import org.folio.factory.devfactory.decision.DecisionArtifacts;
import org.folio.factory.devfactory.pilot.PilotAttemptPolicy;

import java.util.List;
import java.util.Map;

/**
 * Pauses the execution for review only when intake recorded a required decision.
 * Pilot executions never wait for a review: a required decision records a stop
 * instead, and intake already keeps ambiguous mappings from reaching this gate.
 */
public class DecisionGateWorker implements AgentWorker {

    public static final String ID = "dev-decision-gate";
    public static final String GATE_ID = "dev-intake-decision";

    static final HitlGateSpec GATE = new HitlGateSpec(GATE_ID,
            "Developer Flow: choose the repository",
            "The issue maps to more than one configured repository. APPROVE accepts the recommended "
                    + "choice in dev_decision_answer.md. To pick another option, AMEND only the `choice` "
                    + "field of dev_decision_answer.md. REJECT stops the execution.",
            List.of(DecisionArtifacts.ANSWER, DecisionArtifacts.REQUEST, IntakeWorker.INTAKE));

    private final ConditionalDecisionGate gate;
    private final FrontmatterCodec codec;
    private final PilotAttemptPolicy pilotPolicy;

    public DecisionGateWorker(ConditionalDecisionGate gate, FrontmatterCodec codec) {
        this(gate, codec, PilotAttemptPolicy.none());
    }

    public DecisionGateWorker(ConditionalDecisionGate gate, FrontmatterCodec codec,
                              PilotAttemptPolicy pilotPolicy) {
        this.gate = gate;
        this.codec = codec;
        this.pilotPolicy = pilotPolicy;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        var request = DecisionArtifacts.parseRequest(codec, context.requireInput(DecisionArtifacts.REQUEST).content());
        boolean required = request.required();
        boolean pilot = pilotPolicy.find(context.executionId()).isPresent();
        if (required && pilot) {
            pilotPolicy.recordStop(context.executionId(), context.stepId(),
                    "repository selection requires a human choice; a pilot attempt stops instead of waiting for review");
        }
        boolean opened = required && !pilot && gate.open(context, GATE);
        return new AgentResult(Map.of(), Map.of("decisionRequired", required, "reviewOpened", opened));
    }
}
