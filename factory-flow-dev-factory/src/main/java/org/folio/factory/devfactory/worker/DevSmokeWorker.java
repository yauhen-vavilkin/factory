package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic placeholder proving Developer Flow runs as an ordinary plugin.
 * It performs no Jira read, coding, verification or delivery, and its artifact
 * says so explicitly.
 */
public class DevSmokeWorker implements AgentWorker {

    public static final String ID = "dev-smoke-worker";
    public static final String OUTPUT = "dev_smoke.md";
    public static final String STATE = "SMOKE_ONLY";

    private final FrontmatterCodec frontmatterCodec;

    public DevSmokeWorker(FrontmatterCodec frontmatterCodec) {
        this.frontmatterCodec = frontmatterCodec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", STATE);
        metadata.put("issue_key", context.triggerPayload() == null
                ? "" : context.triggerPayload().path("issueKey").asString(""));
        String body = """
                # Developer Flow smoke run

                Smoke only: no Jira intake, coding, verification or delivery was performed.
                """;
        return AgentResult.of(OUTPUT, frontmatterCodec.render(metadata, body));
    }
}
