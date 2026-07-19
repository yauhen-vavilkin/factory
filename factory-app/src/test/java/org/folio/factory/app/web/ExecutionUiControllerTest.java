package org.folio.factory.app.web;

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
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class ExecutionUiControllerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
                .standaloneSetup(new ExecutionUiController(executions, artifactStore, auditLog))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    @Test
    void executions_list_rendersViewWithExecutions() throws Exception {
        PipelineExecution execution = new PipelineExecution("test-factory", "1", "{}");
        when(executions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(execution));

        mvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(view().name("executions"))
                .andExpect(model().attribute("executions", List.of(execution)));
    }

    @Test
    void execution_detail_rendersViewWithArtifactsAndPreFormattedAudit() throws Exception {
        PipelineExecution execution = new PipelineExecution("test-factory", "1", "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(artifactStore.allForExecution(EXECUTION_ID)).thenReturn(List.of());
        AuditEvent event = new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "triage", "engine", null);
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(event));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("execution"))
                .andExpect(model().attribute("execution", execution))
                .andExpect(model().attributeExists("artifacts"))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("events");
        assertThat(events).hasSize(1);
        Map<String, Object> row = events.get(0);
        assertThat(row.get("eventType")).isEqualTo(AuditEventType.STEP_COMPLETED);
        assertThat(row.get("stepId")).isEqualTo("triage");
        assertThat(row.get("actor")).isEqualTo("engine");
        assertThat(row.get("occurredAt").toString()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }
}
