package org.folio.factory.app.web;

import org.folio.factory.core.domain.FlowRegistryEntry;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.registry.model.SubFlowSpec;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@RestController
@RequestMapping("/api/flows")
public class FlowController {

    private final FlowRegistry flowRegistry;
    private final FlowRegistryEntryRepository mirror;

    public FlowController(FlowRegistry flowRegistry, FlowRegistryEntryRepository mirror) {
        this.flowRegistry = flowRegistry;
        this.mirror = mirror;
    }

    public record TriggerInfo(String eventType, Map<String, String> filters) {
    }

    public record GateInfo(String gateId, String title, String reviewInstructions, List<String> reviewedArtifacts) {
    }

    public record SubFlowInfo(String flowId, Map<String, String> inputMapping, Map<String, String> outputMapping) {
    }

    public record StepInfo(int index, String stepId, String type, String workerId, GateInfo gate, SubFlowInfo subFlow,
                           List<String> inputs, List<String> outputs, Map<String, Object> config) {
    }

    public record RetryInfo(int maxAttempts, List<Long> backoffSeconds) {
    }

    public record FlowSummary(String id, String name, String version, int stepCount, int gateCount,
                              List<TriggerInfo> triggers) {
    }

    public record FlowDetail(String id, String name, String version, List<TriggerInfo> triggers,
                             JsonNode inputSchema, JsonNode outputSchema, RetryInfo retryPolicy,
                             List<StepInfo> steps, String rawYaml, Instant registeredAt) {
    }

    @GetMapping
    public List<FlowSummary> list() {
        return flowRegistry.all().stream().map(this::toSummary).toList();
    }

    @GetMapping("/{flowId}")
    public FlowDetail detail(@PathVariable("flowId") String flowId) {
        FlowDescriptor descriptor = flowRegistry.find(flowId)
                .orElseThrow(() -> new NoSuchElementException("No registered flow with id '" + flowId + "'"));
        Optional<FlowRegistryEntry> entry = mirror.findById(
                new FlowRegistryEntry.Key(descriptor.id(), descriptor.version()));

        List<StepDescriptor> chain = descriptor.agentChain();
        List<StepInfo> steps = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            steps.add(toStepInfo(i, chain.get(i)));
        }
        return new FlowDetail(descriptor.id(), descriptor.name(), descriptor.version(), triggers(descriptor),
                descriptor.inputSchema(), descriptor.outputSchema(),
                new RetryInfo(descriptor.retryPolicy().maxAttempts(), descriptor.retryPolicy().backoffSeconds()),
                steps,
                entry.map(FlowRegistryEntry::getRawYaml).orElse(null),
                entry.map(FlowRegistryEntry::getRegisteredAt).orElse(null));
    }

    private FlowSummary toSummary(FlowDescriptor descriptor) {
        int gateCount = (int) descriptor.agentChain().stream()
                .filter(step -> step.type() == StepType.HITL_GATE).count();
        return new FlowSummary(descriptor.id(), descriptor.name(), descriptor.version(),
                descriptor.agentChain().size(), gateCount, triggers(descriptor));
    }

    private List<TriggerInfo> triggers(FlowDescriptor descriptor) {
        return descriptor.triggers().stream()
                .map(t -> new TriggerInfo(t.eventType(), t.filters()))
                .toList();
    }

    private StepInfo toStepInfo(int index, StepDescriptor step) {
        return new StepInfo(index, step.stepId(), step.type().name(), step.workerId(),
                toGate(step.gate()), toSubFlow(step.subFlow()),
                step.inputs(), step.outputs(), step.config());
    }

    private GateInfo toGate(HitlGateSpec gate) {
        return gate == null ? null
                : new GateInfo(gate.gateId(), gate.title(), gate.reviewInstructions(), gate.reviewedArtifacts());
    }

    private SubFlowInfo toSubFlow(SubFlowSpec subFlow) {
        return subFlow == null ? null
                : new SubFlowInfo(subFlow.flowId(), subFlow.inputMapping(), subFlow.outputMapping());
    }
}
