package org.folio.factory.core.registry.model;

import java.util.List;
import java.util.Map;

/**
 * One position in a flow's agent chain. Exactly one of the type-specific fields is
 * populated depending on {@link StepType}: {@code workerId} for AGENT, {@code gate}
 * for HITL_GATE, {@code subFlow} for SUB_FLOW.
 *
 * <p>{@code inputs} lists the artifact names this step may read — the engine
 * provides only these (scope boundary enforcement). The reserved name
 * {@code $trigger} grants access to the trigger payload. {@code outputs} lists the
 * artifact names the step must produce.</p>
 */
public record StepDescriptor(
        String stepId,
        StepType type,
        String workerId,
        HitlGateSpec gate,
        SubFlowSpec subFlow,
        List<String> inputs,
        List<String> outputs,
        Map<String, Object> config) {

    public static final String TRIGGER_INPUT = "$trigger";

    public StepDescriptor {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public boolean wantsTriggerPayload() {
        return inputs.contains(TRIGGER_INPUT);
    }
}
