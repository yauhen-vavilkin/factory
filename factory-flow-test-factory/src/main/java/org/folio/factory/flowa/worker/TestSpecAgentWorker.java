package org.folio.factory.flowa.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.flowa.model.TestPlan;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns an approved scope manifest into structured manual test cases covering
 * positive, edge and negative paths — {@code test_plan.md}.
 */
public class TestSpecAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "test-spec-agent";

    private final FrontmatterCodec frontmatterCodec;
    private final JsonMapper jsonMapper;

    public TestSpecAgentWorker(ChatClient chatClient, FrontmatterCodec frontmatterCodec, JsonMapper jsonMapper) {
        super(chatClient);
        this.frontmatterCodec = frontmatterCodec;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String scopeManifest = context.requireInput("scope_manifest.md").content();

        TestPlan plan = callForEntity(Map.of("scope_manifest", scopeManifest), TestPlan.class);
        if (plan.cases().isEmpty()) {
            throw new AgentExecutionException("Test specification produced no test cases");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issue_key", plan.issueKey());
        metadata.put("case_count", plan.cases().size());
        metadata.put("cases", jsonMapper.convertValue(plan.cases(), Object.class));
        return AgentResult.of("test_plan.md", frontmatterCodec.render(metadata, renderBody(plan)));
    }

    private String renderBody(TestPlan plan) {
        StringBuilder body = new StringBuilder("# Test Plan — ").append(plan.issueKey()).append("\n");
        for (TestPlan.TestCase testCase : plan.cases()) {
            body.append("\n## ").append(testCase.id()).append(": ").append(testCase.title()).append("\n\n")
                    .append("- **Priority:** ").append(testCase.priority()).append("\n")
                    .append("- **Type:** ").append(testCase.type()).append("\n");
            if (testCase.targetEndpoint() != null) {
                body.append("- **Endpoint:** `").append(testCase.targetEndpoint()).append("`\n");
            }
            if (testCase.acceptanceCriteriaRef() != null) {
                body.append("- **Acceptance criteria:** ").append(testCase.acceptanceCriteriaRef()).append("\n");
            }
            if (!testCase.preconditions().isEmpty()) {
                body.append("\n**Preconditions**\n");
                testCase.preconditions().forEach(p -> body.append("- ").append(p).append("\n"));
            }
            body.append("\n**Steps**\n");
            for (int i = 0; i < testCase.steps().size(); i++) {
                body.append(i + 1).append(". ").append(testCase.steps().get(i)).append("\n");
            }
            body.append("\n**Expected:** ").append(testCase.expected()).append("\n");
        }
        return body.toString();
    }
}
