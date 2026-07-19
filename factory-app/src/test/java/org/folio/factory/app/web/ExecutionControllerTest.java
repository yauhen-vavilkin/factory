package org.folio.factory.app.web;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
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
        mvc = MockMvcBuilders.standaloneSetup(new ExecutionController(executions, artifactStore, auditLog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
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
