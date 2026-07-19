package org.folio.factory.app.web;

import org.folio.factory.core.domain.FlowRegistryEntry;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.registry.model.RetryPolicy;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.registry.model.SubFlowSpec;
import org.folio.factory.core.registry.model.TriggerContract;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FlowControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private FlowRegistry flowRegistry;

    @Mock
    private FlowRegistryEntryRepository mirror;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FlowController(flowRegistry, mirror))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private FlowDescriptor descriptor() {
        StepDescriptor agent = new StepDescriptor("triage", StepType.AGENT, "triage-worker",
                null, null, List.of("$trigger"), List.of("scope_manifest.md"), Map.of("model", "opus"));
        StepDescriptor gate = new StepDescriptor("gate-1", StepType.HITL_GATE, null,
                new HitlGateSpec("gate-1-test-plan", "Review plan", "Check coverage", List.of("test_plan.md")),
                null, List.of(), List.of(), Map.of());
        StepDescriptor sub = new StepDescriptor("child", StepType.SUB_FLOW, null, null,
                new SubFlowSpec("other-flow", Map.of("parent_a", "child_a"), Map.of("child_b", "parent_b")),
                List.of(), List.of(), Map.of());
        return new FlowDescriptor("test-factory", "Test Factory", "1.0.0",
                List.of(new TriggerContract("jira.issue.updated",
                        Map.of("/issue/fields/status/name", "Ready for QA"))),
                json.readTree("{\"type\":\"object\"}"), json.readTree("{\"type\":\"string\"}"),
                List.of(agent, gate, sub), new RetryPolicy(5, List.of(10L, 20L)));
    }

    @Test
    void list_reportsSummaryCounts() throws Exception {
        when(flowRegistry.all()).thenReturn(List.of(descriptor()));

        mvc.perform(get("/api/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("test-factory"))
                .andExpect(jsonPath("$[0].name").value("Test Factory"))
                .andExpect(jsonPath("$[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].stepCount").value(3))
                .andExpect(jsonPath("$[0].gateCount").value(1))
                .andExpect(jsonPath("$[0].triggers[0].eventType").value("jira.issue.updated"))
                .andExpect(jsonPath("$[0].triggers[0].filters['/issue/fields/status/name']").value("Ready for QA"));
    }

    @Test
    void detail_withMirrorRow_includesRawYamlAndSteps() throws Exception {
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));
        FlowRegistryEntry entry = new FlowRegistryEntry("test-factory", "1.0.0", "Test Factory",
                "sha", "id: test-factory\n");
        when(mirror.findById(new FlowRegistryEntry.Key("test-factory", "1.0.0"))).thenReturn(Optional.of(entry));

        mvc.perform(get("/api/flows/{id}", "test-factory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("test-factory"))
                .andExpect(jsonPath("$.inputSchema.type").value("object"))
                .andExpect(jsonPath("$.outputSchema.type").value("string"))
                .andExpect(jsonPath("$.retryPolicy.maxAttempts").value(5))
                .andExpect(jsonPath("$.retryPolicy.backoffSeconds[0]").value(10))
                .andExpect(jsonPath("$.steps[0].index").value(0))
                .andExpect(jsonPath("$.steps[0].type").value("AGENT"))
                .andExpect(jsonPath("$.steps[0].workerId").value("triage-worker"))
                .andExpect(jsonPath("$.steps[0].inputs[0]").value("$trigger"))
                .andExpect(jsonPath("$.steps[0].config.model").value("opus"))
                .andExpect(jsonPath("$.steps[1].type").value("HITL_GATE"))
                .andExpect(jsonPath("$.steps[1].gate.gateId").value("gate-1-test-plan"))
                .andExpect(jsonPath("$.steps[1].gate.reviewedArtifacts[0]").value("test_plan.md"))
                .andExpect(jsonPath("$.steps[2].type").value("SUB_FLOW"))
                .andExpect(jsonPath("$.steps[2].subFlow.flowId").value("other-flow"))
                .andExpect(jsonPath("$.steps[2].subFlow.inputMapping['parent_a']").value("child_a"))
                .andExpect(jsonPath("$.rawYaml").value("id: test-factory\n"))
                .andExpect(jsonPath("$.registeredAt").exists());
    }

    @Test
    void detail_withoutMirrorRow_rawYamlNull() throws Exception {
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));
        when(mirror.findById(new FlowRegistryEntry.Key("test-factory", "1.0.0"))).thenReturn(Optional.empty());

        mvc.perform(get("/api/flows/{id}", "test-factory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawYaml").value(nullValue()))
                .andExpect(jsonPath("$.registeredAt").value(nullValue()));
    }

    @Test
    void detail_unknownFlow_notFound() throws Exception {
        when(flowRegistry.find("ghost")).thenReturn(Optional.empty());

        mvc.perform(get("/api/flows/{id}", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ghost")));
    }
}
