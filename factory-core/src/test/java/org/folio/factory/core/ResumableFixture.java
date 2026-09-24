package org.folio.factory.core;
import org.folio.factory.core.agent.*;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.hitl.*;
import org.folio.factory.core.domain.*;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class ResumableFixture implements AgentWorker, HitlDecisionPolicy {
    public String id() { return "resumable-fixture"; }
    public AgentResult execute(AgentContext c) {
        if (c.stepId().equals("initialize")) return AgentResult.of("counter", "0");
        c.invocation().requireOwned();
        int n = Integer.parseInt(c.requireInput("counter").content()) + 1;
        var outputs = Map.of("counter", Integer.toString(n));
        if (n > 24) return new AgentResult(outputs, Map.of());
        if (n % 2 == 1) return new AgentResult(outputs, Map.of(), AgentResult.Disposition.CONTINUE, null);
        String gate = c.triggerPayload() != null && c.triggerPayload().path("protected").asBoolean(false)
                ? "protected" : "repeat-review";
        return new AgentResult(outputs, Map.of(), AgentResult.Disposition.WAIT,
                new AgentResult.WaitRequest(new HitlGateSpec(gate, "Next round", "Continue", List.of()), "counter"));
    }
    public void validate(HitlReview r, PipelineExecution e, HitlDecision d, Map<String,String> a) {
        if (r.getGateId().equals("protected")) throw new IllegalArgumentException("Typed command required");
    }
}
