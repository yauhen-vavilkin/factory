package org.folio.factory.testfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.testfactory.model.ScopeManifest;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a Jira story (inline payload or fetched via the Jira connector) and
 * produces {@code scope_manifest.md}: affected components, endpoints, risk and
 * ambiguities.
 */
public class TriageAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "triage-agent";

    private final JiraConnector jiraConnector;
    private final FrontmatterCodec frontmatterCodec;

    public TriageAgentWorker(ChatClient chatClient, JiraConnector jiraConnector,
                             FrontmatterCodec frontmatterCodec) {
        super(chatClient);
        this.jiraConnector = jiraConnector;
        this.frontmatterCodec = frontmatterCodec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode payload = context.triggerPayload();
        if (payload == null) {
            throw new AgentExecutionException("Triage requires a trigger payload with an issueKey");
        }
        String issueKey = payload.path("issueKey").asString("");
        JsonNode issue = payload.get("issue");
        String issueJson = issue != null && !issue.isNull()
                ? issue.toString()
                // Data-critical connector call: no inline issue means Jira must be
                // reachable; failures count against the retry budget.
                : jiraConnector.getIssue(issueKey).raw().toString();

        ScopeManifest manifest = callForEntity(Map.of("issue_json", issueJson), ScopeManifest.class);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issue_key", manifest.issueKey() == null || manifest.issueKey().isBlank()
                ? issueKey : manifest.issueKey());
        metadata.put("summary", manifest.summary());
        metadata.put("components", manifest.components());
        metadata.put("endpoints", manifest.endpoints());
        metadata.put("risk_level", manifest.riskLevel());
        metadata.put("ambiguities", manifest.ambiguities());
        return resultWithUsage("scope_manifest.md",
                frontmatterCodec.render(metadata, manifest.analysis()));
    }
}
