package org.folio.factory.testfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.llm.StubChatModel;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriageAgentWorkerTest {

    private static final String SCOPE_MANIFEST_JSON = """
            {"issueKey": "ERM-1001",
             "summary": "Add agreement name validation",
             "components": ["mod-agreements"],
             "endpoints": ["/erm/sas"],
             "riskLevel": "medium",
             "ambiguities": ["Maximum name length is not specified"],
             "analysis": "## Analysis\\n\\nThe story adds validation for agreement names."}
            """;

    private final FrontmatterCodec frontmatterCodec = new FrontmatterCodec();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    static class UnusedJira implements JiraConnector {
        @Override
        public JiraIssue getIssue(String issueKey) {
            throw new AssertionError("Jira must not be called when the payload carries an inline issue");
        }

        @Override
        public void addComment(String issueKey, String body) {
            throw new AssertionError("Triage must not comment on issues");
        }

        @Override
        public void transitionIssue(String issueKey, String transitionName) {
            throw new AssertionError("Triage must not transition issues");
        }
    }

    static class FixedIssueJira extends UnusedJira {
        String requestedKey;
        final JsonNode raw;

        FixedIssueJira(JsonNode raw) {
            this.raw = raw;
        }

        @Override
        public JiraIssue getIssue(String issueKey) {
            requestedKey = issueKey;
            return new JiraIssue(issueKey, "s", "d", "Open", "Story", List.of(), raw);
        }
    }

    private AgentContext contextWith(JsonNode payload) {
        return new AgentContext(UUID.randomUUID(), "triage", Map.of(), payload,
                Map.of(), List.of("scope_manifest.md"));
    }

    private TriageAgentWorker worker(StubChatModel model, JiraConnector jira) {
        return new TriageAgentWorker(ChatClient.create(model), jira, frontmatterCodec);
    }

    @Test
    void failsWithoutTriggerPayload() {
        assertThatThrownBy(() -> worker(new StubChatModel(), new UnusedJira())
                .execute(contextWith(null)))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Triage requires a trigger payload with an issueKey");
    }

    @Test
    void usesInlineIssueFromPayloadWithoutCallingJira() {
        StubChatModel model = new StubChatModel().enqueue(SCOPE_MANIFEST_JSON);
        JsonNode payload = jsonMapper.readTree("""
                {"issueKey": "ERM-5",
                 "issue": {"fields": {"summary": "Inline issue summary"}}}
                """);

        worker(model, new UnusedJira()).execute(contextWith(payload));

        assertThat(model.receivedPrompts().getFirst().getContents())
                .contains("Inline issue summary");
    }

    @Test
    void fetchesIssueViaJiraWhenPayloadHasNoInlineIssue() {
        StubChatModel model = new StubChatModel().enqueue(SCOPE_MANIFEST_JSON);
        FixedIssueJira jira = new FixedIssueJira(
                jsonMapper.readTree("{\"fields\": {\"summary\": \"Fetched from Jira\"}}"));
        JsonNode payload = jsonMapper.readTree("{\"issueKey\": \"ERM-6\"}");

        worker(model, jira).execute(contextWith(payload));

        assertThat(jira.requestedKey).isEqualTo("ERM-6");
        assertThat(model.receivedPrompts().getFirst().getContents())
                .contains("Fetched from Jira");
    }

    @Test
    void fallsBackToPayloadIssueKeyWhenManifestKeyIsBlank() {
        StubChatModel model = new StubChatModel().enqueue("""
                {"issueKey": "", "summary": "s", "components": [], "endpoints": [],
                 "riskLevel": "low", "ambiguities": [], "analysis": "## A"}
                """);
        JsonNode payload = jsonMapper.readTree("""
                {"issueKey": "ERM-77", "issue": {"fields": {"summary": "x"}}}
                """);

        AgentResult result = worker(model, new UnusedJira()).execute(contextWith(payload));

        Frontmatter parsed = frontmatterCodec.parse(result.outputs().get("scope_manifest.md"));
        assertThat(parsed.metadata().path("issue_key").asString("")).isEqualTo("ERM-77");
    }
}
