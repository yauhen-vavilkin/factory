package org.folio.factory.core.trigger;

import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.TriggerContract;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    public List<UUID> routeAdmitted(TriggerEvent event, String admissionKey) {
        requireAdmissionKey(admissionKey);
        List<UUID> admitted = new ArrayList<>();
        for (FlowDescriptor flow : flowRegistry.all()) {
            if (matches(flow, event) && hasRequiredInputs(flow, event.payload())) {
                admitted.add(startAdmitted(flow, event, admissionKey).getId());
            }
        }
        if (admitted.isEmpty()) {
            log.debug("No flow matched trigger event type '{}' from {}", event.type(), event.source());
        }
        return admitted;
    }

    /**
     * Idempotent admission variant of {@link #route}: the caller supplies a
     * stable admission key naming the admitted event revision (T22). Routing
     * the same revision again returns the execution admitted before — one
     * admitted revision yields exactly one execution per matching flow — and
     * the unique (flow_id, admission_key) constraint in the database backs the
     * promise under concurrency: racing admissions collide, the loser re-reads
     * and returns the winner's execution. Admission keys are per flow, so
     * several flows may still subscribe to the same event.
     */
    private PipelineExecution startAdmitted(FlowDescriptor flow, TriggerEvent event, String admissionKey) {
        String payloadJson = event.payload() == null ? null : jsonMapper.writeValueAsString(event.payload());
        PipelineExecution existing = stateManager.findAdmitted(flow.id(), admissionKey).orElse(null);
        if (existing != null) {
            log.info("Trigger '{}' from {} replayed admission {} of flow '{}': returning existing execution {}",
                    event.type(), event.source(), admissionKey, flow.id(), existing.getId());
            return existing;
        }
        try {
            PipelineExecution execution = stateManager.createAdmittedExecution(
                    flow.id(), flow.version(), payloadJson, admissionKey);
            log.info("Trigger '{}' from {} admitted as {} started execution {} of flow '{}'",
                    event.type(), event.source(), admissionKey, execution.getId(), flow.id());
            return execution;
        } catch (DataIntegrityViolationException e) {
            // A racing poller admitted the same revision first and committed;
            // the unique constraint aborted this insert. Return the winner.
            PipelineExecution winner = stateManager.findAdmitted(flow.id(), admissionKey).orElse(null);
            if (winner == null) {
                throw e;
            }
            log.info("Trigger '{}' from {} lost the admission race for {} of flow '{}': returning execution {}",
                    event.type(), event.source(), admissionKey, flow.id(), winner.getId());
            return winner;
        }
    }

    private static void requireAdmissionKey(String admissionKey) {
        if (admissionKey == null || admissionKey.isBlank()) {
            throw new IllegalArgumentException(
                    "admissionKey must not be blank — admitted events need a stable revision identity");
        }
        if (admissionKey.length() > 100) {
            throw new IllegalArgumentException("admissionKey must not exceed 100 characters, got "
                    + admissionKey.length());
        }
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
            if (value != null && !value.isMissingNode() && !value.isValueNode()) {
                // A filter pointing at an object/array is almost certainly a
                // descriptor typo; silent no-match would be undiagnosable.
                log.warn("Trigger filter '{}' resolved to a non-scalar node ({}); it will never match",
                        filter.getKey(), value.getNodeType());
                return false;
            }
            // asString(default) tolerates non-string scalars, where asString()
            // would throw.
            if (value == null || value.isMissingNode() || !filter.getValue().equals(value.asString(""))) {
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
            String name = field.asString("");
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
