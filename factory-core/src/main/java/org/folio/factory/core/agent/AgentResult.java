package org.folio.factory.core.agent;

import org.folio.factory.core.registry.model.HitlGateSpec;
import java.util.Map;
import java.util.Objects;

/** One invocation result. Continuations require an explicitly resumable step. */
public record AgentResult(Map<String, String> outputs, Map<String, Object> metrics,
                          Disposition disposition, WaitRequest waitRequest) {
    public enum Disposition { ADVANCE, CONTINUE, WAIT }
    public record WaitRequest(HitlGateSpec gate, String stateArtifact) {
        public WaitRequest { Objects.requireNonNull(gate); Objects.requireNonNull(stateArtifact); }
    }
    public AgentResult(Map<String, String> outputs, Map<String, Object> metrics) {
        this(outputs, metrics, Disposition.ADVANCE, null);
    }
    public AgentResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        Objects.requireNonNull(disposition);
        if ((disposition == Disposition.WAIT) != (waitRequest != null))
            throw new IllegalArgumentException("WAIT requires a wait request");
    }
    public static AgentResult of(String artifactName, String content) {
        return new AgentResult(Map.of(artifactName, content), Map.of());
    }
}
