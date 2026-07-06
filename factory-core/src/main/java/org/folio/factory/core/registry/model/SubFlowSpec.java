package org.folio.factory.core.registry.model;

import java.util.Map;

/**
 * Reference to another registered flow invoked as a child execution.
 * Input mapping copies parent artifacts into the child execution
 * (parent artifact name → child artifact name); output mapping copies child
 * artifacts back up on completion (child artifact name → parent artifact name).
 */
public record SubFlowSpec(
        String flowId,
        Map<String, String> inputMapping,
        Map<String, String> outputMapping) {

    public SubFlowSpec {
        inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
        outputMapping = outputMapping == null ? Map.of() : Map.copyOf(outputMapping);
    }
}
