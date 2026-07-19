package org.folio.factory.core.agent;

import jakarta.annotation.PostConstruct;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.FlowValidationException;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects every {@link AgentWorker} bean and fails startup if any registered flow
 * references a worker id that does not exist.
 */
@Component
public class AgentWorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkerRegistry.class);

    private final Map<String, AgentWorker> workers = new LinkedHashMap<>();
    private final FlowRegistry flowRegistry;

    public AgentWorkerRegistry(List<AgentWorker> workerBeans, FlowRegistry flowRegistry) {
        this.flowRegistry = flowRegistry;
        for (AgentWorker worker : workerBeans) {
            AgentWorker previous = workers.putIfAbsent(worker.id(), worker);
            if (previous != null) {
                throw new IllegalStateException("Duplicate agent worker id '" + worker.id() + "' ("
                        + previous.getClass().getSimpleName() + " and " + worker.getClass().getSimpleName() + ")");
            }
        }
    }

    @PostConstruct
    void validateFlowWorkerReferences() {
        validateAgainst(flowRegistry.all());
        log.info("Agent worker library loaded {} worker(s): {}", workers.size(), workers.keySet());
    }

    void validateAgainst(Collection<FlowDescriptor> flows) {
        for (FlowDescriptor flow : flows) {
            for (StepDescriptor step : flow.agentChain()) {
                if (step.type() == StepType.AGENT && !workers.containsKey(step.workerId())) {
                    throw new FlowValidationException("Flow '" + flow.id() + "' step '" + step.stepId()
                            + "' references unknown agent worker '" + step.workerId() + "'");
                }
            }
        }
    }

    public AgentWorker require(String workerId) {
        AgentWorker worker = workers.get(workerId);
        if (worker == null) {
            throw new IllegalArgumentException("No agent worker with id '" + workerId + "'");
        }
        return worker;
    }

    public Map<String, AgentWorker> all() {
        return Map.copyOf(workers);
    }
}
