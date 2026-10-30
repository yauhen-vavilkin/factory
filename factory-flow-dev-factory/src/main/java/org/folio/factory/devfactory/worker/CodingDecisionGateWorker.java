package org.folio.factory.devfactory.worker;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.decision.ConditionalDecisionGate;
import org.folio.factory.devfactory.pilot.PilotAttemptPolicy;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Pauses only when the configured coding runtime returned NEEDS_DECISION.
 * Pilot executions stop instead: no review is opened and no continuation is
 * authorized.
 */
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
    private final PilotAttemptPolicy pilotPolicy;
    private final JsonMapper json = JsonMapper.builder().build();

    public CodingDecisionGateWorker(ConditionalDecisionGate gate) {
        this(gate, PilotAttemptPolicy.none());
    }

    public CodingDecisionGateWorker(ConditionalDecisionGate gate, PilotAttemptPolicy pilotPolicy) {
        this.gate = gate;
        this.pilotPolicy = pilotPolicy;
    }

    @Override public String id() { return ID; }

    @Override public AgentResult execute(AgentContext context) {
        CodingOutcome outcome = json.readValue(context.requireInput(DevelopWorker.OUTCOME).content(),
                CodingOutcome.class);
        boolean required = outcome.status() == CodingOutcome.Status.NEEDS_DECISION;
        boolean pilot = pilotPolicy.find(context.executionId()).isPresent();
        if (required && pilot) {
            pilotPolicy.recordStop(context.executionId(), context.stepId(),
                    "coding requested a human decision: " + outcome.decision().question());
        }
        boolean opened = required && !pilot && gate.open(context, GATE);
        return new AgentResult(Map.of(), Map.of("decisionRequired", required, "reviewOpened", opened));
    }
}
