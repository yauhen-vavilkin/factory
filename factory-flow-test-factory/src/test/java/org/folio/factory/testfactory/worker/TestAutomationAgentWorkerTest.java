package org.folio.factory.testfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.StubChatModel;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.testfactory.TestFactoryProperties;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAutomationAgentWorkerTest {

    private static final String SCRIPT_BUNDLE_JSON = """
            {"framework": "karate",
             "files": [
               {"path": "features/agreements.feature",
                "caseIds": ["TC-01"],
                "content": "Feature: Agreement name validation\\n\\n  Scenario: TC-01 create agreement\\n    Then status 201\\n"}
             ]}
            """;

    private final FrontmatterCodec frontmatterCodec = new FrontmatterCodec();
    private final ScriptBundleCodec bundleCodec = new ScriptBundleCodec(frontmatterCodec);

    private TestAutomationAgentWorker worker(StubChatModel model, String baseUrl) {
        TestFactoryProperties properties = new TestFactoryProperties(
                new TestFactoryProperties.Execution(baseUrl, null), "o/r", "main", null, 55L);
        return new TestAutomationAgentWorker(ChatClient.create(model), bundleCodec, properties);
    }

    private AgentContext context() {
        String plan = frontmatterCodec.render(Map.of("issue_key", "ERM-1001"), "# Test Plan");
        return new AgentContext(UUID.randomUUID(), "automation", Map.of(
                "test_plan.md", new ArtifactContent("test_plan.md", 1, "text/markdown", plan)),
                null, Map.of("framework", "karate"), List.of("test_scripts.md"));
    }

    @Test
    void failsWhenModelProducesNoScriptFiles() {
        StubChatModel model = new StubChatModel()
                .enqueue("{\"framework\": \"karate\", \"files\": []}");

        assertThatThrownBy(() -> worker(model, "http://folio-ref:9130").execute(context()))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Test automation produced no script files");
    }

    @Test
    void defaultsBaseUrlToLocalhostWhenExecutionIsUnconfigured() {
        StubChatModel model = new StubChatModel().enqueue(SCRIPT_BUNDLE_JSON);

        worker(model, " ").execute(context());

        assertThat(model.receivedPrompts().getFirst().getContents())
                .contains("Target environment base URL: http://localhost:8080");
    }

    @Test
    void sendsConfiguredBaseUrlAndNormalisesFrameworkToStepConfig() {
        StubChatModel model = new StubChatModel().enqueue("""
                {"framework": "restassured",
                 "files": [
                   {"path": "features/agreements.feature",
                    "caseIds": ["TC-01"],
                    "content": "Feature: A\\n  Scenario: TC-01 works\\n"}
                 ]}
                """);

        AgentResult result = worker(model, "https://folio-ref.example.org").execute(context());

        assertThat(model.receivedPrompts().getFirst().getContents())
                .contains("Target environment base URL: https://folio-ref.example.org");
        // The step config's framework wins over whatever the model claims.
        ScriptBundle rendered = bundleCodec.parse(result.outputs().get("test_scripts.md"));
        assertThat(rendered.framework()).isEqualTo("karate");
    }
}
