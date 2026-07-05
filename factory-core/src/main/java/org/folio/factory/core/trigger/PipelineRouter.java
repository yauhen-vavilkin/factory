package org.folio.factory.core.trigger;

import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.TriggerContract;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point of the orchestration layer. Entirely data-driven: matches incoming
 * trigger events against the trigger contracts registered in the flow registry
 * and creates pipeline executions for every match. No flow-specific logic.
 */
@Component
public class PipelineRouter {

    private static final Logger log = LoggerFactory.getLogger(PipelineRouter.class);

    private final FlowRegistry flowRegistry;
    private final StateManager stateManager;
    private final JsonMapper jsonMapper;

    public PipelineRouter(FlowRegistry flowRegistry, StateManager stateManager, JsonMapper jsonMapper) {
        this.flowRegistry = flowRegistry;
        this.stateManager = stateManager;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Routes an event to every flow whose trigger contract matches. Returns the
     * ids of the executions created.
     */
    public List<UUID> route(TriggerEvent event) {
        List<UUID> created = new ArrayList<>();
        for (FlowDescriptor flow : flowRegistry.all()) {
            if (matches(flow, event) && hasRequiredInputs(flow, event.payload())) {
                created.add(start(flow, event).getId());
            }
        }
        if (created.isEmpty()) {
            log.debug("No flow matched trigger event type '{}' from {}", event.type(), event.source());
        }
        return created;
    }

    /**
     * Manual initiation: the caller names the flow explicitly, bypassing contract
     * matching but not input validation.
     */
    public UUID routeManual(String flowId, JsonNode payload) {
        FlowDescriptor flow = flowRegistry.require(flowId);
        requireInputs(flow, payload);
        return start(flow, TriggerEvent.of("manual", "api", payload)).getId();
    }

    private boolean matches(FlowDescriptor flow, TriggerEvent event) {
        for (TriggerContract contract : flow.triggers()) {
            if (!contract.eventType().equals(event.type())) {
                continue;
            }
            if (filtersMatch(contract, event.payload())) {
                return true;
            }
        }
        return false;
    }

    private boolean filtersMatch(TriggerContract contract, JsonNode payload) {
        for (Map.Entry<String, String> filter : contract.filters().entrySet()) {
            JsonNode value = payload == null ? null : payload.at(filter.getKey());
            if (value == null || value.isMissingNode() || !filter.getValue().equals(value.asString())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRequiredInputs(FlowDescriptor flow, JsonNode payload) {
        try {
            requireInputs(flow, payload);
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Trigger matched flow '{}' but payload is invalid: {}", flow.id(), e.getMessage());
            return false;
        }
    }

    private void requireInputs(FlowDescriptor flow, JsonNode payload) {
        JsonNode required = flow.inputSchema() == null ? null : flow.inputSchema().get("required");
        if (required == null || !required.isArray()) {
            return;
        }
        for (JsonNode field : required) {
            String name = field.asString();
            if (payload == null || payload.get(name) == null || payload.get(name).isNull()) {
                throw new IllegalArgumentException(
                        "Flow '" + flow.id() + "' requires input field '" + name + "'");
            }
        }
    }

    private PipelineExecution start(FlowDescriptor flow, TriggerEvent event) {
        String payloadJson = event.payload() == null ? null : jsonMapper.writeValueAsString(event.payload());
        PipelineExecution execution = stateManager.createExecution(flow.id(), flow.version(), payloadJson);
        log.info("Trigger '{}' from {} started execution {} of flow '{}'",
                event.type(), event.source(), execution.getId(), flow.id());
        return execution;
    }
}
