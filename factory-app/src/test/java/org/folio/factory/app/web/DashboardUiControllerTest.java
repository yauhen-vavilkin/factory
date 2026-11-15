package org.folio.factory.app.web;

import org.folio.factory.app.tasks.persistence.RunState;
import org.folio.factory.app.tasks.presentation.Spending;
import org.folio.factory.app.tasks.presentation.SpendingRows;
import org.folio.factory.app.web.DashboardUiController.RunTile;
import org.folio.factory.app.web.dashboard.DashboardStats.StepTokenCount;
import org.folio.factory.app.web.dashboard.DashboardStats.TokenUsage;
import org.folio.factory.app.web.dashboard.DashboardStatsService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    @Mock
    private DashboardStatsService dashboardStats;

    @Mock
    private SpendingRows spendingRows;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private record FakeConnector(String connectorName, boolean isConfigured) implements ConnectorHealth {
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = mvcWithConnectors(List.of(new FakeConnector("jira", true), new FakeConnector("github", false)));
    }

    private MockMvc mvcWithConnectors(List<ConnectorHealth> connectors) {
        EngineProperties engine = new EngineProperties(true, 2000L, 5, 4, 1800L, 30, true);
        DashboardUiController controller = new DashboardUiController(
                connectors, flowRegistry, engine, flowRegistryEntries, audit, json, dashboardStats, spendingRows);
        return MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    private void stubKpiCounts() {
        when(dashboardStats.countByState(anyInt())).thenReturn(new EnumMap<>(Map.of(
                RunState.IN_PROGRESS, 3L, RunState.WAITING_FOR_REVIEW, 3L, RunState.PULL_REQUEST, 2L,
                RunState.COMPLETED, 10L, RunState.FAILED, 4L, RunState.CANCELLED_OR_REJECTED, 3L)));
        when(dashboardStats.tokenUsage(anyInt())).thenReturn(new TokenUsage(1234, 567));
        when(dashboardStats.stepTokens(anyInt())).thenReturn(List.of(
                new StepTokenCount("test-factory", "test-automation", 1000, 400),
                new StepTokenCount("test-factory", "triage", 234, 167)));
    }

    @Test
    void dashboard_rendersKpiCountsFromCheapRepoQueries() throws Exception {
        stubKpiCounts();

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("days", 14))
                .andExpect(model().attribute("runTiles", List.of(
                        new RunTile("Total runs", "25", "/executions?days=14"),
                        new RunTile("In progress", "3", "/executions?status=IN_PROGRESS&days=14"),
                        new RunTile("Waiting for review", "3", "/executions?status=WAITING_FOR_REVIEW&days=14"),
                        new RunTile("Pull request opened", "2", "/executions?status=PULL_REQUEST&days=14"),
                        new RunTile("Completed", "10", "/executions?status=COMPLETED&days=14"),
                        new RunTile("Asked a question", "0", "/executions?status=ASKED&days=14"),
                        new RunTile("Needs inspection", "0", "/executions?status=NEEDS_INSPECTION&days=14"),
                        new RunTile("Failed", "4", "/executions?status=FAILED&days=14"),
                        new RunTile("Cancelled or rejected", "3", "/executions?status=CANCELLED_OR_REJECTED&days=14"))))
                .andExpect(model().attribute("engineEnabled", true))
                .andExpect(model().attribute("connectorsConfigured", 1))
                .andExpect(model().attribute("connectorsTotal", 2))
                .andExpect(model().attribute("tokenTotal", "1,801"))
                .andExpect(model().attribute("tokenBreakdown", "1,234 in · 567 out"));
    }

    @Test
    void dashboard_tokenRowsCarryGroupedCountsHeaviestFirst() throws Exception {
        stubKpiCounts();
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(
                new FlowDescriptor("test-factory", "Test Factory", "1.0.0", null, null, null, null, null)));

        MvcResult result = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        var rows = (List<DashboardUiController.StepTokenRow>) result.getModelAndView().getModel().get("stepTokens");
        // The flow by its registered name, the step in the words of the Definition tab.
        assertThat(rows).containsExactly(
                new DashboardUiController.StepTokenRow("Test Factory", "Test automation", "1,000", "400", "1,400"),
                new DashboardUiController.StepTokenRow("Test Factory", "Triage", "234", "167", "401"));
    }

    @Test
    void dashboard_scopesRunAndTokenQueriesToTheClampedWindow() throws Exception {
        stubKpiCounts();

        mvc.perform(get("/?days=999")).andExpect(status().isOk()).andExpect(view().name("dashboard"))
                .andExpect(model().attribute("days", 14));
        mvc.perform(get("/?days=abc")).andExpect(status().isOk()).andExpect(model().attribute("days", 14));

        // An unknown range falls back to 14, and the run and token queries must use that
        // same clamped value rather than the raw request parameter.
        verify(dashboardStats, org.mockito.Mockito.times(2)).countByState(14);
        verify(dashboardStats, org.mockito.Mockito.times(2)).tokenUsage(14);
        verify(dashboardStats, org.mockito.Mockito.times(2)).stepTokens(14);
    }

    @Test
    void dashboard_showsTheDeveloperFlowSpendingOfTheChosenPeriodOnlyWhenItHasAttempts() throws Exception {
        stubKpiCounts();
        var spending = new Spending(List.of(new Spending.Figure("$1.90", "Spent")), null, List.of(), null);
        var since = ArgumentCaptor.forClass(java.time.Instant.class);
        when(spendingRows.since(since.capture())).thenReturn(Optional.of(spending), Optional.empty());

        mvc.perform(get("/").param("days", "30")).andExpect(model().attribute("spending", spending));
        mvc.perform(get("/")).andExpect(model().attributeDoesNotExist("spending"));

        // The block counts the attempts whose run started in the period, as the run tiles count runs.
        assertThat(java.time.Duration.between(since.getAllValues().get(0), java.time.Instant.now()).toDays())
                .isEqualTo(30);
        assertThat(java.time.Duration.between(since.getAllValues().get(1), java.time.Instant.now()).toDays())
                .isEqualTo(14);
    }

    @Test
    void dashboard_acceptsAllowedDays() throws Exception {
        stubKpiCounts();

        mvc.perform(get("/").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("days", 30));
        verify(dashboardStats).countByState(30);
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
        var connectors = (List<DashboardUiController.ConnectorRow>) result.getModelAndView().getModel()
                .get("connectors");
        assertThat(connectors).extracting(DashboardUiController.ConnectorRow::name).containsExactly("github", "jira");
        assertThat(connectors).extracting(DashboardUiController.ConnectorRow::configured).containsExactly(false, true);
        assertThat(connectors.getFirst().note()).contains("advisory mode");
        assertThat(connectors.get(1).note()).isNull();

        @SuppressWarnings("unchecked")
        var flows = (List<DashboardUiController.FlowRow>) result.getModelAndView().getModel().get("flows");
        assertThat(flows).containsExactly(
                new DashboardUiController.FlowRow("test-factory", "Test Factory", "1.0.0", null, "/flows/test-factory"));
    }

    @Test
    void status_jiraWithoutSignIn_saysThatMyTasksStillReadsIt() throws Exception {
        var jira = new FakeConnector("jira", false);
        when(flowRegistry.all()).thenReturn(List.of(
                new FlowDescriptor("dev-factory", "Developer Flow", "0.9.0", null, null, null, null, null)));
        when(flowRegistryEntries.findById(any())).thenReturn(java.util.Optional.empty());

        var result = mvcWithConnectors(List.of(jira)).perform(get("/status")).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        var connectors = (List<DashboardUiController.ConnectorRow>) result.getModelAndView().getModel()
                .get("connectors");
        assertThat(connectors).singleElement().satisfies(row -> {
            assertThat(row.note()).startsWith("Not configured for Test Factory sync")
                    .contains("My tasks reads Jira with FACTORY_CONNECTORS_JIRA_BASE_URL alone");
            assertThat(row.configurationUrl()).isEqualTo("/developer/configuration");
        });
        @SuppressWarnings("unchecked")
        var flows = (List<DashboardUiController.FlowRow>) result.getModelAndView().getModel().get("flows");
        assertThat(flows).extracting(DashboardUiController.FlowRow::url)
                .containsExactly("/flows/dev-factory?view=definition");
    }

    @Test
    void dashboard_dedupesConnectorsConfiguredWins() throws Exception {
        stubKpiCounts();
        MockMvc deduped = mvcWithConnectors(List.of(
                new FakeConnector("jira", false), new FakeConnector("jira", true),
                new FakeConnector("github", false), new FakeConnector("github", false),
                new FakeConnector("testrail", true), new FakeConnector("testrail", false)));

        deduped.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("connectorsConfigured", 2))
                .andExpect(model().attribute("connectorsTotal", 3));
    }

    @Test
    void status_dedupesConnectorsConfiguredWinsSortedByName() throws Exception {
        when(flowRegistry.all()).thenReturn(List.of());
        MockMvc deduped = mvcWithConnectors(List.of(
                new FakeConnector("jira", false), new FakeConnector("jira", true),
                new FakeConnector("github", false), new FakeConnector("github", false),
                new FakeConnector("testrail", true)));

        var result = deduped.perform(get("/status"))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        var connectors = (List<DashboardUiController.ConnectorRow>) result.getModelAndView().getModel()
                .get("connectors");
        assertThat(connectors).extracting(DashboardUiController.ConnectorRow::name)
                .containsExactly("github", "jira", "testrail");
        assertThat(connectors).extracting(DashboardUiController.ConnectorRow::configured)
                .containsExactly(false, true, true);
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
    void audit_eventTypeOptions_carryHumanizedLabels() throws Exception {
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(emptyPage(0, 50));

        var result = mvc.perform(get("/audit")).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> options =
                (List<Map<String, String>>) result.getModelAndView().getModel().get("eventTypes");
        assertThat(options)
                .contains(Map.of("value", "HITL_REQUESTED", "label", "Review requested"))
                .contains(Map.of("value", "SUBFLOW_INVOKED", "label", "Sub-flow invoked"))
                .contains(Map.of("value", "STATE_TRANSITION", "label", "State transition"));
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
    void audit_nonPositiveSize_fallsBackToDefaultPageSize() throws Exception {
        when(audit.findAllByOrderByIdDesc(any())).thenReturn(emptyPage(0, 50));

        mvc.perform(get("/audit").param("size", "0"))
                .andExpect(status().isOk());

        verify(audit).findAllByOrderByIdDesc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
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
        assertThat(row.get("eventLabel")).isEqualTo("Step completed");
        assertThat(row.get("eventTone")).isNull();
        assertThat(row.get("executionId")).isEqualTo(executionId);
        assertThat(row.get("detail").toString()).contains("\n").contains("\"note\"");
    }

    @Test
    void audit_executionFilterUsesOnlyThatExecutionsDurableEvents() throws Exception {
        UUID executionId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        AuditEvent event = new AuditEvent(executionId, AuditEventType.STEP_COMPLETED, "triage", "engine", "{}");
        when(audit.findByExecutionIdOrderByIdAsc(executionId)).thenReturn(List.of(event));

        mvc.perform(get("/audit").param("executionId", executionId.toString()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedExecutionId", executionId.toString()))
                .andExpect(model().attribute("events", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(model().attribute("baseUrl", org.hamcrest.Matchers.containsString("executionId=" + executionId)));

        verify(audit).findByExecutionIdOrderByIdAsc(executionId);
    }

    private static Page<AuditEvent> emptyPage(int page, int size) {
        return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
    }
}
