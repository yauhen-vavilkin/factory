package org.folio.factory.core;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.springframework.stereotype.Component;

/**
 * Test fixture: always fails, for exercising retry budgets and escalation.
 */
@Component
public class FakeFailingWorker implements AgentWorker {

    @Override
    public String id() {
        return "failing-worker";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        throw new AgentExecutionException("deliberate test failure");
    }
}
