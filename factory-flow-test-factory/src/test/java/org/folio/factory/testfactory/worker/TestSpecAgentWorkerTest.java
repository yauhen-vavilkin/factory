package org.folio.factory.testfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.llm.StubChatModel;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSpecAgentWorkerTest {

    private static final String TEST_PLAN_JSON = """
            {"issueKey": "ERM-1001",
             "cases": [
               {"id": "TC-01", "title": "Create agreement with valid name", "priority": "high",
                "type": "automatable", "targetEndpoint": "/erm/sas",
                "preconditions": ["Clean test tenant"],
                "steps": ["POST /erm/sas with name 'Test Agreement 001'", "Read back the agreement"],
                "expected": "201 Created with agreement id", "acceptanceCriteriaRef": "AC1"},
               {"id": "TC-02", "title": "Reject agreement without name", "priority": "high",
                "type": "manual", "targetEndpoint": null,
                "preconditions": [],
                "steps": ["POST /erm/sas with empty body"],
                "expected": "422 Unprocessable Entity", "acceptanceCriteriaRef": null}
             ]}
            """;

    private final FrontmatterCodec frontmatterCodec = new FrontmatterCodec();

    private TestSpecAgentWorker worker(StubChatModel model) {
        return new TestSpecAgentWorker(ChatClient.create(model), frontmatterCodec,
                JsonMapper.builder().build());
    }

    private AgentContext context() {
        String manifest = frontmatterCodec.render(Map.of("issue_key", "ERM-1001"), "## Analysis");
        return new AgentContext(UUID.randomUUID(), "spec", Map.of(
                "scope_manifest.md", new ArtifactContent("scope_manifest.md", 1, "text/markdown", manifest)),
                null, Map.of(), List.of("test_plan.md"));
    }

    @Test
    void failsWhenModelProducesNoTestCases() {
        StubChatModel model = new StubChatModel()
                .enqueue("{\"issueKey\": \"ERM-1001\", \"cases\": []}");

        assertThatThrownBy(() -> worker(model).execute(context()))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Test specification produced no test cases");
    }

    @Test
    void rendersPlanWithCaseCountAndHumanReadableBody() {
        StubChatModel model = new StubChatModel().enqueue(TEST_PLAN_JSON);

        AgentResult result = worker(model).execute(context());

        Frontmatter parsed = frontmatterCodec.parse(result.outputs().get("test_plan.md"));
        assertThat(parsed.metadata().path("case_count").asInt()).isEqualTo(2);
        assertThat(parsed.body())
                .contains("# Test Plan — ERM-1001")
                .contains("## TC-01: Create agreement with valid name")
                .contains("- **Priority:** high")
                .contains("- **Type:** automatable")
                .contains("- **Acceptance criteria:** AC1")
                .contains("**Preconditions**\n- Clean test tenant")
                .contains("1. POST /erm/sas with name 'Test Agreement 001'")
                .contains("2. Read back the agreement")
                .contains("**Expected:** 201 Created with agreement id")
                .contains("## TC-02: Reject agreement without name")
                .contains("**Expected:** 422 Unprocessable Entity");
        // TC-02 has no endpoint, so the optional line appears only once (for TC-01).
        assertThat(parsed.body()).containsOnlyOnce("- **Endpoint:** `/erm/sas`");
        assertThat(parsed.body()).containsOnlyOnce("**Endpoint:**");
        assertThat(parsed.body()).containsOnlyOnce("**Acceptance criteria:**");
    }
}
