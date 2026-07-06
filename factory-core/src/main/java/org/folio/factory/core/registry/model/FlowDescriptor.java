package org.folio.factory.core.registry.model;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * A flow plugin descriptor — the complete, data-driven definition of a pipeline
 * flow. Descriptors are authored as YAML resources and parsed at startup; the
 * control plane has no hardcoded knowledge of any specific flow.
 */
public record FlowDescriptor(
        String id,
        String name,
        String version,
        List<TriggerContract> triggers,
        JsonNode inputSchema,
        JsonNode outputSchema,
        List<StepDescriptor> agentChain,
        RetryPolicy retryPolicy) {

    public FlowDescriptor {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        agentChain = agentChain == null ? List.of() : List.copyOf(agentChain);
        retryPolicy = retryPolicy == null ? RetryPolicy.DEFAULT : retryPolicy;
    }

    public StepDescriptor step(int index) {
        return agentChain.get(index);
    }

    public boolean hasStep(int index) {
        return index >= 0 && index < agentChain.size();
    }
}
