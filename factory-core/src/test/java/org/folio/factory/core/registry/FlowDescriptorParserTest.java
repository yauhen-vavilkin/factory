package org.folio.factory.core.registry;

import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowDescriptorParserTest {

    private final FlowDescriptorParser parser = new FlowDescriptorParser();

    private static final String VALID_YAML = """
            id: sample-flow
            name: Sample Flow
            version: 1.0.0
            triggers:
              - event_type: manual
              - event_type: jira.issue.transitioned
                filters:
                  "/issue/fields/status/name": "Ready for QA"
            input_schema:
              required: [issueKey]
            output_schema:
              artifacts: [result.md]
            retry_policy:
              max_attempts: 2
              backoff_seconds: [10, 60]
            agent_chain:
              - step_id: work
                type: AGENT
                worker_id: echo-worker
                inputs: ["$trigger"]
                outputs: [result.md]
                config:
                  mode: fast
              - step_id: review
                type: HITL_GATE
                gate:
                  gate_id: gate-1
                  title: "Review result"
                  review_instructions: "Check it"
                  reviewed_artifacts: [result.md]
              - step_id: delegate
                type: SUB_FLOW
                sub_flow:
                  flow_id: child-flow
                  input_mapping:
                    result.md: input.md
                  output_mapping:
                    child_result.md: final.md
            """;

    @Test
    void parsesFullDescriptor() {
        FlowDescriptor flow = parser.parse(VALID_YAML, "sample.yaml");

        assertThat(flow.id()).isEqualTo("sample-flow");
        assertThat(flow.triggers()).hasSize(2);
        assertThat(flow.triggers().get(1).filters())
                .containsEntry("/issue/fields/status/name", "Ready for QA");
        assertThat(flow.retryPolicy().maxAttempts()).isEqualTo(2);
        assertThat(flow.retryPolicy().backoffFor(1)).isEqualTo(10);
        assertThat(flow.retryPolicy().backoffFor(5)).isEqualTo(60);
        assertThat(flow.agentChain()).hasSize(3);
        assertThat(flow.step(0).type()).isEqualTo(StepType.AGENT);
        assertThat(flow.step(0).wantsTriggerPayload()).isTrue();
        assertThat(flow.step(0).config()).containsEntry("mode", "fast");
        assertThat(flow.step(1).gate().reviewedArtifacts()).containsExactly("result.md");
        assertThat(flow.step(2).subFlow().inputMapping()).containsEntry("result.md", "input.md");
        assertThat(flow.inputSchema().get("required").get(0).asString()).isEqualTo("issueKey");
    }

    @Test
    void defaultsRetryPolicyWhenAbsent() {
        String yaml = """
                id: f
                name: F
                version: 1.0.0
                agent_chain:
                  - step_id: s
                    type: AGENT
                    worker_id: w
                """;
        FlowDescriptor flow = parser.parse(yaml, "f.yaml");
        assertThat(flow.retryPolicy().maxAttempts()).isEqualTo(3);
        assertThat(flow.retryPolicy().backoffFor(3)).isEqualTo(300);
    }

    @Test
    void rejectsAgentStepWithoutWorkerId() {
        String yaml = """
                id: f
                name: F
                version: 1.0.0
                agent_chain:
                  - step_id: s
                    type: AGENT
                """;
        assertThatThrownBy(() -> parser.parse(yaml, "f.yaml"))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("worker_id");
    }

    @Test
    void rejectsDuplicateStepIds() {
        String yaml = """
                id: f
                name: F
                version: 1.0.0
                agent_chain:
                  - step_id: s
                    type: AGENT
                    worker_id: w
                  - step_id: s
                    type: AGENT
                    worker_id: w2
                """;
        assertThatThrownBy(() -> parser.parse(yaml, "f.yaml"))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("duplicate step_id");
    }

    @Test
    void rejectsUnknownProperties() {
        String yaml = """
                id: f
                name: F
                version: 1.0.0
                agent_chainz:
                  - step_id: s
                """;
        assertThatThrownBy(() -> parser.parse(yaml, "f.yaml"))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("Cannot parse");
    }

    @Test
    void rejectsReservedEscalationGateId() {
        String yaml = """
                id: f
                name: F
                version: 1.0.0
                agent_chain:
                  - step_id: a
                    type: AGENT
                    worker_id: w
                  - step_id: g
                    type: HITL_GATE
                    gate:
                      gate_id: escalation
                      title: "Sneaky"
                """;
        assertThatThrownBy(() -> parser.parse(yaml, "f.yaml"))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void rejectsHitlGateWithoutGateId() {
        String yaml = """
                id: f
                name: F
                version: 1.0.0
                agent_chain:
                  - step_id: a
                    type: AGENT
                    worker_id: w
                  - step_id: g
                    type: HITL_GATE
                """;
        assertThatThrownBy(() -> parser.parse(yaml, "f.yaml"))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("gate.gate_id");
    }
}
