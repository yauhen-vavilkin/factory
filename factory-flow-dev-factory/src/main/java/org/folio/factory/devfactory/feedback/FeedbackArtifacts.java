package org.folio.factory.devfactory.feedback;
import org.folio.factory.core.agent.AgentContext;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

public final class FeedbackArtifacts {
    public static final String STATE = "dev_pr_state.json";
    public static final String REQUEST = "dev_pr_request.json";
    public static final String CANDIDATE = "dev_pr_candidate.json";
    public static final String VERIFICATION = "dev_pr_verification.json";
    public static final String DELIVERY = "dev_pr_delivery.json";
    public static final String SNAPSHOT = "dev_pr_comments.json";
    public static final List<String> OUTPUTS = List.of(STATE, REQUEST, CANDIDATE, VERIFICATION, DELIVERY);
    public static final String STEP = "pr-follow-up";
    public static final String VERSION = "0.9.0";
    public static final JsonMapper JSON = JsonMapper.builder().build();
    private FeedbackArtifacts() { }
    public static Map<String,String> initial() {
        var result = new LinkedHashMap<String,String>();
        OUTPUTS.forEach(n -> result.put(n, "{}"));
        result.put(STATE, JSON.writeValueAsString(new FeedbackState()));
        return result;
    }
    public static Map<String,String> inputs(AgentContext c) {
        var result = new LinkedHashMap<String,String>();
        OUTPUTS.forEach(n -> result.put(n, c.requireInput(n).content()));
        return result;
    }
}
