package org.folio.factory.core.agent;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Everything an agent worker is allowed to see for one step execution. The engine
 * populates {@code inputs} with only the artifacts declared in the step's
 * {@code inputs} list — scope boundary enforcement is physical, not advisory.
 * {@code triggerPayload} is present only when the step declares the reserved
 * {@code $trigger} input. {@code attempt} is the 1-based execution attempt of
 * this step (T25 recovery-bundle key component; a context built without one
 * reports the first attempt).
 */
public record AgentContext(
        UUID executionId,
        String stepId,
        Map<String, ArtifactContent> inputs,
        JsonNode triggerPayload,
        Map<String, Object> config,
        List<String> expectedOutputs,
        int attempt) {

    public AgentContext {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        config = config == null ? Map.of() : Map.copyOf(config);
        expectedOutputs = expectedOutputs == null ? List.of() : List.copyOf(expectedOutputs);
    }

    /**
     * Compatibility constructor for callers that do not know the attempt
     * number yet: such a context represents the first attempt.
     */
    public AgentContext(
            UUID executionId,
            String stepId,
            Map<String, ArtifactContent> inputs,
            JsonNode triggerPayload,
            Map<String, Object> config,
            List<String> expectedOutputs) {
        this(executionId, stepId, inputs, triggerPayload, config, expectedOutputs, 1);
    }

    public ArtifactContent requireInput(String name) {
        ArtifactContent artifact = inputs.get(name);
        if (artifact == null) {
            throw new NoSuchElementException(
                    "Step '" + stepId + "' has no input artifact '" + name + "' — is it declared in the flow descriptor?");
        }
        return artifact;
    }

    public String configString(String key, String defaultValue) {
        Object value = config.get(key);
        return value == null ? defaultValue : value.toString();
    }
}
