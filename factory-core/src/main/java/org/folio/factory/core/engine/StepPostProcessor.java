package org.folio.factory.core.engine;

import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;

import java.util.Map;

/**
 * Framework-level quality control applied to agent step outputs before they are
 * persisted (static analysis gates, test data sanitisation, …). Implementations
 * are discovered as Spring beans and applied to every AGENT step of every flow.
 * Throwing {@link AgentExecutionException} fails the step, which counts against
 * the flow's retry budget.
 */
public interface StepPostProcessor {

    void process(FlowDescriptor flow, StepDescriptor step, Map<String, String> outputs)
            throws AgentExecutionException;
}
