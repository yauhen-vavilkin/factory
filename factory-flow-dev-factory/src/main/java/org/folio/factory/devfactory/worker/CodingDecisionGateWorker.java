package org.folio.factory.devfactory.worker;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.decision.ConditionalDecisionGate;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/** Pauses only when the configured coding runtime returned NEEDS_DECISION. */
public class CodingDecisionGateWorker implements AgentWorker {
    public static final String ID = "dev-coding-decision-gate";
    public static final String GATE_ID = "dev-coding-decision";
    static final HitlGateSpec GATE = new HitlGateSpec(GATE_ID,
            "Developer Flow: answer the coding question",
            "The coding runtime found a blocking product or repository question. AMEND only "
                    + "dev_coding_decision_answer.md and replace UNANSWERED with the confirmed human answer. "
                    + "APPROVE without amending does not authorize another coding attempt. REJECT stops the execution.",
            List.of(IntakeResolveWorker.CODING_REQUEST, DevelopWorker.OUTCOME,
                    CodingDecisionArtifacts.REQUEST, CodingDecisionArtifacts.ANSWER));

    private final ConditionalDecisionGate gate;
    private final JsonMapper json = JsonMapper.builder().build();

    public CodingDecisionGateWorker(ConditionalDecisionGate gate) { this.gate = gate; }

    @Override public String id() { return ID; }

    @Override public AgentResult execute(AgentContext context) {
        CodingOutcome outcome = json.readValue(context.requireInput(DevelopWorker.OUTCOME).content(),
                CodingOutcome.class);
        boolean required = outcome.status() == CodingOutcome.Status.NEEDS_DECISION;
        boolean opened = required && gate.open(context, GATE);
        return new AgentResult(Map.of(), Map.of("decisionRequired", required, "reviewOpened", opened));
    }
}
