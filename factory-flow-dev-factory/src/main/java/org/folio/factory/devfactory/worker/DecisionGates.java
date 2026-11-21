package org.folio.factory.devfactory.worker;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.devfactory.decision.ConditionalDecisionGate;
import org.folio.factory.devfactory.tasks.TaskGuard;

import java.util.Map;
import java.util.function.Supplier;

/** A required decision opens its review, except in a task run, which records a stop instead of waiting. */
final class DecisionGates {

    private DecisionGates() {
    }

    static AgentResult openOrStop(AgentContext context, ConditionalDecisionGate gate, HitlGateSpec spec,
                                  boolean required, Supplier<String> reason, TaskGuard taskGuard) {
        boolean taskRun = taskGuard.isTaskRun(context.executionId());
        if (required && taskRun) {
            taskGuard.recordStop(context.executionId(), context.stepId(), reason.get());
        }
        boolean opened = required && !taskRun && gate.open(context, spec);
        return new AgentResult(Map.of(), Map.of("decisionRequired", required, "reviewOpened", opened));
    }
}
