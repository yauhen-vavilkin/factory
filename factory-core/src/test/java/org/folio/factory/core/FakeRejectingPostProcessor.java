package org.folio.factory.core;

import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.engine.StepPostProcessor;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Test fixture: a quality gate that rejects any step output named {@value #REJECTED_OUTPUT}
 * and lets every other output through, so it stays inert for the other fake flows.
 */
@Component
public class FakeRejectingPostProcessor implements StepPostProcessor {

    public static final String REJECTED_OUTPUT = "rejected.md";
    public static final String MESSAGE = "Test quality gate rejected " + REJECTED_OUTPUT;

    @Override
    public void process(FlowDescriptor flow, StepDescriptor step, Map<String, String> outputs) {
        if (outputs.containsKey(REJECTED_OUTPUT)) {
            throw new AgentExecutionException(MESSAGE);
        }
    }
}
