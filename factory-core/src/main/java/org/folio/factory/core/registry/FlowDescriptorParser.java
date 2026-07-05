package org.folio.factory.core.registry;

import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.HashSet;
import java.util.Set;

/**
 * Parses and structurally validates YAML flow descriptors. Cross-flow checks
 * (sub-flow references, worker id resolution) happen in {@link FlowRegistry} and
 * the startup validator, once all flows and workers are known.
 */
@Component
public class FlowDescriptorParser {

    private final ObjectMapper yamlMapper;

    public FlowDescriptorParser() {
        this.yamlMapper = YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public FlowDescriptor parse(String yaml, String sourceName) {
        FlowDescriptor descriptor;
        try {
            descriptor = yamlMapper.readValue(yaml, FlowDescriptor.class);
        } catch (Exception e) {
            throw new FlowValidationException("Cannot parse flow descriptor " + sourceName + ": " + e.getMessage(), e);
        }
        validate(descriptor, sourceName);
        return descriptor;
    }

    private void validate(FlowDescriptor descriptor, String sourceName) {
        requireText(descriptor.id(), sourceName, "id");
        requireText(descriptor.name(), sourceName, "name");
        requireText(descriptor.version(), sourceName, "version");
        if (descriptor.agentChain().isEmpty()) {
            throw new FlowValidationException(sourceName + ": agent_chain must contain at least one step");
        }
        Set<String> stepIds = new HashSet<>();
        for (StepDescriptor step : descriptor.agentChain()) {
            requireText(step.stepId(), sourceName, "step_id");
            if (!stepIds.add(step.stepId())) {
                throw new FlowValidationException(sourceName + ": duplicate step_id '" + step.stepId() + "'");
            }
            if (step.type() == null) {
                throw new FlowValidationException(sourceName + ": step '" + step.stepId() + "' has no type");
            }
            switch (step.type()) {
                case AGENT -> {
                    if (isBlank(step.workerId())) {
                        throw new FlowValidationException(
                                sourceName + ": AGENT step '" + step.stepId() + "' must declare worker_id");
                    }
                }
                case HITL_GATE -> {
                    if (step.gate() == null || isBlank(step.gate().gateId())) {
                        throw new FlowValidationException(
                                sourceName + ": HITL_GATE step '" + step.stepId() + "' must declare gate.gate_id");
                    }
                }
                case SUB_FLOW -> {
                    if (step.subFlow() == null || isBlank(step.subFlow().flowId())) {
                        throw new FlowValidationException(
                                sourceName + ": SUB_FLOW step '" + step.stepId() + "' must declare sub_flow.flow_id");
                    }
                }
            }
        }
        for (var trigger : descriptor.triggers()) {
            if (isBlank(trigger.eventType())) {
                throw new FlowValidationException(sourceName + ": trigger with empty event_type");
            }
        }
        if (descriptor.agentChain().stream().noneMatch(s -> s.type() == StepType.AGENT)) {
            throw new FlowValidationException(sourceName + ": flow must contain at least one AGENT step");
        }
    }

    private void requireText(String value, String sourceName, String field) {
        if (isBlank(value)) {
            throw new FlowValidationException(sourceName + ": missing required field '" + field + "'");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
