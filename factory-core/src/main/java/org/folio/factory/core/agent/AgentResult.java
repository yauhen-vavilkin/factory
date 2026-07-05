package org.folio.factory.core.agent;

import java.util.Map;

/**
 * Output of one agent worker invocation: artifact name → content, plus optional
 * metrics (token counts, durations) that are recorded in the audit log.
 */
public record AgentResult(Map<String, String> outputs, Map<String, Object> metrics) {

    public AgentResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public static AgentResult of(String artifactName, String content) {
        return new AgentResult(Map.of(artifactName, content), Map.of());
    }
}
