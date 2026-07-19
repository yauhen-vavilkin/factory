package org.folio.factory.app.web;

import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.repository.AuditEventRepository;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class DashboardUiControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private FlowRegistry flowRegistry;

    @Mock
    private FlowRegistryEntryRepository flowRegistryEntries;

    @Mock
    private AuditEventRepository audit;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private record FakeConnector(String connectorName, boolean isConfigured) implements ConnectorHealth {
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        EngineProperties engine = new EngineProperties(true, 2000L, 5, 4, 1800L, 30, true);
        List<ConnectorHealth> connectors =
                List.of(new FakeConnector("jira", true), new FakeConnector("github", false));
        DashboardUiController controller = new DashboardUiController(
                connectors, flowRegistry, engine, flowRegistryEntries, audit, json);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    @Test
    void status_rendersEngineConnectorAndFlowRows() throws Exception {
        when(flowRegistry.all()).thenReturn(List.of(
                new FlowDescriptor("test-factory", "Test Factory", "1.0.0", null, null, null, null, null)));
        when(flowRegistryEntries.findById(any())).thenReturn(java.util.Optional.empty());

        var result = mvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(view().name("status"))
                .andExpect(model().attributeExists("engine", "connectors", "flows"))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> engine = (Map<String, Object>) result.getModelAndView().getModel().get("engine");
        assertThat(engine.get("enabled")).isEqualTo(true);
        assertThat(engine.get("pollIntervalMs")).isEqualTo(2000L);
        assertThat(engine.get("batchSize")).isEqualTo(5);
        assertThat(engine.get("workerThreads")).isEqualTo(4);
        assertThat(engine.get("leaseTimeoutSeconds")).isEqualTo(1800L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connectors =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("connectors");
        assertThat(connectors).extracting(c -> c.get("name")).containsExactly("jira", "github");
        assertThat(connectors).extracting(c -> c.get("configured")).containsExactly(true, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flows =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("flows");
        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).get("id")).isEqualTo("test-factory");
        assertThat(flows.get(0).get("name")).isEqualTo("Test Factory");
        assertThat(flows.get(0).get("version")).isEqualTo("1.0.0");
    }

    @Test
    void audit_noEventType_usesUnfilteredPagedQuery() throws Exception {
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(emptyPage(0, 50));

        mvc.perform(get("/audit"))
                .andExpect(status().isOk())
                .andExpect(view().name("audit"))
                .andExpect(model().attributeExists("events", "page", "eventTypes"));

        verify(audit).findAllByOrderByIdDesc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void audit_validEventType_dispatchesToTypedQueryWithPageable() throws Exception {
        when(audit.findByEventTypeOrderByIdDesc(eq(AuditEventType.STEP_COMPLETED), any()))
                .thenReturn(emptyPage(2, 10));

        mvc.perform(get("/audit").param("eventType", "STEP_COMPLETED").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedEventType", "STEP_COMPLETED"));

        verify(audit).findByEventTypeOrderByIdDesc(eq(AuditEventType.STEP_COMPLETED), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void audit_invalidEventType_treatedAsAll() throws Exception {
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(emptyPage(0, 50));

        mvc.perform(get("/audit").param("eventType", "NOT_A_TYPE"))
                .andExpect(status().isOk());

        verify(audit).findAllByOrderByIdDesc(any());
    }

    @Test
    void audit_rowsCarryPrettyDetailAndExecutionLink() throws Exception {
        UUID executionId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        AuditEvent event = new AuditEvent(executionId, AuditEventType.STEP_COMPLETED, "triage", "engine",
                "{\"note\":\"done\"}");
        Page<AuditEvent> page = new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1);
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(page);

        var result = mvc.perform(get("/audit")).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("events");
        assertThat(events).hasSize(1);
        Map<String, Object> row = events.get(0);
        assertThat(row.get("eventType")).isEqualTo("STEP_COMPLETED");
        assertThat(row.get("executionId")).isEqualTo(executionId);
        assertThat(row.get("detail").toString()).contains("\n").contains("\"note\"");
    }

    private static Page<AuditEvent> emptyPage(int page, int size) {
        return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
    }
}
