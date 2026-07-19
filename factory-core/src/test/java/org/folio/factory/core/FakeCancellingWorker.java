package org.folio.factory.core;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.service.ExecutionActionService;
import org.springframework.stereotype.Component;

/**
 * Test fixture: cancels its own execution while running, then fails — reproducing
 * an operator cancel that lands while a step is still in flight, so the engine's
 * failure handling runs against an already-resolved execution.
 */
@Component
public class FakeCancellingWorker implements AgentWorker {

    private final ExecutionActionService actionService;

    public FakeCancellingWorker(ExecutionActionService actionService) {
        this.actionService = actionService;
    }

    @Override
    public String id() {
        return "cancelling-worker";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        actionService.cancel(context.executionId(), "ops-cancel", "cancelled mid-step");
        throw new AgentExecutionException("failure after mid-step cancel");
    }
}
