package org.folio.factory.flowa.worker;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.flowa.FlowAProperties;
import org.folio.factory.flowa.artifact.ScriptBundleCodec;
import org.folio.factory.flowa.model.ScriptBundle;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

/**
 * Transforms the approved test plan into executable test scripts. Milestone 1
 * targets Karate (plain-text .feature files, standalone runner); the framework
 * remains step configuration so future generators can slot in.
 */
public class TestAutomationAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "test-automation-agent";

    private final ScriptBundleCodec bundleCodec;
    private final FlowAProperties properties;

    public TestAutomationAgentWorker(ChatClient chatClient, ScriptBundleCodec bundleCodec,
                                     FlowAProperties properties) {
        super(chatClient);
        this.bundleCodec = bundleCodec;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String testPlan = context.requireInput("test_plan.md").content();
        String framework = context.configString("framework", "karate");
        String baseUrl = properties.execution().baseUrl() == null || properties.execution().baseUrl().isBlank()
                ? "http://localhost:8080" : properties.execution().baseUrl();

        ScriptBundle bundle = callForEntity(Map.of(
                "test_plan", testPlan,
                "framework", framework,
                "base_url", baseUrl), ScriptBundle.class);
        if (bundle.files().isEmpty()) {
            throw new AgentExecutionException("Test automation produced no script files");
        }
        ScriptBundle normalised = new ScriptBundle(framework, bundle.files());
        return AgentResult.of("test_scripts.md", bundleCodec.render(normalised));
    }
}
