package org.folio.factory.app.web;

import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.app.tasks.presentation.SourceCard;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.folio.factory.app.tasks.presentation.TaskUsage;
import org.folio.factory.app.tasks.presentation.TaskView;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.app.tasks.source.TaskSourceProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.runtime.ModelEntry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
class TaskUiControllerTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ATTEMPT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String FLOW_PAGE = "/flows/dev-factory";

    @Mock
    private TaskQuery taskQuery;

    @Mock
    private TaskInstallationRepository installations;

    @Mock
    private TaskAttemptRepository attempts;

    @Mock
    private TaskAdmissionService admissions;


    @Mock
    private TaskDispatcher dispatcher;

    @Mock
    private ObjectProvider<TaskOutcomeService> outcomes;

    @Mock
    private TaskOutcomeService outcomeService;

    @Mock
    private TaskJiraInstance jira;

    @Mock
    private FlowRegistry flows;

    @Captor
    private ArgumentCaptor<TaskCommands.RunSelected> runCaptor;

    @Captor
    private ArgumentCaptor<TaskCommands.Retry> retryCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private MockMvc mvc;

    private TaskRunSettings runSettings;

    private final CodingRuntime coding = org.mockito.Mockito.mock(CodingRuntime.class);

    @BeforeEach
    void setUp() {
        lenient().when(coding.id()).thenReturn("claude-code");
        var sonnet = new ModelEntry("codemie-sonnet-5.5", "anthropic-messages", "http://gateway.example:4001",
                "bearer-token", "FACTORY_CODEMIE_PROXY_TOKEN", "claude-sonnet-5-5",
                List.of("low", "medium", "high", "xhigh", "max"), "high", null, null);
        var glm = new ModelEntry("glm-5.3", "anthropic-messages", "https://api.z.ai/api/anthropic", "bearer-token",
                "FACTORY_ZAI_API_KEY", "glm-5.3", List.of("low", "high", "max"), "max", null, null);
        var secrets = Map.of("FACTORY_CODEMIE_PROXY_TOKEN", "gateway-token", "FACTORY_ZAI_API_KEY", "zai-key");
        runSettings = new TaskRunSettings(coding, new ModelCatalog(new ModelCatalog.Properties(List.of(sonnet, glm),
                null), "claude-code", secrets::get));
        var repositories = new DevFactoryProperties("https://github.com", new TreeMap<>(Map.of("mod-bar",
                new DevFactoryProperties.Repository("folio-org/mod-bar", "master", "build:image", "unit",
                        List.of("MODBAR"), List.of(), new DeliveryTarget("folio-org/mod-bar", null)))));
        var rows = new TaskRows(new TaskSourceProperties("Jane Developer"), jira, repositories);
        var controller = new TaskUiController(taskQuery, installations, attempts, admissions,
                dispatcher, outcomes, rows, flows, runSettings);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new UiExceptionHandler())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
        lenient().when(flows.find("dev-factory")).thenReturn(Optional.of(
                new FlowDescriptor("dev-factory", "Developer Flow", "1.0.0", null, null, null, null, null)));
        lenient().when(jira.baseUrl()).thenReturn("https://jira.example.org");
        lenient().when(jira.current()).thenReturn("jira.example.org");
        lenient().when(installations.findById(1)).thenReturn(Optional.of(TaskInstallations.seeded()));
        lenient().when(attempts.findLatestUpdate()).thenReturn(Optional.empty());
    }

    // ----- GET routes are read-only -----

    @Test
    void myTasks_rendersAvailableAndTriggeredFromQueryOnly() throws Exception {
        TaskView available = taskView(TASK_ID, "MODBAR-901", "Open",
                TaskView.TaskState.AWAITING_SELECTION, true, false);
        TaskView stopped = taskView(OTHER_TASK_ID, "MODBAR-911", "In Progress",
                TaskView.TaskState.STOPPED, false, true, attempt("STOPPED", "MANUAL"));
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(available), stopped));

        MvcResult result = mvc.perform(get(FLOW_PAGE))
                .andExpect(status().isOk())
                .andExpect(view().name("flow-my-tasks"))
                .andExpect(model().attributeExists("flow", "source", "available", "statusChips",
                        "triggered", "page", "pollUrl", "pollBaseline"))
                .andReturn();

        verify(taskQuery).myTasks(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        Map<String, Object> model = result.getModelAndView().getModel();
        assertThat(rows(model, "available").getFirst())
                .containsEntry("key", "MODBAR-901")
                .containsEntry("repository", "mod-bar")
                .containsEntry("jiraStatus", "Open")
                .containsEntry("selectable", true)
                .containsEntry("problem", null)
                .containsEntry("shown", true);
        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("stateLabel", "Could not complete")
                .containsEntry("stateClass", "state-pill state-pill-danger")
                .containsEntry("retryAllowed", true)
                .containsEntry("addedByKey", false)
                .containsEntry("link", "/tasks/" + OTHER_TASK_ID)
                .containsEntry("markStoppedAttemptId", null);
        var source = (SourceCard) model.get("source");
        assertThat(source.assignee()).isEqualTo("Jane Developer");
        assertThat(source.jiraHost()).isEqualTo("jira.example.org");
        assertThat(source.query()).isEqualTo("assignee = \"Jane Developer\" AND statusCategory != Done"
                + " ORDER BY updated DESC");
        assertThat(source.searchUrl()).startsWith("https://jira.example.org/issues/?jql=");
        var runForm = (TaskRunSettings.Form) model.get("runForm");
        assertThat(runForm.models()).extracting(TaskRunSettings.ModelOption::id)
                .containsExactly("codemie-sonnet-5.5", "glm-5.3");
        assertThat(runForm.selected().id()).isEqualTo("codemie-sonnet-5.5");
        assertThat(runForm.effort()).isEqualTo("high");
        verifyNoInteractions(admissions, outcomes);
        verify(dispatcher, never()).dispatchNext();
        verify(dispatcher, never()).dispatchNextQuietly();
    }

    @Test
    void myTasks_withThePiRuntimeTheRunFormHasNoModelChoice() throws Exception {
        when(coding.id()).thenReturn("pi");
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of()));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model.get("runForm")).isNull();
    }

    @Test
    void myTasks_statusChipsPressOpenAndInProgressByDefault() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                taskView(TASK_ID, "MODBAR-901", "In Review", TaskView.TaskState.AWAITING_SELECTION, true, false),
                taskView(OTHER_TASK_ID, "MODBAR-902", "In Progress",
                        TaskView.TaskState.AWAITING_SELECTION, true, false),
                taskView(UUID.randomUUID(), "MODBAR-903", "Open",
                        TaskView.TaskState.AWAITING_SELECTION, true, false))));

        var chips = rows(mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel(), "statusChips");

        assertThat(chips).extracting(chip -> chip.get("status"))
                .containsExactly("Open", "In Progress", "In Review");
        assertThat(chips).extracting(chip -> chip.get("pressed")).containsExactly(true, true, false);
    }

    @Test
    void myTasks_pressesEveryChipWhenNoDefaultStatusIsPresent() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                taskView(TASK_ID, "MODBAR-901", "To Do", TaskView.TaskState.AWAITING_SELECTION, true, false))));

        var chips = rows(mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel(), "statusChips");

        assertThat(chips).singleElement().satisfies(chip -> assertThat(chip).containsEntry("pressed", true));
    }

    @Test
    void myTasks_availableCountsOnlyShownTasksThatCanRun() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                taskView(TASK_ID, "MODBAR-901", "Open", TaskView.TaskState.UNSUPPORTED, false, false),
                taskView(OTHER_TASK_ID, "MODBAR-902", "Open", TaskView.TaskState.UNSUPPORTED, false, false),
                taskView(UUID.randomUUID(), "MODBAR-903", "In Progress",
                        TaskView.TaskState.UNSUPPORTED, false, false))));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model).containsEntry("availableShown", 0L).containsEntry("notEligibleShown", 3L);
    }

    @Test
    void myTasks_availableIgnoresTasksHiddenByTheStatusFilter() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                taskView(TASK_ID, "MODBAR-901", "Open", TaskView.TaskState.AWAITING_SELECTION, true, false),
                taskView(OTHER_TASK_ID, "MODBAR-902", "Open", TaskView.TaskState.UNSUPPORTED, false, false),
                taskView(UUID.randomUUID(), "MODBAR-903", "In Review",
                        TaskView.TaskState.AWAITING_SELECTION, true, false),
                taskView(UUID.randomUUID(), "MODBAR-904", "In Review",
                        TaskView.TaskState.UNSUPPORTED, false, false))));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model).containsEntry("availableShown", 1L).containsEntry("notEligibleShown", 1L);
    }

    @Test
    void myTasks_showsQueueIsWaitingWhileAnInspectionHoldsTheSlot() throws Exception {
        TaskView held = taskView(TASK_ID, "MODBAR-921", "Open", TaskView.TaskState.PROCESSING_PAUSED,
                false, false, attempt("NEEDS_INSPECTION", "BY_KEY"));
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), held));
        TaskInstallation installation = TaskInstallations.seeded();
        installation.setActiveAttemptId(ATTEMPT_ID);
        when(installations.findById(1)).thenReturn(Optional.of(installation));
        TaskAttempt attempt = new TaskAttempt(TASK_ID, 1, TaskAttempt.Kind.INITIAL, "run#1",
                TaskAttempt.State.NEEDS_INSPECTION);
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
        when(attempts.countByStateIn(List.of(TaskAttempt.State.RESERVED))).thenReturn(2L);
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
        TaskView draft = taskView(TASK_ID, "MODBAR-921", "Open", TaskView.TaskState.PR_READY,
                false, false, prAttempt(reasons));
        TaskView ready = taskView(OTHER_TASK_ID, "MODBAR-922", "Open", TaskView.TaskState.PR_READY,
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
        assertThat(TaskRows.runContext(draft, EXECUTION_ID))
                .containsEntry("stateLabel", "Draft PR")
                .containsEntry("stateClass", "state-pill state-pill-warning");
        assertThat(TaskRows.runContext(ready, EXECUTION_ID))
                .containsEntry("stateLabel", "PR ready");
    }

    @Test
    void incompleteUsageSaysTheCostMayBeHigherAndExplainsWhy() throws Exception {
        String turnLimit = "Claude Code used all 40 turns before it finished the task."
                + " Retry with more turns or a different effort.";
        TaskView.AttemptHistoryEntry attempt = new TaskView.AttemptHistoryEntry(UUID.randomUUID(), 1,
                TaskAttempt.Kind.INITIAL, TaskAttempt.AdmittedVia.MANUAL, TaskAttempt.State.STOPPED, "coding",
                "DEVELOPMENT_FAILED: " + turnLimit, null, null, EXECUTION_ID, Instant.parse("2026-10-23T07:59:00Z"),
                Instant.parse("2026-10-23T08:00:00Z"), true, 600, incompleteUsage(), null);
        TaskView stopped = withUsage(taskView(TASK_ID, "MODBAR-911", "Open", TaskView.TaskState.STOPPED,
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
    void costNoteSaysSoFarWhileRunningAndCostUnknownWithoutACostFigure() throws Exception {
        String unknownTitle = "The runtime did not report usage, or the usage has no price, so the cost is not known.";
        // A paid start that stopped before the runtime reported any usage, for example on a Docker error.
        TaskView stopped = withUsage(taskView(TASK_ID, "MODBAR-911", "Open", TaskView.TaskState.STOPPED,
                false, true, attempt("STOPPED", 1, unreportedUsage())), unreportedUsage());
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), stopped));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();
        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("cost", null)
                .containsEntry("costNote", "cost unknown")
                .containsEntry("costNoteTitle", unknownTitle);

        // The retry is running and has not reported usage yet: its cost is "so far", like Working in Triggered.
        TaskView working = withUsage(taskView(TASK_ID, "MODBAR-911", "Open", TaskView.TaskState.WORKING,
                false, false, attempt("STOPPED", 1, unreportedUsage()), attempt("RUNNING", 2, unreportedUsage())),
                unreportedUsage());
        when(taskQuery.detail(TASK_ID)).thenReturn(working);

        List<Map<String, Object>> attempts = rows(mvc.perform(get("/tasks/{taskId}", TASK_ID)).andReturn()
                .getModelAndView().getModel(), "attempts");
        assertThat(attempts.get(0))
                .containsEntry("cost", null)
                .containsEntry("costNote", "cost unknown")
                .containsEntry("costNoteTitle", unknownTitle);
        assertThat(attempts.get(1))
                .containsEntry("cost", null)
                .containsEntry("costNote", "so far")
                .containsEntry("costNoteTitle", null);

        // A queued attempt has no cost note: its Started cell already says "not started".
        when(taskQuery.detail(OTHER_TASK_ID)).thenReturn(taskView(OTHER_TASK_ID, "MODBAR-912", "Open",
                TaskView.TaskState.WORKING, false, false, attempt("RESERVED", 1, emptyUsage())));
        assertThat(rows(mvc.perform(get("/tasks/{taskId}", OTHER_TASK_ID)).andReturn().getModelAndView().getModel(),
                "attempts").getFirst())
                .containsEntry("costNote", null)
                .containsEntry("costNoteTitle", null);
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
                TaskView.TaskState.STOPPED, false, true,
                attempt("STOPPED", "MANUAL", 1), attempt("STOPPED", "BY_KEY", 2)));

        MvcResult result = mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("task"))
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
        verifyNoInteractions(admissions, outcomes);
        verify(dispatcher, never()).dispatchNext();
        verify(dispatcher, never()).dispatchNextQuietly();
    }

    @Test
    void taskDetail_retryFormIsFilledFromThePreviousAttempt() throws Exception {
        String previous = "{\"profileId\":\"glm-5.3\",\"profileLabel\":\"GLM-5.3 (Z.ai)\",\"model\":\"glm-5.3\","
                + "\"endpointOrigin\":\"https://api.z.ai\",\"effort\":\"low\",\"maxTurns\":77,"
                + "\"instructions\":\"Keep the public API.\"}";
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-911", "Open",
                TaskView.TaskState.STOPPED, false, true,
                attempt("STOPPED", "MANUAL", 1, null), attempt("STOPPED", "MANUAL", 2, previous)));

        MvcResult result = mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andReturn();

        var model = result.getModelAndView().getModel();
        var retryForm = (TaskRunSettings.Form) model.get("retryForm");
        assertThat(retryForm.selected().id()).isEqualTo("glm-5.3");
        assertThat(retryForm.effort()).isEqualTo("low");
        assertThat(model.get("retryInstructions")).isEqualTo("Keep the public API.");
        assertThat(rows(model, "attempts")).extracting(row -> row.get("run"))
                .containsExactly(null, "glm-5.3 · low");
    }

    @Test
    void taskDetail_unknownTaskRendersNotFoundPage() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenThrow(new NoSuchElementException("No task " + TASK_ID));

        mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void poll_reportsRevisionWithoutCommandServices() throws Exception {
        when(attempts.count()).thenReturn(2L);

        mvc.perform(get("/tasks/poll"))
                .andExpect(status().isOk());

        verifyNoInteractions(admissions, outcomes);
        verify(dispatcher, never()).dispatchNext();
        verify(dispatcher, never()).dispatchNextQuietly();
    }

    // ----- run selected -----

    @Test
    void runSelected_mapsFormToRunSelectedCommand() throws Exception {
        when(admissions.runSelected(any())).thenReturn(
                new TaskAdmissionService.AdmissionResult(List.of(TASK_ID), Map.of()));

        mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString())
                        .param("version-" + TASK_ID, "3")
                        .param("input-" + TASK_ID, "revision-MODBAR-901")
                        .param("destination-" + TASK_ID, "dest-MODBAR-901"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "Queued 1 task."));

        verify(admissions).runSelected(runCaptor.capture());
        TaskCommands.RunSelected run = runCaptor.getValue();
        assertThat(run.requestId()).startsWith("ui-");
        assertThat(run.requestId().length()).isLessThanOrEqualTo(63);
        // Without a choice the run takes the default model and its default effort.
        assertThat(run.selections()).singleElement().satisfies(selection -> {
            assertThat(selection).isEqualTo(new TaskCommands.Selection(TASK_ID, 3L, "revision-MODBAR-901",
                    "dest-MODBAR-901", selection.runSettings()));
            assertThat(TaskRunSettings.settings(selection.runSettings()))
                    .isEqualTo(new CodingRequest.RunSettings("codemie-sonnet-5.5", "high", null));
        });
    }

    @Test
    void runSelected_givesEachTaskTheEffortOfItsRowWithTheChosenModel() throws Exception {
        when(admissions.runSelected(any())).thenReturn(
                new TaskAdmissionService.AdmissionResult(List.of(TASK_ID, OTHER_TASK_ID), Map.of()));

        mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString(), OTHER_TASK_ID.toString())
                        .param("modelId", "glm-5.3")
                        .param("effort-" + TASK_ID, "low").param("effort-" + OTHER_TASK_ID, "max"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE));

        verify(admissions).runSelected(runCaptor.capture());
        assertThat(runCaptor.getValue().selections())
                .extracting(selection -> TaskRunSettings.settings(selection.runSettings()))
                .containsExactly(new CodingRequest.RunSettings("glm-5.3", "low", null),
                        new CodingRequest.RunSettings("glm-5.3", "max", null));
        assertThat(runCaptor.getValue().selections()).allSatisfy(selection ->
                assertThat(selection.runSettings()).doesNotContain("zai-key"));
    }

    @Test
    void runSelected_badRunSettingsAreRejectedWithAPlainMessage() throws Exception {
        MvcResult result = mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString())
                        .param("modelId", "glm-5.3").param("effort-" + TASK_ID, "medium"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(java.net.URLDecoder.decode(result.getResponse().getRedirectedUrl(),
                java.nio.charset.StandardCharsets.UTF_8))
                .endsWith("Effort medium is not available for model glm-5.3. Choose one of: low, high, max.");

        result = mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString()).param("modelId", "gone"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(java.net.URLDecoder.decode(result.getResponse().getRedirectedUrl(),
                java.nio.charset.StandardCharsets.UTF_8))
                .endsWith("There is no model gone. Reload the page and choose a model from the list.");
        verifyNoInteractions(admissions);
    }

    @Test
    void runSelected_surfacesPerRowRefusals() throws Exception {
        when(admissions.runSelected(any())).thenReturn(new TaskAdmissionService.AdmissionResult(List.of(),
                Map.of(TASK_ID, "Your GitHub token cannot push to folio-org/mod-bar")));
        when(taskQuery.displayKey(TASK_ID)).thenReturn(Optional.of("MODBAR-901"));

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

    // ----- retry -----

    @Test
    void retry_fromTheFlowPageReturnsThere() throws Exception {
        when(admissions.retry(any())).thenReturn(new TaskAdmissionService.RetryResult(ATTEMPT_ID, null, TaskAttempt.State.RESERVED, "the retry joined the queue", false));

        mvc.perform(post("/tasks/{taskId}/retry", TASK_ID)
                        .param("expectedVersion", "3")
                        .param("inputFingerprint", "revision-MODBAR-901")
                        .param("destinationFingerprint", "dest-MODBAR-901")
                        .param("returnTo", "flow"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "Retry queued: the retry joined the queue"));

        verify(admissions).retry(retryCaptor.capture());
        TaskCommands.Retry command = retryCaptor.getValue();
        assertThat(command.taskId()).isEqualTo(TASK_ID);
        assertThat(command.expectedVersion()).isEqualTo(3L);
        assertThat(command.inputFingerprint()).isEqualTo("revision-MODBAR-901");
        assertThat(command.destinationFingerprint()).isEqualTo("dest-MODBAR-901");
        assertThat(command.requestId()).startsWith("ui-");
    }

    @Test
    void retry_carriesModelEffortAndInstructions() throws Exception {
        when(admissions.retry(any())).thenReturn(new TaskAdmissionService.RetryResult(ATTEMPT_ID, null, TaskAttempt.State.RESERVED, "the retry joined the queue", false));

        mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "3")
                        .param("modelId", "glm-5.3").param("effort", "max")
                        .param("instructions", "  Read the ADR through the wiki REST URL.  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks/" + TASK_ID));

        verify(admissions).retry(retryCaptor.capture());
        assertThat(TaskRunSettings.settings(retryCaptor.getValue().runSettings())).isEqualTo(
                new CodingRequest.RunSettings("glm-5.3", "max", "Read the ADR through the wiki REST URL."));
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
        verify(admissions, never()).retry(any());
    }

    @Test
    void retry_refusalReturnsToTheTaskSummary() throws Exception {
        when(admissions.retry(any())).thenReturn(new TaskAdmissionService.RetryResult(null, null, TaskAttempt.State.STOPPED, "Your GitHub token cannot push to folio-org/mod-bar", false));

        MvcResult result = mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "1"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/tasks/" + TASK_ID + "?error=")
                .contains("cannot+push");
    }

    @Test
    void retry_refusedWhileTheTaskIsQueuedIsNotShownAsQueued() throws Exception {
        when(admissions.retry(any())).thenReturn(new TaskAdmissionService.RetryResult(ATTEMPT_ID, null, TaskAttempt.State.RESERVED, "task already has a live attempt", true));

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
        mvc.perform(post("/tasks/run-selected").param("taskId", TASK_ID.toString())
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/tasks/{taskId}/attempts/{attemptId}/mark-stopped", TASK_ID, ATTEMPT_ID)
                        .header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(admissions, outcomes, dispatcher);
    }

    // ----- helpers -----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(MvcResult result) {
        return (Map<String, Object>) result.getModelAndView().getModel().get("task");
    }

    /** Page rows as maps of their fields, so records and the maps older rows still use read alike. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> model, String name) {
        return ((List<Object>) model.get(name)).stream().map(TaskUiControllerTest::fields).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Object row) {
        if (row instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        var fields = new LinkedHashMap<String, Object>();
        for (var component : row.getClass().getRecordComponents()) {
            try {
                fields.put(component.getName(), component.getAccessor().invoke(row));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return fields;
    }

    private static TaskQuery.MyTasks myTasks(List<TaskView> available, TaskView... triggered) {
        return new TaskQuery.MyTasks(available,
                new PageImpl<>(List.of(triggered), PageRequest.of(0, 20), triggered.length));
    }

    private static TaskView taskView(UUID taskId, String key, String jiraStatus, TaskView.TaskState state,
                                          boolean select, boolean retry,
                                          TaskView.AttemptHistoryEntry... attempts) {
        return new TaskView(taskId, key, "https://jira.example.org/browse/" + key, "Title of " + key,
                "MODBAR", jiraStatus, state, null,
                state == TaskView.TaskState.STOPPED ? "VERIFICATION_FAILED: 2 tests failed" : null,
                state == TaskView.TaskState.STOPPED ? "verification" : null, null,
                "mod-bar",
                state == TaskView.TaskState.PR_READY ? "https://github.com/folio-org/mod-bar/pull/7" : null,
                state == TaskView.TaskState.PR_READY && attempts.length > 0
                        ? attempts[attempts.length - 1].draftReasons() : null,
                java.util.Arrays.stream(attempts)
                        .noneMatch(attempt -> attempt.admittedVia() == TaskAttempt.AdmittedVia.BY_KEY),
                java.util.Arrays.stream(attempts)
                        .anyMatch(attempt -> attempt.admittedVia() == TaskAttempt.AdmittedVia.BY_KEY),
                3L, "revision-" + key, "dest-" + key,
                List.of(attempts), emptyUsage(), new TaskView.AllowedActions(select, retry));
    }

    private static TaskView.AttemptHistoryEntry attempt(String state, String admittedVia) {
        return attempt(state, admittedVia, 1);
    }

    private static TaskView.AttemptHistoryEntry attempt(String state, String admittedVia, int ordinal) {
        return attempt(state, admittedVia, ordinal, null);
    }

    private static TaskView.AttemptHistoryEntry attempt(String state, String admittedVia, int ordinal,
                                                             String runSettings) {
        return new TaskView.AttemptHistoryEntry("NEEDS_INSPECTION".equals(state) ? ATTEMPT_ID : UUID.randomUUID(),
                ordinal, TaskAttempt.Kind.INITIAL, TaskAttempt.AdmittedVia.valueOf(admittedVia),
                TaskAttempt.State.valueOf(state), "verification", "VERIFICATION_FAILED: 2 tests failed", null,
                null, EXECUTION_ID, Instant.parse("2026-10-23T07:59:00Z"), Instant.parse("2026-10-23T08:00:00Z"),
                true, 600, emptyUsage(), runSettings);
    }

    /** A delivered attempt; {@code draftReasons} null means the pull request is ready for review. */
    private static TaskView.AttemptHistoryEntry prAttempt(List<String> draftReasons) {
        return new TaskView.AttemptHistoryEntry(UUID.randomUUID(), 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.AdmittedVia.MANUAL, TaskAttempt.State.PR_READY, "publish",
                null, "https://github.com/folio-org/mod-bar/pull/7", draftReasons, EXECUTION_ID,
                Instant.parse("2026-10-23T07:59:00Z"), Instant.parse("2026-10-23T08:00:00Z"),
                true, 600, emptyUsage(), null);
    }

    private static TaskUsage emptyUsage() {
        return new TaskUsage(0, Map.of(), new TreeMap<String, BigDecimal>(), true, false, List.of());
    }

    private static TaskView.AttemptHistoryEntry attempt(String state, int ordinal, TaskUsage usage) {
        return new TaskView.AttemptHistoryEntry(UUID.randomUUID(), ordinal, TaskAttempt.Kind.INITIAL,
                TaskAttempt.AdmittedVia.MANUAL, TaskAttempt.State.valueOf(state),
                "verification", "VERIFICATION_FAILED: 2 tests failed", null, null, EXECUTION_ID,
                Instant.parse("2026-10-23T07:59:00Z"), Instant.parse("2026-10-23T08:00:00Z"), true, 600, usage, null);
    }

    /** A paid start with no reported usage: no cost figure, and the total is incomplete. */
    private static TaskUsage unreportedUsage() {
        return new TaskUsage(0, Map.of(), new TreeMap<>(), false, false, List.of());
    }

    /** $0.32 so far, but some usage was not reported, so the total is incomplete. */
    private static TaskUsage incompleteUsage() {
        return new TaskUsage(10_000, Map.of(), new TreeMap<>(Map.of("USD", new BigDecimal("0.32"))), false,
                false, List.of());
    }

    private static TaskView withUsage(TaskView view, TaskUsage usage) {
        return new TaskView(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(), view.projectKey(),
                view.jiraStatus(), view.state(), view.queuePosition(), view.reason(), view.stage(),
                view.stageStartedAt(), view.destinationRepository(), view.pullRequestUrl(), view.draftReasons(),
                view.inScope(), view.addedByKey(), view.version(), view.inputFingerprint(),
                view.destinationFingerprint(),
                view.attempts(), usage, view.allowedActions());
    }
}
