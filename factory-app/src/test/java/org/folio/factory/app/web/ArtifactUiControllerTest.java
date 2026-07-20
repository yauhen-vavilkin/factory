package org.folio.factory.app.web;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.repository.ArtifactRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class ArtifactUiControllerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Mock
    private ArtifactRepository artifacts;

    @Mock
    private PipelineExecutionRepository executions;

    @Mock
    private FlowRegistry flowRegistry;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new ArtifactUiController(artifacts, executions, flowRegistry))
                .setControllerAdvice(new UiExceptionHandler())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    private static Artifact artifact(String name, int version) {
        return new Artifact(EXECUTION_ID, name, version, "text/markdown", "# Plan\n", "sha", "test-spec");
    }

    private static Slice<Artifact> sliceOf(Artifact... rows) {
        return new SliceImpl<>(List.of(rows), PageRequest.of(0, 50), false);
    }

    private void stubFlowRegistry() {
        StepDescriptor agent = new StepDescriptor("test-spec", StepType.AGENT, "test-spec-agent",
                null, null, null, List.of("test_plan.md"), null);
        when(flowRegistry.all()).thenReturn(List.of(
                new FlowDescriptor("test-factory", "Test Factory", "1", null, null, null, List.of(agent), null)));
    }

    @Test
    void artifacts_noFilter_usesUnfilteredSliceAndMapsRows() throws Exception {
        stubFlowRegistry();
        when(artifacts.findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class)))
                .thenReturn(sliceOf(artifact("test_plan.md", 2)));
        PipelineExecution execution = new PipelineExecution("test-factory", "1", "{}");
        when(executions.findAllById(List.of(EXECUTION_ID))).thenReturn(List.of(execution));

        var result = mvc.perform(get("/artifacts"))
                .andExpect(status().isOk())
                .andExpect(view().name("artifacts"))
                .andExpect(model().attribute("selectedName", ""))
                .andExpect(model().attribute("names", List.of("test_plan.md")))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("artifacts");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("name", "test_plan.md")
                .containsEntry("version", 2)
                .containsEntry("createdBy", "test-spec")
                .containsEntry("executionId", EXECUTION_ID);
        assertThat(rows.get(0).get("executionShort").toString()).isNotBlank();
        // The stubbed execution entity has no persisted id, so the flow id lookup
        // legitimately misses — rendered as a muted dash.
        assertThat(rows.get(0).get("flowId")).isNull();
    }

    @Test
    void artifacts_nameFilter_dispatchesToNamedFinder() throws Exception {
        stubFlowRegistry();
        when(artifacts.findByNameOrderByCreatedAtDescIdDesc(eq("test_plan.md"), any(Pageable.class)))
                .thenReturn(sliceOf());
        when(executions.findAllById(List.of())).thenReturn(List.of());

        mvc.perform(get("/artifacts").param("name", "test_plan.md"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedName", "test_plan.md"))
                .andExpect(model().attribute("filtersActive", true));

        verify(artifacts).findByNameOrderByCreatedAtDescIdDesc(eq("test_plan.md"), any(Pageable.class));
    }

    @Test
    void artifacts_nonPositiveSize_fallsBackToDefaultPageSize() throws Exception {
        stubFlowRegistry();
        when(artifacts.findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class))).thenReturn(sliceOf());
        when(executions.findAllById(List.of())).thenReturn(List.of());

        mvc.perform(get("/artifacts").param("size", "0"))
                .andExpect(status().isOk());

        verify(artifacts).findAllByOrderByCreatedAtDescIdDesc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void artifact_detail_carriesContentAndMeta() throws Exception {
        when(artifacts.findById(ARTIFACT_ID)).thenReturn(Optional.of(artifact("test_plan.md", 1)));
        when(executions.findAllById(List.of(EXECUTION_ID))).thenReturn(List.of());

        var result = mvc.perform(get("/artifacts/{id}", ARTIFACT_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("artifact"))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> row =
                (Map<String, Object>) result.getModelAndView().getModel().get("artifact");
        assertThat(row)
                .containsEntry("name", "test_plan.md")
                .containsEntry("version", 1)
                .containsEntry("contentType", "text/markdown")
                .containsEntry("sha256", "sha")
                .containsEntry("artifactContent", "# Plan\n");
    }

    @Test
    void artifact_unknownId_rendersNotFound() throws Exception {
        UUID unknown = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        when(artifacts.findById(unknown)).thenReturn(Optional.empty());

        mvc.perform(get("/artifacts/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }
}
