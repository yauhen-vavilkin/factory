package org.folio.factory.app.web;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.agents.prompt.PromptCatalog;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/workers")
public class WorkerController {

    private final AgentWorkerRegistry registry;
    private final PromptCatalog catalog;
    private final FlowRegistry flowRegistry;

    public WorkerController(AgentWorkerRegistry registry, PromptCatalog catalog, FlowRegistry flowRegistry) {
        this.registry = registry;
        this.catalog = catalog;
        this.flowRegistry = flowRegistry;
    }

    public record WorkerStepRef(String flowId, String stepId) {
    }

    public record WorkerInfo(String id, String className, boolean llm, List<String> prompts,
                             List<WorkerStepRef> usedBy) {
    }

    @GetMapping
    public List<WorkerInfo> list() {
        return registry.all().values().stream()
                .sorted(Comparator.comparing(AgentWorker::id))
                .map(this::toInfo)
                .toList();
    }

    private WorkerInfo toInfo(AgentWorker worker) {
        return new WorkerInfo(worker.id(), worker.getClass().getName(),
                worker instanceof AbstractLlmAgentWorker,
                catalog.promptsFor(worker.id()), usedBy(worker.id(), flowRegistry));
    }

    /** Shared with the workers UI page so the usage scan cannot drift between the two. */
    static List<WorkerStepRef> usedBy(String workerId, FlowRegistry flowRegistry) {
        List<WorkerStepRef> usedBy = new ArrayList<>();
        for (FlowDescriptor flow : flowRegistry.all()) {
            for (StepDescriptor step : flow.agentChain()) {
                if (step.type() == StepType.AGENT && workerId.equals(step.workerId())) {
                    usedBy.add(new WorkerStepRef(flow.id(), step.stepId()));
                }
            }
        }
        return usedBy;
    }
}
