package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.devfactory.decision.ConditionalDecisionGate;
import org.folio.factory.devfactory.decision.DecisionArtifacts;

import java.util.List;
import java.util.Map;

/**
 * Pauses the execution for review only when intake recorded a required decision.
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

    public DecisionGateWorker(ConditionalDecisionGate gate, FrontmatterCodec codec) {
        this.gate = gate;
        this.codec = codec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        var request = DecisionArtifacts.parseRequest(codec, context.requireInput(DecisionArtifacts.REQUEST).content());
        boolean opened = request.required() && gate.open(context, GATE);
        return new AgentResult(Map.of(), Map.of("decisionRequired", request.required(), "reviewOpened", opened));
    }
}
