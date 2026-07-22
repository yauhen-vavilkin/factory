package org.folio.factory.app.web;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExecutionControllerTest {

    @Mock
    private PipelineExecutionRepository executions;

    @Mock
    private ArtifactStore artifactStore;

    @Mock
    private AuditLog auditLog;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new ExecutionController(executions, artifactStore, auditLog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private PipelineExecution execution() {
        return new PipelineExecution("test-factory", "1.0.0", "{}");
    }

    private Page<PipelineExecution> pageOf(PipelineExecution execution) {
        return new PageImpl<>(List.of(execution), PageRequest.of(0, 50), 1);
    }

    @Test
    void list_noFilters_usesFindAllAndWrapsInEnvelope() throws Exception {
        PipelineExecution execution = execution();
        when(executions.search(any(), any(), any(Pageable.class))).thenCallRealMethod();
        when(executions.findAll(any(Pageable.class))).thenReturn(pageOf(execution));

        mvc.perform(get("/api/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(execution.getId().toString()))
                .andExpect(jsonPath("$.items[0].flowId").value("test-factory"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        verify(executions).findAll(any(Pageable.class));
    }

    @Test
    void list_statusOnly_usesFindByStatus() throws Exception {
        when(executions.search(any(), any(), any(Pageable.class))).thenCallRealMethod();
        when(executions.findByStatus(eq(ExecutionStatus.RUNNING), any(Pageable.class)))
                .thenReturn(pageOf(execution()));

        mvc.perform(get("/api/executions").param("status", "RUNNING"))
                .andExpect(status().isOk());

        verify(executions).findByStatus(eq(ExecutionStatus.RUNNING), any(Pageable.class));
    }

    @Test
    void list_flowIdOnly_usesFindByFlowId() throws Exception {
        when(executions.search(any(), any(), any(Pageable.class))).thenCallRealMethod();
        when(executions.findByFlowId(eq("test-factory"), any(Pageable.class))).thenReturn(pageOf(execution()));

        mvc.perform(get("/api/executions").param("flowId", "test-factory"))
                .andExpect(status().isOk());

        verify(executions).findByFlowId(eq("test-factory"), any(Pageable.class));
    }

    @Test
    void list_statusAndFlowId_usesCombinedFinder() throws Exception {
        when(executions.search(any(), any(), any(Pageable.class))).thenCallRealMethod();
        when(executions.findByStatusAndFlowId(eq(ExecutionStatus.COMPLETED), eq("test-factory"), any(Pageable.class)))
                .thenReturn(pageOf(execution()));

        mvc.perform(get("/api/executions").param("status", "COMPLETED").param("flowId", "test-factory"))
                .andExpect(status().isOk());

        verify(executions).findByStatusAndFlowId(eq(ExecutionStatus.COMPLETED), eq("test-factory"), any(Pageable.class));
    }

    @Test
    void list_invalidStatus_unprocessableWithoutRepositoryAccess() throws Exception {
        mvc.perform(get("/api/executions").param("status", "BOGUS"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value(containsString("BOGUS")));

        verifyNoInteractions(executions);
    }

    @Test
    void list_sizeTooLarge_unprocessable() throws Exception {
        mvc.perform(get("/api/executions").param("size", "201"))
                .andExpect(status().is(422));
        verifyNoInteractions(executions);
    }

    @Test
    void get_malformedUuid_badRequestJsonError() throws Exception {
        mvc.perform(get("/api/executions/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("id")));

        verifyNoInteractions(executions);
    }

    @Test
    void list_sizeTooSmall_unprocessable() throws Exception {
        mvc.perform(get("/api/executions").param("size", "0"))
                .andExpect(status().is(422));
        verifyNoInteractions(executions);
    }

    @Test
    void list_negativePage_unprocessable() throws Exception {
        mvc.perform(get("/api/executions").param("page", "-1"))
                .andExpect(status().is(422));
        verifyNoInteractions(executions);
    }

    @Test
    void get_unknownId_notFound() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        when(executions.findById(id)).thenReturn(Optional.empty());

        mvc.perform(get("/api/executions/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("No execution")));
    }

    @Test
    void get_detail_mapsExecutionArtifactsAndAuditTrail() throws Exception {
        PipelineExecution execution = new PipelineExecution("test-factory", "1.0.0", "{}");
        execution.setErrorMessage("worker exploded");
        UUID id = execution.getId();
        when(executions.findById(id)).thenReturn(Optional.of(execution));
        when(artifactStore.allForExecution(id)).thenReturn(List.of(
                new Artifact(id, "test_plan.md", 2, "text/markdown", "# Plan", "cafebabe", "reviewer:qa")));
        when(auditLog.forExecution(id)).thenReturn(List.of(
                new AuditEvent(id, AuditEventType.ARTIFACT_WRITTEN, "test-spec", "engine", null)));

        mvc.perform(get("/api/executions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.id").value(id.toString()))
                .andExpect(jsonPath("$.execution.flowId").value("test-factory"))
                .andExpect(jsonPath("$.execution.status").value("PENDING"))
                .andExpect(jsonPath("$.errorMessage").value("worker exploded"))
                .andExpect(jsonPath("$.artifacts[0].name").value("test_plan.md"))
                .andExpect(jsonPath("$.artifacts[0].version").value(2))
                .andExpect(jsonPath("$.artifacts[0].content").value("# Plan"))
                .andExpect(jsonPath("$.artifacts[0].createdBy").value("reviewer:qa"))
                .andExpect(jsonPath("$.auditTrail[0].eventType").value("ARTIFACT_WRITTEN"))
                .andExpect(jsonPath("$.auditTrail[0].stepId").value("test-spec"));
    }
}
