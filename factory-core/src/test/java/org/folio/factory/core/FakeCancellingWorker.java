package org.folio.factory.core;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.service.StateManager;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Test fixture: finalizes its own execution to CANCELLED while running, then
 * fails — reproducing an operator cancel that lands while a step is still in
 * flight, so the engine's failure handling runs against an already-resolved
 * execution and its final-state guards must refuse the late writes.
 */
@Component
public class FakeCancellingWorker implements AgentWorker {

    private final StateManager stateManager;

    public FakeCancellingWorker(StateManager stateManager) {
        this.stateManager = stateManager;
    }

    @Override
    public String id() {
        return "cancelling-worker";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        stateManager.transition(context.executionId(), ExecutionStatus.CANCELLED,
                Map.of("reason", "cancelled mid-step"));
        throw new AgentExecutionException("failure after mid-step cancel");
    }
}
