package org.folio.factory.app.web;

import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.admission.PilotRetryService;
import org.folio.factory.app.pilot.admission.PilotRunSettings;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.presentation.PilotTaskQuery;
import org.folio.factory.app.pilot.presentation.PilotTaskUsage;
import org.folio.factory.app.pilot.presentation.PilotTaskView;
import org.folio.factory.app.pilot.source.PilotDiscoveryService;
import org.folio.factory.app.pilot.source.PilotJiraInstance;
import org.folio.factory.app.pilot.source.PilotSourceProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.RunProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class PilotTaskUiControllerTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ATTEMPT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String FLOW_PAGE = "/flows/dev-factory";

    @Mock
    private PilotTaskQuery taskQuery;

    @Mock
    private PilotInstallationRepository installations;

    @Mock
    private PilotAttemptRepository attempts;

    @Mock
    private PilotAdmissionService admissions;

    @Mock
    private PilotRetryService retries;

    @Mock
    private PilotDiscoveryService discovery;

    @Mock
    private PilotDispatcher dispatcher;

    @Mock
    private ObjectProvider<PilotOutcomeService> outcomes;

    @Mock
    private PilotOutcomeService outcomeService;

    @Mock
    private PilotJiraInstance jira;

    @Mock
    private FlowRegistry flows;

    @Captor
    private ArgumentCaptor<PilotCommands.RunSelected> runCaptor;

    @Captor
    private ArgumentCaptor<PilotCommands.Retry> retryCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private MockMvc mvc;

    private PilotRunSettings runSettings;

    private final CodingRuntime coding = org.mockito.Mockito.mock(CodingRuntime.class);

    @BeforeEach
    void setUp() {
        lenient().when(coding.id()).thenReturn("claude-code");
        var envDefault = new DevRuntimeProperties.ClaudeCode(null, null, "http://gateway.example:4001",
                "claude-sonnet-5-5", "bearer-token", null, "gateway-token", 40, 16384, null);
        var glm = new RunProfiles.FileProfile("glm-5.3", "GLM-5.3 (Z.ai)", "https://api.z.ai/api/anthropic",
                "bearer-token", "FACTORY_ZAI_API_KEY", "glm-5.3", List.of("low", "high", "max"), "max", 100, 16384);
        runSettings = new PilotRunSettings(coding, new RunProfiles(envDefault,
                new RunProfiles.Properties(List.of(glm), null), Map.of("FACTORY_ZAI_API_KEY", "zai-key")::get));
        var repositories = new DevFactoryProperties("https://github.com", new TreeMap<>(Map.of("mod-bar",
                new DevFactoryProperties.Repository("folio-org/mod-bar", "master", "build:image", "unit",
                        List.of("MODBAR"), List.of(), new DeliveryTarget("folio-org/mod-bar", null)))));
        var controller = new PilotTaskUiController(taskQuery, installations, attempts, admissions, retries,
                discovery, dispatcher, outcomes, new PilotSourceProperties("Jane Developer"), jira, flows,
                repositories, new DevDeliveryProperties(Map.of(), true), runSettings);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new UiExceptionHandler())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        lenient().when(flows.find("dev-factory")).thenReturn(Optional.of(
                new FlowDescriptor("dev-factory", "Developer Flow", "1.0.0", null, null, null, null, null)));
        lenient().when(jira.baseUrl()).thenReturn("https://jira.example.org");
        lenient().when(jira.current()).thenReturn("jira.example.org");
        lenient().when(installations.findById(1)).thenReturn(Optional.of(PilotInstallation.initial()));
        lenient().when(attempts.findLatestUpdate()).thenReturn(Optional.empty());
    }

    // ----- GET routes are read-only -----

    @Test
    void myTasks_rendersAvailableAndTriggeredFromQueryOnly() throws Exception {
        PilotTaskView available = taskView(TASK_ID, "MODBAR-901", "Open",
                PilotTaskView.TaskState.AWAITING_SELECTION, true, false);
        PilotTaskView stopped = taskView(OTHER_TASK_ID, "MODBAR-911", "In Progress",
                PilotTaskView.TaskState.STOPPED, false, true, attempt("STOPPED", "MANUAL"));
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(available), stopped));

        MvcResult result = mvc.perform(get(FLOW_PAGE))
                .andExpect(status().isOk())
                .andExpect(view().name("flow-my-tasks"))
                .andExpect(model().attributeExists("flow", "source", "repositoryMap", "available", "statusChips",
                        "triggered", "page", "pollUrl", "pollBaseline"))
                .andReturn();

        verify(taskQuery).myTasks(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        Map<String, Object> model = result.getModelAndView().getModel();
        assertThat(rows(model, "available").getFirst())
                .containsEntry("key", "MODBAR-901")
                .containsEntry("repository", "mod-bar")
                .containsEntry("jiraStatus", "Open")
                .containsEntry("selectable", true);
        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("stateLabel", "Could not complete")
                .containsEntry("stateClass", "state-pill state-pill-danger")
                .containsEntry("retryAllowed", true)
                .containsEntry("addedByKey", false)
                .containsEntry("link", "/tasks/" + OTHER_TASK_ID)
                .containsEntry("markStoppedAttemptId", null);
        assertThat(model.get("repositoryMap")).isEqualTo("1 Jira project configured: MODBAR → mod-bar");
        @SuppressWarnings("unchecked")
        var source = (Map<String, Object>) model.get("source");
        assertThat(source)
                .containsEntry("assignee", "Jane Developer")
                .containsEntry("jiraHost", "jira.example.org")
                .containsEntry("query", "assignee = \"Jane Developer\" AND statusCategory != Done"
                        + " ORDER BY updated DESC");
        assertThat((String) source.get("searchUrl")).startsWith("https://jira.example.org/issues/?jql=");
        @SuppressWarnings("unchecked")
        var runForm = (Map<String, Object>) model.get("runForm");
        assertThat(runForm).containsEntry("profile", "env-default").containsEntry("effort", "")
                .containsEntry("maxTurns", 40).containsEntry("maxTurnsLimit", 500);
        assertThat(rows(runForm, "profiles")).extracting(row -> row.get("id")).containsExactly("env-default", "glm-5.3");
        verifyNoInteractions(admissions, retries, discovery, outcomes);
        verify(dispatcher, never()).dispatchNext();
        verify(dispatcher, never()).dispatchNextQuietly();
    }

    @Test
    void myTasks_withThePiRuntimeTheRunFormHasNoProfileChoice() throws Exception {
        when(coding.id()).thenReturn("pi");
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of()));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model.get("runForm")).isNull();
    }

    @Test
    void myTasks_statusChipsPressOpenAndInProgressByDefault() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                taskView(TASK_ID, "MODBAR-901", "In Review", PilotTaskView.TaskState.AWAITING_SELECTION, true, false),
                taskView(OTHER_TASK_ID, "MODBAR-902", "In Progress",
                        PilotTaskView.TaskState.AWAITING_SELECTION, true, false),
                taskView(UUID.randomUUID(), "MODBAR-903", "Open",
                        PilotTaskView.TaskState.AWAITING_SELECTION, true, false))));

        var chips = rows(mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel(), "statusChips");

        assertThat(chips).extracting(chip -> chip.get("status"))
                .containsExactly("Open", "In Progress", "In Review");
        assertThat(chips).extracting(chip -> chip.get("pressed")).containsExactly(true, true, false);
    }

    @Test
    void myTasks_pressesEveryChipWhenNoDefaultStatusIsPresent() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                taskView(TASK_ID, "MODBAR-901", "To Do", PilotTaskView.TaskState.AWAITING_SELECTION, true, false))));

        var chips = rows(mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel(), "statusChips");

        assertThat(chips).singleElement().satisfies(chip -> assertThat(chip).containsEntry("pressed", true));
    }

    @Test
    void myTasks_availableCountsOnlyShownTasksThatCanRun() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                taskView(TASK_ID, "MODBAR-901", "Open", PilotTaskView.TaskState.UNSUPPORTED, false, false),
                taskView(OTHER_TASK_ID, "MODBAR-902", "Open", PilotTaskView.TaskState.UNSUPPORTED, false, false),
                taskView(UUID.randomUUID(), "MODBAR-903", "In Progress",
                        PilotTaskView.TaskState.UNSUPPORTED, false, false))));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model).containsEntry("availableShown", 0L).containsEntry("notEligibleShown", 3L);
    }

    @Test
    void myTasks_availableIgnoresTasksHiddenByTheStatusFilter() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                taskView(TASK_ID, "MODBAR-901", "Open", PilotTaskView.TaskState.AWAITING_SELECTION, true, false),
                taskView(OTHER_TASK_ID, "MODBAR-902", "Open", PilotTaskView.TaskState.UNSUPPORTED, false, false),
                taskView(UUID.randomUUID(), "MODBAR-903", "In Review",
                        PilotTaskView.TaskState.AWAITING_SELECTION, true, false),
                taskView(UUID.randomUUID(), "MODBAR-904", "In Review",
                        PilotTaskView.TaskState.UNSUPPORTED, false, false))));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model).containsEntry("availableShown", 1L).containsEntry("notEligibleShown", 1L);
    }

    @Test
    void myTasks_showsQueueIsWaitingWhileAnInspectionHoldsTheSlot() throws Exception {
        PilotTaskView held = taskView(TASK_ID, "MODBAR-921", "Open", PilotTaskView.TaskState.PROCESSING_PAUSED,
                false, false, attempt("NEEDS_INSPECTION", "BY_KEY"));
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), held));
        PilotInstallation installation = PilotInstallation.initial();
        installation.setActiveAttemptId(ATTEMPT_ID);
        when(installations.findById(1)).thenReturn(Optional.of(installation));
        PilotAttempt attempt = new PilotAttempt(TASK_ID, 1, PilotAttempt.Kind.INITIAL, "run#1",
                PilotAttempt.State.NEEDS_INSPECTION);
        ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
        when(attempts.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model.get("queueWaiting")).isEqualTo(Map.of("key", "MODBAR-921", "taskId", TASK_ID,
                "attemptId", ATTEMPT_ID));
        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("stateLabel", "Needs inspection")
                .containsEntry("addedByKey", true)
                .containsEntry("markStoppedAttemptId", ATTEMPT_ID);
    }

    @Test
    void myTasks_explainsWhyQueuedTasksAreNotStarting() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of()));
        when(attempts.countByStateIn(List.of(PilotAttempt.State.RESERVED))).thenReturn(2L);
        when(dispatcher.queueProblem()).thenReturn("Developer Flow preflight failed: Docker is not running.");

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model)
                .containsEntry("queuedCount", 2L)
                .containsEntry("queueProblem", "Developer Flow preflight failed: Docker is not running.");
    }

    @Test
    void draftPullRequestShowsDraftPrAndItsReasonsInsteadOfPrReady() throws Exception {
        List<String> reasons = List.of("1 acceptance criterion is not met.",
                "The agent made assumptions that a reviewer should confirm.");
        PilotTaskView draft = taskView(TASK_ID, "MODBAR-921", "Open", PilotTaskView.TaskState.PR_READY,
                false, false, prAttempt(reasons));
        PilotTaskView ready = taskView(OTHER_TASK_ID, "MODBAR-922", "Open", PilotTaskView.TaskState.PR_READY,
                false, false, prAttempt(null));
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), draft, ready));
        when(taskQuery.detail(TASK_ID)).thenReturn(draft);

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andExpect(status().isOk()).andReturn()
                .getModelAndView().getModel();
        assertThat(rows(model, "triggered").get(0))
                .containsEntry("stateLabel", "Draft PR")
                .containsEntry("stateClass", "state-pill state-pill-warning")
                .containsEntry("detail", null)
                .containsEntry("prLabel", "Pull request #7");
        assertThat(rows(model, "triggered").get(1))
                .containsEntry("stateLabel", "PR ready")
                .containsEntry("stateClass", "state-pill state-pill-success");

        MvcResult result = mvc.perform(get("/tasks/{taskId}", TASK_ID)).andExpect(status().isOk()).andReturn();
        assertThat(detail(result))
                .containsEntry("stateLabel", "Draft PR")
                .containsEntry("stateClass", "state-pill state-pill-warning")
                .containsEntry("result", "Pull request #7 is open as a draft.")
                .containsEntry("draftReasons", reasons)
                .containsEntry("prAction", "Open pull request #7");
        assertThat(rows(result.getModelAndView().getModel(), "attempts").getFirst())
                .containsEntry("resultLabel", "Draft PR")
                .containsEntry("resultClass", "state-pill state-pill-warning")
                .containsEntry("result", "Pull request #7 opened as a draft");
        // The execution page header uses the same label for the attempt behind the run.
        assertThat(PilotTaskUiController.runContext(draft, EXECUTION_ID))
                .containsEntry("stateLabel", "Draft PR")
                .containsEntry("stateClass", "state-pill state-pill-warning");
        assertThat(PilotTaskUiController.runContext(ready, EXECUTION_ID))
                .containsEntry("stateLabel", "PR ready");
    }

    @Test
    void incompleteUsageSaysTheCostMayBeHigherAndExplainsWhy() throws Exception {
        String turnLimit = "Claude Code used all 40 turns before it finished the task."
                + " Retry with more turns or a different effort.";
        PilotTaskView.AttemptHistoryEntry attempt = new PilotTaskView.AttemptHistoryEntry(UUID.randomUUID(), 1,
                "INITIAL", "MANUAL", "STOPPED", "coding", "DEVELOPMENT_FAILED: " + turnLimit, null, null,
                EXECUTION_ID, "COMPLETED", Instant.parse("2026-10-23T07:59:00Z"),
                Instant.parse("2026-10-23T08:00:00Z"), Instant.parse("2026-10-23T09:00:00Z"), true, 600,
                incompleteUsage(), null);
        PilotTaskView stopped = withUsage(taskView(TASK_ID, "MODBAR-911", "Open", PilotTaskView.TaskState.STOPPED,
                false, true, attempt), incompleteUsage());
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), stopped));
        when(taskQuery.detail(TASK_ID)).thenReturn(stopped);

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();
        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("cost", "$0.32")
                .containsEntry("costNote", "cost may be higher")
                .containsEntry("costNoteTitle",
                        "Some usage was not reported by the runtime or has no price, so the real cost may be higher.");

        Map<String, Object> detail = mvc.perform(get("/tasks/{taskId}", TASK_ID)).andReturn().getModelAndView()
                .getModel();
        assertThat(rows(detail, "attempts").getFirst())
                .containsEntry("costNote", "cost may be higher")
                .containsEntry("costNoteTitle",
                        "Some usage was not reported by the runtime or has no price, so the real cost may be higher.")
                .containsEntry("ordinal", 1)
                .containsEntry("executionId", EXECUTION_ID)
                .containsEntry("resultLabel", null)
                // The reason keeps what to do.
                .containsEntry("result", "Implementation failed: " + turnLimit.substring(0, turnLimit.length() - 1));
    }

    @Test
    void tasks_redirectsToTheFlowPage() throws Exception {
        mvc.perform(get("/tasks"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE));
    }

    @Test
    void taskDetail_rendersSummaryWithAttemptsOldestFirst() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-911", "Open",
                PilotTaskView.TaskState.STOPPED, false, true,
                attempt("STOPPED", "MANUAL", 1), attempt("STOPPED", "BY_KEY", 2)));

        MvcResult result = mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("pilot-task"))
                .andReturn();

        assertThat(detail(result))
                .containsEntry("stateLabel", "Could not complete")
                .containsEntry("retryAllowed", true)
                .containsEntry("lastRunUrl", "/executions/" + EXECUTION_ID)
                .containsEntry("project", "MODBAR")
                .containsEntry("destination", "folio-org/mod-bar · base master");
        assertThat((String) detail(result).get("result")).endsWith("No pull request was created.");
        // Oldest first: a new attempt is added at the bottom.
        assertThat(rows(result.getModelAndView().getModel(), "attempts"))
                .extracting(row -> row.get("label"))
                .containsExactly("1 · first run", "2 · first run by key");
        verifyNoInteractions(admissions, retries, discovery, outcomes);
        verify(dispatcher, never()).dispatchNext();
        verify(dispatcher, never()).dispatchNextQuietly();
    }

    @Test
    @SuppressWarnings("unchecked")
    void taskDetail_retryFormIsFilledFromThePreviousAttempt() throws Exception {
        String previous = "{\"profileId\":\"glm-5.3\",\"profileLabel\":\"GLM-5.3 (Z.ai)\",\"model\":\"glm-5.3\","
                + "\"endpointOrigin\":\"https://api.z.ai\",\"effort\":\"low\",\"maxTurns\":77,"
                + "\"instructions\":\"Keep the public API.\"}";
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-911", "Open",
                PilotTaskView.TaskState.STOPPED, false, true,
                attempt("STOPPED", "MANUAL", 1, null), attempt("STOPPED", "MANUAL", 2, previous)));

        MvcResult result = mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andReturn();

        var model = result.getModelAndView().getModel();
        assertThat((Map<String, Object>) model.get("retryForm")).containsEntry("profile", "glm-5.3")
                .containsEntry("effort", "low").containsEntry("maxTurns", 77);
        assertThat(model.get("retryInstructions")).isEqualTo("Keep the public API.");
        assertThat(rows(model, "attempts")).extracting(row -> row.get("run"))
                .containsExactly(null, "glm-5.3 · low");
    }

    @Test
    void taskDetail_unknownTaskRendersNotFoundPage() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenThrow(new NoSuchElementException("No pilot task " + TASK_ID));

        mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void poll_reportsRevisionWithoutCommandServices() throws Exception {
        when(attempts.count()).thenReturn(2L);

        mvc.perform(get("/tasks/poll"))
                .andExpect(status().isOk());

        verifyNoInteractions(admissions, retries, discovery, outcomes);
        verify(dispatcher, never()).dispatchNext();
        verify(dispatcher, never()).dispatchNextQuietly();
    }

    // ----- scan -----

    @Test
    void scan_reportsTheAssignedCount() throws Exception {
        when(discovery.sync()).thenReturn(new PilotDiscoveryService.SyncResult(true, 3, Instant.now(), null));

        mvc.perform(post(FLOW_PAGE + "/scan"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "Scan complete: 3 tasks are assigned to you."));
    }

    @Test
    void scan_failureRedirectsWithTheProblem() throws Exception {
        when(discovery.sync()).thenReturn(new PilotDiscoveryService.SyncResult(false, 0, null, "Jira timed out"));

        mvc.perform(post(FLOW_PAGE + "/scan"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE + "?error=Scan+failed%3A+Jira+timed+out"));
    }

    // ----- run selected -----

    @Test
    void runSelected_mapsFormToRunSelectedCommand() throws Exception {
        when(admissions.runSelected(any())).thenReturn(
                new PilotAdmissionService.AdmissionResult(List.of(TASK_ID), Map.of()));

        mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString())
                        .param("version-" + TASK_ID, "3")
                        .param("input-" + TASK_ID, "revision-MODBAR-901")
                        .param("destination-" + TASK_ID, "dest-MODBAR-901"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "Queued 1 task."));

        verify(admissions).runSelected(runCaptor.capture());
        PilotCommands.RunSelected run = runCaptor.getValue();
        assertThat(run.requestId()).startsWith("ui-");
        assertThat(run.requestId().length()).isLessThanOrEqualTo(63);
        assertThat(run.selections()).containsExactly(new PilotCommands.Selection(
                TASK_ID, 3L, "revision-MODBAR-901", "dest-MODBAR-901"));
        // Without a choice the run takes the default profile and its defaults.
        assertThat(PilotRunSettings.read(run.runSettings())).isEqualTo(new PilotRunSettings.Snapshot(
                "env-default", "Configured in .env", "claude-sonnet-5-5", "http://gateway.example:4001", null, 40,
                null));
    }

    @Test
    void runSelected_appliesOneChoiceOfProfileEffortAndTurnsToEveryTask() throws Exception {
        when(admissions.runSelected(any())).thenReturn(
                new PilotAdmissionService.AdmissionResult(List.of(TASK_ID, OTHER_TASK_ID), Map.of()));

        mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString(), OTHER_TASK_ID.toString())
                        .param("profileId", "glm-5.3").param("effort", "low").param("maxTurns", "120"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE));

        verify(admissions).runSelected(runCaptor.capture());
        assertThat(runCaptor.getValue().selections()).hasSize(2);
        assertThat(PilotRunSettings.settings(runCaptor.getValue().runSettings()))
                .isEqualTo(new CodingRequest.RunSettings("glm-5.3", "low", 120, null));
        assertThat(PilotRunSettings.read(runCaptor.getValue().runSettings()).endpointOrigin())
                .isEqualTo("https://api.z.ai");
        assertThat(runCaptor.getValue().runSettings()).doesNotContain("zai-key");
    }

    @Test
    void runSelected_badRunSettingsAreRejectedWithAPlainMessage() throws Exception {
        MvcResult result = mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString())
                        .param("profileId", "glm-5.3").param("effort", "medium").param("maxTurns", "10"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(java.net.URLDecoder.decode(result.getResponse().getRedirectedUrl(),
                java.nio.charset.StandardCharsets.UTF_8))
                .endsWith("Effort medium is not available for profile glm-5.3. Choose one of: low, high, max.");

        result = mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString()).param("maxTurns", "501"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(java.net.URLDecoder.decode(result.getResponse().getRedirectedUrl(),
                java.nio.charset.StandardCharsets.UTF_8))
                .endsWith("Max turns must be a whole number from 1 to 500.");
        verifyNoInteractions(admissions);
    }

    @Test
    void runSelected_surfacesPerRowRefusals() throws Exception {
        when(admissions.runSelected(any())).thenReturn(new PilotAdmissionService.AdmissionResult(List.of(),
                Map.of(TASK_ID, "Your GitHub token cannot push to folio-org/mod-bar")));
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-901", "Open",
                PilotTaskView.TaskState.AWAITING_SELECTION, true, false));

        MvcResult result = mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString())
                        .param("version-" + TASK_ID, "1"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith(FLOW_PAGE + "?error=")
                .contains("MODBAR-901")
                .contains("cannot+push+to+folio-org%2Fmod-bar");
    }

    @Test
    void runSelected_withoutSelection_redirectsWithoutCallingAdmission() throws Exception {
        mvc.perform(post("/tasks/run-selected"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE + "?error=Select+at+least+one+task%2C+then+choose+Run."));

        verifyNoInteractions(admissions);
    }

    @Test
    void runSelected_mangledForm_redirectsWithoutCallingAdmission() throws Exception {
        MvcResult result = mvc.perform(post("/tasks/run-selected")
                        .param("taskId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeeX"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith(FLOW_PAGE + "?error=")
                .contains("The+submitted+selection+was+malformed");
        verifyNoInteractions(admissions);
    }

    // ----- run by key -----

    @Test
    void runByKey_reportsTheQueuedKey() throws Exception {
        when(admissions.runByKey(anyString(), any(), any())).thenReturn(
                new PilotAdmissionService.KeyResult("MODBAR-930", TASK_ID, null));

        mvc.perform(post(FLOW_PAGE + "/run-by-key").param("key", "https://jira.example.org/browse/MODBAR-930"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "MODBAR-930 joined the queue."));

        verify(admissions).runByKey(anyString(), org.mockito.ArgumentMatchers.eq(
                "https://jira.example.org/browse/MODBAR-930"), any());
    }

    @Test
    void runByKey_carriesTheChosenRunSettings() throws Exception {
        when(admissions.runByKey(anyString(), any(), any())).thenReturn(
                new PilotAdmissionService.KeyResult("MODBAR-930", TASK_ID, null));

        mvc.perform(post(FLOW_PAGE + "/run-by-key").param("key", "MODBAR-930")
                        .param("profileId", "glm-5.3").param("effort", "high").param("maxTurns", "60"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE));

        ArgumentCaptor<String> settings = ArgumentCaptor.forClass(String.class);
        verify(admissions).runByKey(anyString(), org.mockito.ArgumentMatchers.eq("MODBAR-930"), settings.capture());
        assertThat(PilotRunSettings.settings(settings.getValue()))
                .isEqualTo(new CodingRequest.RunSettings("glm-5.3", "high", 60, null));
    }

    @Test
    void runByKey_refusalRedirectsWithTheReason() throws Exception {
        when(admissions.runByKey(anyString(), any(), any())).thenReturn(new PilotAdmissionService.KeyResult(
                "MODX-1", null, "Project MODX is not configured"));

        mvc.perform(post(FLOW_PAGE + "/run-by-key").param("key", "MODX-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE + "?error=Project+MODX+is+not+configured"));
    }

    @Test
    void runByKey_aLongJiraErrorStaysShortInTheRedirect() throws Exception {
        when(admissions.runByKey(anyString(), any(), any())).thenReturn(new PilotAdmissionService.KeyResult(
                "MODX-1", null, "Jira could not be read: " + "<html>".repeat(2000)));

        MvcResult result = mvc.perform(post(FLOW_PAGE + "/run-by-key").param("key", "MODX-1"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String error = java.net.URLDecoder.decode(result.getResponse().getRedirectedUrl()
                .substring((FLOW_PAGE + "?error=").length()), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(error).hasSize(UiFormat.MAX_REDIRECT_MESSAGE).startsWith("Jira could not be read: ").endsWith("…");
    }

    // ----- retry -----

    @Test
    void retry_fromTheFlowPageReturnsThere() throws Exception {
        when(retries.retry(any())).thenReturn(new PilotRetryService.RetryResult(
                ATTEMPT_ID, null, "RESERVED", "the retry joined the queue"));

        mvc.perform(post("/tasks/{taskId}/retry", TASK_ID)
                        .param("expectedVersion", "3")
                        .param("inputFingerprint", "revision-MODBAR-901")
                        .param("destinationFingerprint", "dest-MODBAR-901")
                        .param("returnTo", "flow"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "Retry queued: the retry joined the queue"));

        verify(retries).retry(retryCaptor.capture());
        PilotCommands.Retry command = retryCaptor.getValue();
        assertThat(command.taskId()).isEqualTo(TASK_ID);
        assertThat(command.expectedVersion()).isEqualTo(3L);
        assertThat(command.inputFingerprint()).isEqualTo("revision-MODBAR-901");
        assertThat(command.destinationFingerprint()).isEqualTo("dest-MODBAR-901");
        assertThat(command.requestId()).startsWith("ui-");
    }

    @Test
    void retry_carriesProfileEffortTurnsAndInstructions() throws Exception {
        when(retries.retry(any())).thenReturn(new PilotRetryService.RetryResult(
                ATTEMPT_ID, null, "RESERVED", "the retry joined the queue"));

        mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "3")
                        .param("profileId", "glm-5.3").param("effort", "max").param("maxTurns", "150")
                        .param("instructions", "  Read the ADR through the wiki REST URL.  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks/" + TASK_ID));

        verify(retries).retry(retryCaptor.capture());
        assertThat(PilotRunSettings.settings(retryCaptor.getValue().runSettings())).isEqualTo(
                new CodingRequest.RunSettings("glm-5.3", "max", 150, "Read the ADR through the wiki REST URL."));
    }

    @Test
    void retry_tooLongInstructionsAreRejectedBeforeAnyRetry() throws Exception {
        MvcResult result = mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "3")
                        .param("instructions", "x".repeat(8001)))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(java.net.URLDecoder.decode(result.getResponse().getRedirectedUrl(),
                java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("/tasks/" + TASK_ID + "?error=")
                .contains("at most 8000 characters; this text has 8001");
        verifyNoInteractions(retries);
    }

    @Test
    void retry_refusalReturnsToTheTaskSummary() throws Exception {
        when(retries.retry(any())).thenReturn(new PilotRetryService.RetryResult(
                null, null, "STOPPED", "Your GitHub token cannot push to folio-org/mod-bar"));

        MvcResult result = mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "1"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/tasks/" + TASK_ID + "?error=")
                .contains("cannot+push");
    }

    @Test
    void retry_refusedWhileTheTaskIsQueuedIsNotShownAsQueued() throws Exception {
        when(retries.retry(any())).thenReturn(new PilotRetryService.RetryResult(
                ATTEMPT_ID, null, "RESERVED", "task already has a live attempt", true));

        mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "1").param("returnTo", "flow"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE + "?error=task+already+has+a+live+attempt"))
                .andExpect(flash().attributeCount(0));
    }

    // ----- mark as stopped -----

    @Test
    void markStopped_releasesTheQueueAndStartsTheNextTask() throws Exception {
        when(outcomes.getIfAvailable()).thenReturn(outcomeService);
        when(outcomeService.markStopped(ATTEMPT_ID)).thenReturn(null);

        mvc.perform(post("/tasks/{taskId}/attempts/{attemptId}/mark-stopped", TASK_ID, ATTEMPT_ID)
                        .param("returnTo", "flow"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "Marked as stopped; the queue continues."));

        verify(dispatcher).dispatchNextQuietly();
    }

    @Test
    void markStopped_refusalDoesNotDispatch() throws Exception {
        when(outcomes.getIfAvailable()).thenReturn(outcomeService);
        when(outcomeService.markStopped(ATTEMPT_ID))
                .thenReturn("Only a task that needs inspection can be marked as stopped");

        mvc.perform(post("/tasks/{taskId}/attempts/{attemptId}/mark-stopped", TASK_ID, ATTEMPT_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks/" + TASK_ID
                        + "?error=Only+a+task+that+needs+inspection+can+be+marked+as+stopped"));

        verifyNoInteractions(dispatcher);
    }

    // ----- cross-site posts -----

    @Test
    void crossSitePostsAreRefusedBeforeAnyService() throws Exception {
        mvc.perform(post(FLOW_PAGE + "/scan").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mvc.perform(post(FLOW_PAGE + "/run-by-key").param("key", "MODBAR-1")
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/tasks/{taskId}/attempts/{attemptId}/mark-stopped", TASK_ID, ATTEMPT_ID)
                        .header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(discovery, admissions, outcomes, dispatcher);
    }

    @Test
    void sameOriginPostsPass() throws Exception {
        when(discovery.sync()).thenReturn(new PilotDiscoveryService.SyncResult(true, 1, Instant.now(), null));

        mvc.perform(post(FLOW_PAGE + "/scan").header("Origin", "http://localhost"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "Scan complete: 1 task is assigned to you."));
        mvc.perform(post(FLOW_PAGE + "/scan").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().is3xxRedirection());
    }

    // ----- helpers -----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(MvcResult result) {
        return (Map<String, Object>) result.getModelAndView().getModel().get("task");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> model, String name) {
        return (List<Map<String, Object>>) model.get(name);
    }

    private static PilotTaskQuery.MyTasks myTasks(List<PilotTaskView> available, PilotTaskView... triggered) {
        return new PilotTaskQuery.MyTasks(available,
                new PageImpl<>(List.of(triggered), PageRequest.of(0, 20), triggered.length));
    }

    private static PilotTaskView taskView(UUID taskId, String key, String jiraStatus, PilotTaskView.TaskState state,
                                          boolean select, boolean retry,
                                          PilotTaskView.AttemptHistoryEntry... attempts) {
        return new PilotTaskView(taskId, key, "https://jira.example.org/browse/" + key, "Title of " + key,
                "MODBAR", jiraStatus, state, null,
                state == PilotTaskView.TaskState.STOPPED ? "VERIFICATION_FAILED: 2 tests failed" : null,
                state == PilotTaskView.TaskState.STOPPED ? "verification" : null, null,
                "mod-bar",
                state == PilotTaskView.TaskState.PR_READY ? "https://github.com/folio-org/mod-bar/pull/7" : null,
                state == PilotTaskView.TaskState.PR_READY && attempts.length > 0
                        ? attempts[attempts.length - 1].draftReasons() : null,
                java.util.Arrays.stream(attempts).noneMatch(attempt -> "BY_KEY".equals(attempt.admittedVia())),
                "ELIGIBLE", "repository mod-bar", 3L, "revision-" + key, "dest-" + key,
                Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-23T09:00:00Z"),
                List.of(attempts), emptyUsage(), new PilotTaskView.AllowedActions(select, retry));
    }

    private static PilotTaskView.AttemptHistoryEntry attempt(String state, String admittedVia) {
        return attempt(state, admittedVia, 1);
    }

    private static PilotTaskView.AttemptHistoryEntry attempt(String state, String admittedVia, int ordinal) {
        return attempt(state, admittedVia, ordinal, null);
    }

    private static PilotTaskView.AttemptHistoryEntry attempt(String state, String admittedVia, int ordinal,
                                                             String runSettings) {
        return new PilotTaskView.AttemptHistoryEntry("NEEDS_INSPECTION".equals(state) ? ATTEMPT_ID : UUID.randomUUID(),
                ordinal, "INITIAL", admittedVia, state, "verification", "VERIFICATION_FAILED: 2 tests failed", null,
                null, EXECUTION_ID, "COMPLETED", Instant.parse("2026-10-23T07:59:00Z"),
                Instant.parse("2026-10-23T08:00:00Z"), Instant.parse("2026-10-23T09:00:00Z"),
                true, 600, emptyUsage(), runSettings);
    }

    /** A delivered attempt; {@code draftReasons} null means the pull request is ready for review. */
    private static PilotTaskView.AttemptHistoryEntry prAttempt(List<String> draftReasons) {
        return new PilotTaskView.AttemptHistoryEntry(UUID.randomUUID(), 1, "INITIAL", "MANUAL", "PR_READY", "publish",
                null, "https://github.com/folio-org/mod-bar/pull/7", draftReasons, EXECUTION_ID, "RUNNING",
                Instant.parse("2026-10-23T07:59:00Z"), Instant.parse("2026-10-23T08:00:00Z"),
                Instant.parse("2026-10-23T09:00:00Z"), true, 600, emptyUsage(), null);
    }

    private static PilotTaskUsage emptyUsage() {
        return new PilotTaskUsage(0, Map.of(), new TreeMap<String, BigDecimal>(), true, false,
                List.of(), List.of());
    }

    /** $0.32 so far, but some usage was not reported, so the total is incomplete. */
    private static PilotTaskUsage incompleteUsage() {
        return new PilotTaskUsage(10_000, Map.of(), new TreeMap<>(Map.of("USD", new BigDecimal("0.32"))), false,
                false, List.of(), List.of());
    }

    private static PilotTaskView withUsage(PilotTaskView view, PilotTaskUsage usage) {
        return new PilotTaskView(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(), view.projectKey(),
                view.jiraStatus(), view.state(), view.queuePosition(), view.reason(), view.stage(),
                view.stageStartedAt(), view.destinationRepository(), view.pullRequestUrl(), view.draftReasons(),
                view.inScope(), view.eligibility(), view.routingReason(), view.version(), view.inputFingerprint(),
                view.destinationFingerprint(), view.jiraCreatedAt(), view.updatedAt(), view.attempts(), usage,
                view.allowedActions());
    }
}
