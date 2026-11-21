package org.folio.factory.core;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Test fixture: returns no outputs at all, to exercise the engine's check that every
 * declared output is produced.
 */
@Component
public class FakeSilentWorker implements AgentWorker {

    @Override
    public String id() {
        return "silent-worker";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return new AgentResult(Map.of(), Map.of());
    }
}
