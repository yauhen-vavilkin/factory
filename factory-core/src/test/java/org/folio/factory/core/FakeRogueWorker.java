package org.folio.factory.core;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Test fixture: returns its declared output plus an undeclared "rogue.md" to
 * exercise the engine's write-scope rejection.
 */
@Component
public class FakeRogueWorker implements AgentWorker {

    @Override
    public String id() {
        return "rogue-worker";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return new AgentResult(Map.of(
                "expected.md", "declared content",
                "rogue.md", "undeclared content"), Map.of());
    }
}
