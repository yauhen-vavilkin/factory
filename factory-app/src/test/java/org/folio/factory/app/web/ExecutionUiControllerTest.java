package org.folio.factory.app.web;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.limits.DailyBudgetExceededException;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.FlowValidationException;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.ExecutionActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import tools.jackson.databind.json.JsonMapper;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class ExecutionUiControllerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private PipelineExecutionRepository executions;

    @Mock
    private ArtifactStore artifactStore;

    @Mock
    private AuditLog auditLog;

    @Mock
    private FlowRegistry flowRegistry;

    @Mock
    private HitlReviewRepository reviews;

    @Mock
    private ExecutionActionService actionService;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new ExecutionUiController(executions, artifactStore, auditLog,
                        flowRegistry, reviews, actionService, json))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    // ----- list filter dispatch -----

    @Test
    void executions_noFilters_usesFindAll() throws Exception {
        when(executions.findAll(any(Pageable.class))).thenReturn(emptyPage());

        mvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(view().name("executions"))
                .andExpect(model().attributeExists("executions", "page", "statuses", "flows"));

        verify(executions).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
    }

    @Test
    void executions_statusFilter_dispatchesToFindByStatus() throws Exception {
        when(executions.findByStatus(eq(ExecutionStatus.RUNNING), any())).thenReturn(emptyPage());

        mvc.perform(get("/executions").param("status", "RUNNING"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedStatus", "RUNNING"));

        verify(executions).findByStatus(eq(ExecutionStatus.RUNNING), any());
    }

    @Test
    void executions_flowFilter_dispatchesToFindByFlowId() throws Exception {
        when(executions.findByFlowId(eq("test-factory"), any())).thenReturn(emptyPage());

        mvc.perform(get("/executions").param("flow", "test-factory"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedFlow", "test-factory"));

        verify(executions).findByFlowId(eq("test-factory"), any());
    }

    @Test
    void executions_statusAndFlow_dispatchesToCombinedQuery() throws Exception {
        when(executions.findByStatusAndFlowId(eq(ExecutionStatus.COMPLETED), eq("test-factory"), any()))
                .thenReturn(emptyPage());

        mvc.perform(get("/executions").param("status", "COMPLETED").param("flow", "test-factory"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("filtersActive", true));

        verify(executions).findByStatusAndFlowId(eq(ExecutionStatus.COMPLETED), eq("test-factory"), any());
    }

    @Test
    void executions_invalidStatus_treatedAsAll() throws Exception {
        when(executions.findAll(any(Pageable.class))).thenReturn(emptyPage());

        mvc.perform(get("/executions").param("status", "NONSENSE"))
                .andExpect(status().isOk());

        verify(executions).findAll(any(Pageable.class));
    }

    // ----- detail: stepper state mapping -----

    @Test
    void execution_detail_mapsStepperStatesAndAttempts() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.AWAITING_HITL, 1,
                "{\"triage\":0,\"gate-1\":2}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("execution"))
                .andReturn();

        List<Map<String, Object>> steps = steps(result);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0))
                .containsEntry("state", "done")
                .containsEntry("label", "triage-worker")
                .containsEntry("attempts", 0);
        assertThat(steps.get(1))
                .containsEntry("state", "current")
                .containsEntry("label", "QA gate 1")
                .containsEntry("sublabel", "gate-1 · HITL_GATE")
                .containsEntry("attempts", 2);
        assertThat(steps.get(2))
                .containsEntry("state", "pending")
                .containsEntry("label", "finalizer-worker");
    }

    @Test
    void execution_detail_escalatedCurrentStepMarkedFailed() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.FAILED_ESCALATED, 1, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn();

        assertThat(steps(result).get(1)).containsEntry("state", "failed");
    }

    @Test
    void execution_detail_escalatedSurfacesPendingReview() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.FAILED_ESCALATED, 1, "{}");
        HitlReview pending = new HitlReview(EXECUTION_ID, "escalation", 1, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(reviews.findByExecutionIdOrderByCreatedAtAsc(EXECUTION_ID)).thenReturn(List.of(pending));

        mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pendingReview", pending));
    }

    @Test
    void execution_detail_noStepperWhenFlowUnregistered() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.RUNNING, 0, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.empty());

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn();

        assertThat(steps(result)).isEmpty();
    }

    // ----- detail: artifact version grouping -----

    @Test
    void execution_detail_groupsArtifactsByNameWithLatestFlagged() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.COMPLETED, 3, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(artifactStore.allForExecution(EXECUTION_ID)).thenReturn(List.of(
                artifact("coverage.md", 1, "cov", "test-exec"),
                artifact("test_plan.md", 1, "v1 body", "test-spec"),
                artifact("test_plan.md", 2, "v2 body", "qa")));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("artifactGroups");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("name")).isEqualTo("coverage.md");
        assertThat(groups.get(1).get("name")).isEqualTo("test_plan.md");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> planVersions = (List<Map<String, Object>>) groups.get(1).get("versions");
        assertThat(planVersions).hasSize(2);
        assertThat(planVersions.get(0)).containsEntry("version", 1).containsEntry("latest", false);
        assertThat(planVersions.get(1)).containsEntry("version", 2).containsEntry("latest", true);
        assertThat(planVersions.get(1).get("label").toString()).contains("v2").contains("qa");
        assertThat(planVersions.get(0).get("panelId")).isNotEqualTo(planVersions.get(1).get("panelId"));
    }

    // ----- detail: pending review / actions flags -----

    @Test
    void execution_detail_exposesPendingReviewAndActionFlags() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.AWAITING_HITL, 1, "{}");
        HitlReview pending = new HitlReview(EXECUTION_ID, "qa-1", 1, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(reviews.findByExecutionIdOrderByCreatedAtAsc(EXECUTION_ID)).thenReturn(List.of(pending));

        mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pendingReview", pending))
                .andExpect(model().attribute("canCancel", true))
                .andExpect(model().attribute("canRerun", false))
                .andExpect(model().attribute("pollUrl", "/api/executions/" + EXECUTION_ID));
    }

    @Test
    void execution_detail_terminalRunAllowsRerunNotCancelAndDoesNotPoll() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.COMPLETED, 3, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));

        mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("canCancel", false))
                .andExpect(model().attribute("canRerun", true))
                .andExpect(model().attribute("pollUrl", (Object) null));
    }

    // ----- actions -----

    @Test
    void cancel_delegatesToServiceAndFlashRedirectsToSamePage() throws Exception {
        when(actionService.cancel(EXECUTION_ID, "qa", "stuck"))
                .thenReturn(execution(ExecutionStatus.CANCELLED, 1, "{}"));

        mvc.perform(post("/executions/{id}/cancel", EXECUTION_ID)
                        .param("actor", "qa").param("reason", "stuck"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/executions/" + EXECUTION_ID))
                .andExpect(flash().attribute("message", "Execution cancelled"));

        verify(actionService).cancel(EXECUTION_ID, "qa", "stuck");
    }

    @Test
    void cancel_illegalState_redirectsWithEncodedError() throws Exception {
        when(actionService.cancel(EXECUTION_ID, "qa", null))
                .thenThrow(new IllegalStateException("already CANCELLED"));

        mvc.perform(post("/executions/{id}/cancel", EXECUTION_ID).param("actor", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/executions/" + EXECUTION_ID + "?error=")
                        .contains("already"));
    }

    @Test
    void cancel_blankActor_serviceRejects_redirectsWithError() throws Exception {
        when(actionService.cancel(EXECUTION_ID, "", null))
                .thenThrow(new IllegalArgumentException("actor is required"));

        mvc.perform(post("/executions/{id}/cancel", EXECUTION_ID).param("actor", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/executions/" + EXECUTION_ID + "?error="));
    }

    @Test
    void rerun_delegatesAndRedirectsToNewExecution() throws Exception {
        UUID newId = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        when(actionService.rerun(EXECUTION_ID, "qa")).thenReturn(newId);

        mvc.perform(post("/executions/{id}/rerun", EXECUTION_ID).param("actor", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/executions/" + newId))
                .andExpect(flash().attribute("message", "Execution re-started"));

        verify(actionService).rerun(EXECUTION_ID, "qa");
    }

    @Test
    void rerun_flowValidation_redirectsWithEncodedError() throws Exception {
        when(actionService.rerun(EXECUTION_ID, "qa"))
                .thenThrow(new FlowValidationException("flow 'gone' is not registered"));

        mvc.perform(post("/executions/{id}/rerun", EXECUTION_ID).param("actor", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/executions/" + EXECUTION_ID + "?error="));
    }

    @Test
    void rerun_budgetExceeded_redirectsWithEncodedError() throws Exception {
        when(actionService.rerun(EXECUTION_ID, "qa"))
                .thenThrow(new DailyBudgetExceededException("daily execution budget reached"));

        mvc.perform(post("/executions/{id}/rerun", EXECUTION_ID).param("actor", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/executions/" + EXECUTION_ID + "?error="));
    }

    @Test
    void executions_baseUrl_urlEncodesFilterValues() throws Exception {
        when(executions.findByFlowId(eq("a b&c"), any())).thenReturn(emptyPage());

        var result = mvc.perform(get("/executions").param("flow", "a b&c"))
                .andExpect(status().isOk())
                .andReturn();

        String baseUrl = (String) result.getModelAndView().getModel().get("baseUrl");
        assertThat(baseUrl).contains("flow=a+b%26c").doesNotContain("a b&c");
    }

    // ----- helpers -----

    private static Page<PipelineExecution> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    }

    private static List<Map<String, Object>> steps(org.springframework.test.web.servlet.MvcResult result) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("steps");
        return steps;
    }

    private static PipelineExecution execution(ExecutionStatus status, int stepIndex, String retryCounts) {
        PipelineExecution execution = new PipelineExecution("test-factory", "1", "{}");
        execution.setStatus(status);
        execution.setCurrentStepIndex(stepIndex);
        execution.setRetryCounts(retryCounts);
        return execution;
    }

    private static Artifact artifact(String name, int version, String content, String createdBy) {
        return new Artifact(EXECUTION_ID, name, version, "text/markdown", content, "sha", createdBy);
    }

    private static FlowDescriptor descriptor() {
        StepDescriptor triage = new StepDescriptor("triage", StepType.AGENT, "triage-worker",
                null, null, null, null, null);
        StepDescriptor gate = new StepDescriptor("gate-1", StepType.HITL_GATE, null,
                new HitlGateSpec("qa-1", "QA gate 1", "instructions", null), null, null, null, null);
        StepDescriptor finalizer = new StepDescriptor("finalizer", StepType.AGENT, "finalizer-worker",
                null, null, null, null, null);
        return new FlowDescriptor("test-factory", "Test Factory", "1", null, null, null,
                List.of(triage, gate, finalizer), null);
    }
}
