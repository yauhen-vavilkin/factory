package org.folio.factory.app.web;

import org.folio.factory.app.tasks.presentation.AgentQuestion;
import org.folio.factory.app.tasks.presentation.AttemptClock;
import org.folio.factory.app.tasks.presentation.AttemptStatus;
import org.folio.factory.app.tasks.presentation.FeedbackStanding;
import org.folio.factory.app.tasks.presentation.Turn;
import org.folio.factory.app.tasks.presentation.RunLimits;
import org.folio.factory.app.tasks.presentation.TaskDetail;
import org.folio.factory.app.tasks.presentation.TaskAnswers;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.app.tasks.presentation.CannotRunRow;
import org.folio.factory.app.tasks.presentation.InProgressStrip;
import org.folio.factory.app.tasks.presentation.RunAction;
import org.folio.factory.app.tasks.presentation.SourceCard;
import org.folio.factory.app.tasks.presentation.TaskRevisions;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.folio.factory.app.tasks.presentation.TaskUsage;
import org.folio.factory.app.tasks.presentation.TaskView;
import org.folio.factory.app.tasks.presentation.TriggeredRow;
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
import static org.assertj.core.api.Assertions.tuple;
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

    @Mock
    private ArtifactStore artifactStore;

    @Mock
    private PipelineExecutionRepository executions;

    @Captor
    private ArgumentCaptor<TaskCommands.RunSelected> runCaptor;

    @Captor
    private ArgumentCaptor<TaskCommands.Retry> retryCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private MockMvc mvc;

    private TaskRunSettings runSettings;

    private final CodingRuntime coding = org.mockito.Mockito.mock(CodingRuntime.class);

    /** Where PR feedback stands; NONE unless a test says otherwise. */
    private final FeedbackUi feedback = org.mockito.Mockito.mock(FeedbackUi.class);

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
        var limits = org.mockito.Mockito.mock(RunLimits.class);
        lenient().when(limits.limits()).thenReturn(new AttemptClock.Limits(1800, java.time.Duration.ofMinutes(60)));
        lenient().when(feedback.standing(any())).thenReturn(FeedbackStanding.NONE);
        var rows = new TaskRows(new TaskSourceProperties("Jane Developer"), jira, repositories, limits, feedback);
        var revisions = new TaskRevisions(attempts, executions, dispatcher, taskQuery);
        var controller = new TaskUiController(taskQuery, installations, attempts, admissions,
                dispatcher, rows, flows, runSettings, revisions);
        var page = new TaskPageController(taskQuery, admissions, rows, runSettings, new TaskAnswers(artifactStore),
                outcomes, dispatcher);
        mvc = MockMvcBuilders.standaloneSetup(controller, page)
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
    void myTasks_isReadOnly() throws Exception {
        TaskView stopped = taskView(OTHER_TASK_ID, "MODBAR-911", "In Progress",
                TaskView.TaskState.STOPPED, false, true, attempt("STOPPED", "MANUAL"));
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), stopped));

        mvc.perform(get(FLOW_PAGE)).andExpect(status().isOk());

        verifyNoInteractions(admissions, outcomes);
        verify(dispatcher, never()).dispatchNext();
        verify(dispatcher, never()).dispatchNextQuietly();
    }

    @Test
    void myTasks_buildsRowsSourceAndRunFormFromTheQuery() throws Exception {
        TaskView available = taskView(TASK_ID, "MODBAR-901", "Open",
                TaskView.TaskState.AWAITING_SELECTION, true, false);
        TaskView stopped = taskView(OTHER_TASK_ID, "MODBAR-911", "In Progress",
                TaskView.TaskState.STOPPED, false, true, attempt("STOPPED", "MANUAL"));
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(available), stopped));

        MvcResult result = mvc.perform(get(FLOW_PAGE))
                .andExpect(status().isOk())
                .andExpect(view().name("flow-my-tasks"))
                .andExpect(model().attributeExists("flow", "source", "available", "cannotRun",
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
                .containsEntry("problem", null);
        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("chipLabel", "Failed")
                .containsEntry("chipClass", "chip chip-danger")
                .containsEntry("chipUrl", null)
                .containsEntry("detail", "Checks failed: 2 tests failed.")
                .containsEntry("detailTitle", "Checks failed: 2 tests failed.")
                // No model was recorded for this attempt, so the facts are the attempts and the agent's time.
                .containsEntry("facts", List.of("1 attempt", "10 min"))
                // The button of a failed task opens its task page at the next attempt.
                .containsEntry("action", RunAction.RETRY)
                .containsEntry("button",
                        new TriggeredRow.Button("Start attempt 2", "/tasks/" + OTHER_TASK_ID + "#retry"))
                .containsEntry("markStoppedAttemptId", null);
        assertThat(model.get("inProgress")).isNull();
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
    }

    @Test
    void myTasks_withThePiRuntimeTheRunFormHasNoModelChoice() throws Exception {
        when(coding.id()).thenReturn("pi");
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of()));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model.get("runForm")).isNull();
    }

    @Test
    void myTasks_listsTasksThatCannotRunOneRowEachWithItsCause() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                taskView(TASK_ID, "MODBAR-901", "Open", TaskView.TaskState.AWAITING_SELECTION, true, false),
                unsupported(OTHER_TASK_ID, "MODBAR-902", TaskRoutingReason.noRepository("MODBAR")),
                unsupported(UUID.randomUUID(), "MODBAR-903", TaskRoutingReason.noRepository("MODBAR")),
                unsupported(UUID.randomUUID(), "MODBAR-904", "Out of scope"),
                unsupported(UUID.randomUUID(), "MODBAR-905",
                        TaskRoutingReason.severalRepositories("MODBAR", List.of("mod-bar", "mod-bar-fork"))))));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(rows(model, "available")).extracting(row -> row.get("key")).containsExactly("MODBAR-901");
        @SuppressWarnings("unchecked")
        var cannotRun = (List<CannotRunRow>) model.get("cannotRun");
        assertThat(cannotRun).extracting(CannotRunRow::key, CannotRunRow::chip, CannotRunRow::reason,
                CannotRunRow::needsRepository).containsExactly(
                tuple("MODBAR-902", "No repository for MODBAR",
                        "Project MODBAR has no repository in config/developer-repositories.yaml.", true),
                tuple("MODBAR-903", "No repository for MODBAR",
                        "Project MODBAR has no repository in config/developer-repositories.yaml.", true),
                tuple("MODBAR-904", "Cannot run", "Out of scope.", false),
                tuple("MODBAR-905", "Several repositories for MODBAR",
                        "Project MODBAR maps to more than one repository in config/developer-repositories.yaml.",
                        true));
        assertThat(model).containsEntry("repositorySetupNeeded", true);
    }

    @Test
    void myTasks_offersRepositorySetupOnlyWhenARepositoryWouldLetATaskRun() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(
                unsupported(OTHER_TASK_ID, "MODBAR-906", "Out of scope"))));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(rows(model, "cannotRun")).extracting(row -> row.get("key")).containsExactly("MODBAR-906");
        assertThat(model).containsEntry("repositorySetupNeeded", false);
    }

    @Test
    void myTasks_showsTheRunningTaskInTheStripAndItsRowIsNotYourTurn() throws Exception {
        TaskView running = taskView(TASK_ID, "MODBAR-931", "Open", TaskView.TaskState.WORKING, false, false,
                attempt("RUNNING", "MANUAL", 1,
                        "{\"profileId\":\"glm-5.3\",\"effort\":\"max\"}"));
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), running));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        var strip = (InProgressStrip) model.get("inProgress");
        assertThat(strip.key()).isEqualTo("MODBAR-931");
        assertThat(strip.run()).startsWith("glm-5.3 · max · ");
        assertThat(strip.runUrl()).isEqualTo("/executions/" + EXECUTION_ID);
        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("chipLabel", "In progress")
                .containsEntry("chipClass", "chip chip-info")
                .containsEntry("detail", null)
                // An attempt in flight says which one it is, and no time: the strip above has it.
                .containsEntry("facts", List.of("glm-5.3 · max", "Attempt 1"))
                .containsEntry("action", RunAction.OPEN_RUN)
                .containsEntry("button", null);
        assertThat(RunAction.OPEN_RUN.yours()).isFalse();
    }

    @Test
    void myTasks_aTaskThatRestsSaysItsAttemptsAndTheTimeTheAgentWorkedInAllOfThem() throws Exception {
        TaskView stopped = taskView(TASK_ID, "MODBAR-951", "Open", TaskView.TaskState.STOPPED, false, true,
                attempt("STOPPED", "MANUAL", 1),
                retryAttempt(2, "{\"profileId\":\"glm-5.3\",\"effort\":\"max\"}"));

        var row = rows(myTasksModel(stopped), "triggered").getFirst();

        // The model of the latest attempt, and 10 minutes in each of the two attempts.
        assertThat(row)
                .containsEntry("facts", List.of("glm-5.3 · max", "2 attempts", "20 min"))
                .containsEntry("button", new TriggeredRow.Button("Start attempt 3", "/tasks/" + TASK_ID + "#retry"));
    }

    @Test
    void myTasks_aRunThatAskedNeedsYourAnswerAndIsYourTurn() throws Exception {
        var reason = "clarify-implementation: " + TaskStops.DECISION_REASON + "Which API should change?";
        var stopped = taskView(TASK_ID, "MODBAR-941", "Open", TaskView.TaskState.STOPPED, false, true,
                attempt("STOPPED", "MANUAL"));
        TaskView asked = new TaskView(stopped.taskId(), stopped.displayKey(), stopped.jiraUrl(), stopped.title(),
                stopped.projectKey(), stopped.jiraStatus(), stopped.state(), null, reason,
                "clarify-implementation", null, stopped.destinationRepository(), null, null, true, false,
                stopped.version(), stopped.inputFingerprint(), stopped.destinationFingerprint(),
                stopped.attempts(), stopped.usage(), stopped.allowedActions());
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), asked));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("chipLabel", "Needs your answer")
                .containsEntry("chipClass", "chip chip-warning")
                .containsEntry("detail", null)
                .containsEntry("action", RunAction.ANSWER)
                .containsEntry("button", new TriggeredRow.Button("Answer", "/tasks/" + TASK_ID + "#answer"));
        assertThat(RunAction.ANSWER.yours()).isTrue();
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

        assertThat(model.get("queueWaiting")).isEqualTo("MODBAR-921 needs inspection: Factory could not confirm"
                + " how its run ended. It rechecks every minute.");
        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("chipLabel", "Needs inspection")
                // The chip says it, so the row has no words about the outcome.
                .containsEntry("detail", null)
                .containsEntry("action", RunAction.MARK_STOPPED)
                .containsEntry("button", null)
                .containsEntry("markStoppedAttemptId", ATTEMPT_ID);
    }

    @Test
    void myTasks_explainsWhyQueuedTasksAreNotStarting() throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of()));
        when(attempts.countByStateIn(List.of(TaskAttempt.State.RESERVED))).thenReturn(2L);
        when(dispatcher.queueProblem()).thenReturn("Developer Flow preflight failed: Docker is not running.");

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();

        assertThat(model)
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
                .containsEntry("chipLabel", "Draft PR #7")
                .containsEntry("chipClass", "chip chip-neutral")
                .containsEntry("chipUrl", "https://github.com/folio-org/mod-bar/pull/7")
                .containsEntry("detail", null)
                .containsEntry("action", RunAction.PR_FEEDBACK);
        assertThat(rows(model, "triggered").get(1))
                .containsEntry("chipLabel", "PR #7")
                .containsEntry("chipClass", "chip chip-success");

        MvcResult result = mvc.perform(get("/tasks/{taskId}", TASK_ID)).andExpect(status().isOk()).andReturn();
        assertThat(detail(result))
                .containsEntry("chipLabel", "Draft PR #7")
                .containsEntry("chipClass", "chip chip-neutral")
                .containsEntry("chipUrl", draft.pullRequestUrl())
                .containsEntry("headline", "Pull request #7 is open as a draft")
                .containsEntry("draftReasons", reasons)
                .containsEntry("prAction", "Open pull request #7");
        assertThat(rows(result.getModelAndView().getModel(), "attempts").getFirst())
                .containsEntry("chipLabel", "Draft PR #7")
                .containsEntry("chipClass", "chip chip-neutral")
                .containsEntry("result", "Pull request #7 opened as a draft");
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
                .containsEntry("costTitle",
                        "Some usage was not reported by the runtime or has no price, so the real cost may be higher.");

        Map<String, Object> detail = mvc.perform(get("/tasks/{taskId}", TASK_ID)).andReturn().getModelAndView()
                .getModel();
        assertThat(rows(detail, "attempts").getFirst())
                .containsEntry("costText", "$0.32")
                .containsEntry("costNoteTitle",
                        "Some usage was not reported by the runtime or has no price, so the real cost may be higher.")
                .containsEntry("ordinal", 1)
                .containsEntry("executionId", EXECUTION_ID)
                .containsEntry("chipLabel", "Failed")
                // A reason stored by an older run reads like the reason a run stores now; the row has room for
                // its first sentence.
                .containsEntry("result", "The agent reached its limit of 40 turns before it finished.");
    }

    private static final String UNKNOWN_COST_TITLE =
            "The runtime did not report usage, or the usage has no price, so the cost is not known.";

    @Test
    void aStoppedTaskWithoutReportedUsageHasNoCostAndSaysItIsUnknown() throws Exception {
        // A paid start that stopped before the runtime reported any usage, for example on a Docker error.
        TaskView stopped = withUsage(taskView(TASK_ID, "MODBAR-911", "Open", TaskView.TaskState.STOPPED,
                false, true, attempt("STOPPED", 1, unreportedUsage())), unreportedUsage());
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), stopped));

        Map<String, Object> model = mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel();
        assertThat(rows(model, "triggered").getFirst())
                .containsEntry("cost", null)
                .containsEntry("costTitle", UNKNOWN_COST_TITLE);
    }

    @Test
    void aRetryThatWorksCostsZeroSoFarAndTheEarlierAttemptCostIsUnknown() throws Exception {
        // The retry is running and has not reported usage yet: $0.00 so far, as the run card says.
        TaskView working = withUsage(taskView(TASK_ID, "MODBAR-911", "Open", TaskView.TaskState.WORKING,
                false, false, attempt("STOPPED", 1, unreportedUsage()), attempt("RUNNING", 2, unreportedUsage())),
                unreportedUsage());
        when(taskQuery.detail(TASK_ID)).thenReturn(working);

        var taskModel = mvc.perform(get("/tasks/{taskId}", TASK_ID)).andReturn().getModelAndView().getModel();
        List<Map<String, Object>> attempts = rows(taskModel, "attempts");
        // Newest first.
        assertThat(attempts.get(0))
                .containsEntry("cost", "$0.00")
                .containsEntry("costNoteTitle", null)
                .containsEntry("costText", "$0.00 so far");
        assertThat(attempts.get(1))
                .containsEntry("cost", null)
                .containsEntry("costText", "cost unknown")
                .containsEntry("costNoteTitle", UNKNOWN_COST_TITLE);
        assertThat(((TaskDetail) taskModel.get("task")).spent()).isEqualTo("$0.00");
    }

    @Test
    void aWorkingTaskInRunsShowsTheCostOfTheAttemptThatWorksNow() throws Exception {
        // Not the total of the earlier attempts.
        TaskView retrying = withUsage(taskView(TASK_ID, "MODBAR-911", "Open", TaskView.TaskState.WORKING,
                false, false, attempt("STOPPED", 1, incompleteUsage()), attempt("RUNNING", 2, unreportedUsage())),
                incompleteUsage());
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), retrying));

        assertThat(rows(mvc.perform(get(FLOW_PAGE)).andReturn().getModelAndView().getModel(), "triggered").getFirst())
                .containsEntry("cost", "$0.00 so far")
                .containsEntry("costTitle", null);
    }

    @Test
    void tokensWithoutAPriceKeepTheDashWhileTheAttemptWorks() throws Exception {
        TaskView unpriced = withUsage(taskView(TASK_ID, "MODBAR-911", "Open", TaskView.TaskState.WORKING,
                false, false, attempt("RUNNING", 1, unpricedUsage())), unpricedUsage());
        when(taskQuery.detail(TASK_ID)).thenReturn(unpriced);

        assertThat(rows(mvc.perform(get("/tasks/{taskId}", TASK_ID)).andReturn().getModelAndView().getModel(),
                "attempts").getFirst())
                .containsEntry("cost", null)
                .containsEntry("costText", "—");
    }

    @Test
    void aQueuedAttemptHasNoCostNote() throws Exception {
        // Its Started cell already says "not started".
        when(taskQuery.detail(OTHER_TASK_ID)).thenReturn(taskView(OTHER_TASK_ID, "MODBAR-912", "Open",
                TaskView.TaskState.WORKING, false, false, attempt("RESERVED", 1, emptyUsage())));

        assertThat(rows(mvc.perform(get("/tasks/{taskId}", OTHER_TASK_ID)).andReturn().getModelAndView().getModel(),
                "attempts").getFirst())
                .containsEntry("costText", "—")
                .containsEntry("costNoteTitle", null);
    }

    // ----- PR feedback standing and whose turn it is -----

    @Test
    void aMergedPullRequestReadsAsMergedInRunsAndOnTheTaskPage() throws Exception {
        TaskView delivered = taskView(TASK_ID, "MODBAR-921", "Open", TaskView.TaskState.PR_READY,
                false, false, prAttempt(null));
        givenStanding(delivered, FeedbackStanding.MERGED);

        assertThat(rows(myTasksModel(delivered), "triggered").getFirst())
                .containsEntry("chipLabel", "Merged PR #7")
                .containsEntry("chipClass", "chip chip-success")
                .containsEntry("chipUrl", "https://github.com/folio-org/mod-bar/pull/7");
        var task = taskPage();
        assertThat(task.status().chipLabel()).isEqualTo("Merged PR #7");
        assertThat(task.status().headline()).isEqualTo("Pull request #7 is merged");
        assertThat(task.turn()).isEqualTo(Turn.NONE);
        assertThat(attemptRowsOfTheTaskPage().getFirst()).containsEntry("status", task.status());
    }

    @Test
    void anAttemptThatNeedsInspectionKeepsItsChipAfterItsPullRequestIsMerged() throws Exception {
        var delivered = prAttempt(null);
        var held = new TaskView.AttemptHistoryEntry(ATTEMPT_ID, 1, delivered.kind(), delivered.admittedVia(),
                TaskAttempt.State.NEEDS_INSPECTION, delivered.stage(), null, delivered.pullRequestUrl(), null,
                EXECUTION_ID, delivered.createdAt(), delivered.startedAt(), true, 600, emptyUsage(), null);
        TaskView task = taskView(TASK_ID, "MODBAR-921", "Open", TaskView.TaskState.PROCESSING_PAUSED,
                false, false, held);
        givenStanding(task, FeedbackStanding.MERGED);

        assertThat(rows(myTasksModel(task), "triggered").getFirst()).containsEntry("chipLabel", "Needs inspection");
        assertThat(taskPage().status().chipLabel()).isEqualTo("Needs inspection");
        assertThat(attemptRowsOfTheTaskPage().getFirst()).containsEntry("status", taskPage().status());
    }

    /** My tasks and the run page give a held run to the developer; the task page must say the same. */
    @Test
    void aRunThatNeedsInspectionIsYourTurnWhileAPrFeedbackRoundWorks() throws Exception {
        var delivered = prAttempt(null);
        var held = new TaskView.AttemptHistoryEntry(ATTEMPT_ID, 1, delivered.kind(), delivered.admittedVia(),
                TaskAttempt.State.NEEDS_INSPECTION, delivered.stage(), null, delivered.pullRequestUrl(), null,
                EXECUTION_ID, delivered.createdAt(), delivered.startedAt(), true, 600, emptyUsage(), null);
        TaskView task = taskView(TASK_ID, "MODBAR-921", "Open", TaskView.TaskState.PROCESSING_PAUSED,
                false, false, held);
        givenStanding(task, FeedbackStanding.WORKING);

        assertThat(rows(myTasksModel(task), "triggered").getFirst())
                .containsEntry("action", RunAction.MARK_STOPPED);
        assertThat(RunAction.MARK_STOPPED.yours()).isTrue();
        assertThat(taskPage().turn()).isEqualTo(Turn.YOURS);
    }

    @Test
    void aPullRequestClosedWithoutMergeReadsAsClosedInRunsAndOnTheTaskPage() throws Exception {
        TaskView delivered = taskView(TASK_ID, "MODBAR-921", "Open", TaskView.TaskState.PR_READY,
                false, false, prAttempt(null));
        givenStanding(delivered, FeedbackStanding.CLOSED);

        assertThat(rows(myTasksModel(delivered), "triggered").getFirst())
                .containsEntry("chipLabel", "Closed PR #7")
                .containsEntry("chipClass", "chip chip-neutral");
        var task = taskPage();
        assertThat(task.status().chipLabel()).isEqualTo("Closed PR #7");
        assertThat(task.status().headline()).isEqualTo("Pull request #7 was closed without merge");
        assertThat(attemptRowsOfTheTaskPage().getFirst()).containsEntry("status", task.status());
    }

    @Test
    void prFeedbackThatWaitsForYouIsYourTurnInRunsAndOnTheTaskPage() throws Exception {
        TaskView delivered = taskView(TASK_ID, "MODBAR-921", "Open", TaskView.TaskState.PR_READY,
                false, false, prAttempt(null));
        givenStanding(delivered, FeedbackStanding.WAITS_FOR_YOU);

        assertThat(rows(myTasksModel(delivered), "triggered").getFirst())
                .containsEntry("chipLabel", "PR #7")
                // Your turn and the button say it, so the row has no words about it.
                .containsEntry("detail", null)
                .containsEntry("action", RunAction.PR_WAITING)
                .containsEntry("button",
                        new TriggeredRow.Button("Open PR feedback", "/executions/" + EXECUTION_ID + "#pr-feedback"));
        assertThat(RunAction.PR_WAITING.yours()).isTrue();
        var task = taskPage();
        assertThat(task.reasonNote()).isEqualTo("PR feedback waits for you.");
        assertThat(task.run()).isEqualTo(new TaskDetail.RunLink("Open PR feedback",
                "/executions/" + EXECUTION_ID + "#pr-feedback", true));
        assertThat(task.turn()).isEqualTo(Turn.YOURS);
        assertThat(task.turn().label()).isEqualTo("Your turn");
    }

    @Test
    void aPrFeedbackRoundThatWorksIsFactorysTurn() throws Exception {
        TaskView delivered = taskView(TASK_ID, "MODBAR-921", "Open", TaskView.TaskState.PR_READY,
                false, false, prAttempt(null));
        givenStanding(delivered, FeedbackStanding.WORKING);

        assertThat(rows(myTasksModel(delivered), "triggered").getFirst())
                .containsEntry("detail", "The agent works on PR feedback")
                .containsEntry("action", RunAction.PR_FEEDBACK);
        var task = taskPage();
        assertThat(task.reasonNote()).isEqualTo("The agent works on PR feedback.");
        assertThat(task.run()).isEqualTo(new TaskDetail.RunLink("Open run",
                "/executions/" + EXECUTION_ID + "#pr-feedback", false));
        assertThat(task.turn()).isEqualTo(Turn.WORKING);
    }

    @Test
    void aQueuedTaskWaitsInTheQueue() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-912", "Open",
                TaskView.TaskState.WORKING, false, false, attempt("RESERVED", 1, emptyUsage())));

        var task = taskPage();

        assertThat(task.turn()).isEqualTo(Turn.QUEUED);
        assertThat(task.turn().label()).isEqualTo("Waiting in the queue");
    }

    @Test
    void aRunningTaskIsFactorysTurn() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-931", "Open",
                TaskView.TaskState.WORKING, false, false, attempt("RUNNING", 1, emptyUsage())));

        var task = taskPage();

        assertThat(task.turn()).isEqualTo(Turn.WORKING);
        assertThat(task.turn().label()).isEqualTo("Factory is working");
    }

    @Test
    void aPullRequestWithoutFeedbackToActOnNeedsNothingFromYou() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-921", "Open",
                TaskView.TaskState.PR_READY, false, false, prAttempt(null)));

        var task = taskPage();

        assertThat(task.turn()).isEqualTo(Turn.NONE);
        assertThat(task.turn().label()).isEqualTo("Nothing needed from you");
    }

    @Test
    void aFailedTaskThatCanRetryIsYourTurn() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-911", "Open",
                TaskView.TaskState.STOPPED, false, true, attempt("STOPPED", "MANUAL")));

        assertThat(taskPage().turn()).isEqualTo(Turn.YOURS);
    }

    @Test
    void aTaskWhoseRunNeedsInspectionIsYourTurn() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-921", "Open",
                TaskView.TaskState.PROCESSING_PAUSED, false, false, attempt("NEEDS_INSPECTION", "MANUAL")));

        var task = taskPage();

        assertThat(task.turn()).isEqualTo(Turn.YOURS);
        assertThat(task.markStoppedAttemptId()).isEqualTo(ATTEMPT_ID);
    }

    @Test
    void aQuestionThatWaitsForItsAnswerIsYourTurn() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(askedView());

        assertThat(taskPage().turn()).isEqualTo(Turn.YOURS);
    }

    private void givenStanding(TaskView view, FeedbackStanding standing) {
        when(feedback.standing(view)).thenReturn(standing);
        when(taskQuery.detail(TASK_ID)).thenReturn(view);
    }

    private Map<String, Object> myTasksModel(TaskView... triggered) throws Exception {
        when(taskQuery.myTasks(any(Pageable.class))).thenReturn(myTasks(List.of(), triggered));
        return mvc.perform(get(FLOW_PAGE)).andExpect(status().isOk()).andReturn().getModelAndView().getModel();
    }

    private List<Map<String, Object>> attemptRowsOfTheTaskPage() throws Exception {
        return rows(mvc.perform(get("/tasks/{taskId}", TASK_ID)).andReturn().getModelAndView().getModel(),
                "attempts");
    }

    private TaskDetail taskPage() throws Exception {
        return (TaskDetail) mvc.perform(get("/tasks/{taskId}", TASK_ID)).andExpect(status().isOk()).andReturn()
                .getModelAndView().getModel().get("task");
    }

    @Test
    void tasks_redirectsToTheFlowPage() throws Exception {
        mvc.perform(get("/tasks"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE));
    }

    @Test
    void taskDetail_rendersSummaryWithAttemptsNewestFirst() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-911", "Open",
                TaskView.TaskState.STOPPED, false, true,
                attempt("STOPPED", "MANUAL", 1), attempt("STOPPED", "BY_KEY", 2)));

        MvcResult result = mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("task"))
                .andReturn();

        assertThat(detail(result))
                .containsEntry("chipLabel", "Failed")
                .containsEntry("retryAllowed", true)
                .containsEntry("run", new TaskDetail.RunLink("Open run", "/executions/" + EXECUTION_ID, false))
                .containsEntry("nextAttempt", 3)
                .containsEntry("spentNote", "cost unknown")
                .containsEntry("repository", "mod-bar → folio-org/mod-bar · master");
        assertThat(detail(result))
                .containsEntry("headline", "Attempt 2 failed")
                .containsEntry("reason", "Checks failed: 2 tests failed.")
                .containsEntry("reasonNote", "No pull request was created.");
        // Newest first: a new attempt is added at the top.
        assertThat(rows(result.getModelAndView().getModel(), "attempts"))
                .extracting(row -> row.get("ordinal") + " · " + row.get("kind"))
                .containsExactly("2 · first run", "1 · first run");
        assertThat(result.getModelAndView().getModel().get("question")).isNull();
        verifyNoInteractions(admissions, outcomes);
        verify(dispatcher, never()).dispatchNext();
        verify(dispatcher, never()).dispatchNextQuietly();
    }

    @Test
    void taskDetail_retryFormStartsFromTheDefaultsWithAnEmptyTextField() throws Exception {
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
        assertThat(retryForm.selected().id()).isEqualTo("codemie-sonnet-5.5");
        assertThat(retryForm.effort()).isEqualTo("high");
        assertThat(model).doesNotContainKeys("retryInstructions", "typedInstructions");
        // The row shows the settings, the time and the quote, which alone may be cut.
        assertThat(rows(model, "attempts")).extracting(row -> row.get("settings"),
                        row -> ((AttemptClock) row.get("clock")).time(), row -> row.get("quote"))
                .containsExactly(tuple("glm-5.3 · low", "10 min", "\"Keep the public API.\""),
                        tuple(null, "10 min", null));
    }

    @Test
    void taskDetail_namesEachAttemptByHowItStarted() throws Exception {
        var instructed = "{\"profileId\":\"glm-5.3\",\"effort\":\"max\",\"instructions\":\"Keep the API.\"}";
        var answered = "{\"profileId\":\"glm-5.3\",\"effort\":\"max\","
                + "\"instructions\":\"My answer to your question: The internal API\"}";
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-911", "Open",
                TaskView.TaskState.STOPPED, false, true, attempt("STOPPED", "BY_KEY", 1), retryAttempt(2, null),
                retryAttempt(3, instructed), retryAttempt(4, answered)));

        var model = mvc.perform(get("/tasks/{taskId}", TASK_ID)).andReturn().getModelAndView().getModel();

        assertThat(rows(model, "attempts")).extracting(row -> row.get("kind")).containsExactly(
                "answer to the agent's question", "retry with your instructions", "retry", "first run");
    }

    @Test
    void taskDetail_unknownTaskRendersNotFoundPage() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenThrow(new NoSuchElementException("No task " + TASK_ID));

        mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void poll_changesWhenTheRunningTaskShowsAnotherCostAndStaysWhenItShowsTheSame() throws Exception {
        runningExecutionOf(TaskAttempt.State.RUNNING);
        TaskView before = taskView(TASK_ID, "MODBAR-931", "Open", TaskView.TaskState.WORKING, false, false,
                attempt("RUNNING", 1, emptyUsage()));
        TaskView later = taskView(TASK_ID, "MODBAR-931", "Open", TaskView.TaskState.WORKING, false, false,
                attempt("RUNNING", 1, incompleteUsage()));
        when(taskQuery.detail(TASK_ID)).thenReturn(before, before, later);

        String first = mvc.perform(get("/tasks/poll")).andReturn().getResponse().getContentAsString();
        String same = mvc.perform(get("/tasks/poll")).andReturn().getResponse().getContentAsString();
        String grown = mvc.perform(get("/tasks/poll")).andReturn().getResponse().getContentAsString();

        assertThat(same).isEqualTo(first);
        assertThat(grown).isNotEqualTo(first);
        // The poll only reads: it never admits, settles or dispatches a task.
        verifyNoInteractions(admissions, outcomes);
        verify(dispatcher, never()).dispatchNext();
        verify(dispatcher, never()).dispatchNextQuietly();
    }

    @Test
    void poll_changesWhileAPrFeedbackRoundAddsToTheCostOfADeliveredTask() throws Exception {
        // A round runs on the delivered attempt's run; the task holds no queue slot meanwhile.
        runningExecutionOf(TaskAttempt.State.PR_READY);
        TaskView before = withUsage(taskView(TASK_ID, "MODBAR-921", "Open", TaskView.TaskState.PR_READY,
                false, false, prAttempt(null)), emptyUsage());
        TaskView later = withUsage(before, incompleteUsage());
        when(taskQuery.detail(TASK_ID)).thenReturn(before, later);

        String first = mvc.perform(get("/tasks/poll")).andReturn().getResponse().getContentAsString();
        String grown = mvc.perform(get("/tasks/poll")).andReturn().getResponse().getContentAsString();

        assertThat(grown).isNotEqualTo(first);
    }

    private void runningExecutionOf(TaskAttempt.State state) {
        var execution = new PipelineExecution("dev-factory", "0.8.0", "{}");
        ReflectionTestUtils.setField(execution, "id", EXECUTION_ID);
        when(executions.findByStatus(ExecutionStatus.RUNNING)).thenReturn(List.of(execution));
        TaskAttempt attempt = new TaskAttempt(TASK_ID, 1, TaskAttempt.Kind.INITIAL, "run#1", state);
        when(attempts.findByExecutionId(EXECUTION_ID)).thenReturn(Optional.of(attempt));
    }

    @Test
    void taskPoll_startsFromThePageAndChangesWhenAnAttemptStarts() throws Exception {
        TaskView stopped = taskView(TASK_ID, "MODBAR-911", "Open", TaskView.TaskState.STOPPED, false, true,
                attempt("STOPPED", "MANUAL", 1));
        TaskView started = taskView(TASK_ID, "MODBAR-911", "Open", TaskView.TaskState.WORKING, false, false,
                attempt("STOPPED", "MANUAL", 1), attempt("RUNNING", "MANUAL", 2));
        when(taskQuery.detail(TASK_ID)).thenReturn(stopped, stopped, started);

        var page = mvc.perform(get("/tasks/{taskId}", TASK_ID)).andReturn().getModelAndView().getModel();
        String baseline = (String) page.get("pollBaseline");
        String unchanged = mvc.perform(get("/tasks/{taskId}/poll", TASK_ID)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String changed = mvc.perform(get("/tasks/{taskId}/poll", TASK_ID)).andReturn().getResponse()
                .getContentAsString();

        assertThat(page).containsEntry("pollUrl", "/tasks/" + TASK_ID + "/poll");
        assertThat(baseline).matches("[0-9a-f]+");
        assertThat(unchanged).isEqualTo("{\"revision\":\"" + baseline + "\"}");
        assertThat(changed).doesNotContain(baseline);
        verifyNoInteractions(admissions, outcomes);
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
                .andExpect(flash().attributeCount(0));

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

        MvcResult result = mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "1")
                        .param("instructions", "Keep the public API."))
                .andExpect(status().is3xxRedirection())
                // The page shows the typed text again; it never goes into the URL.
                .andExpect(flash().attribute("typedInstructions", "Keep the public API."))
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/tasks/" + TASK_ID + "?error=")
                .contains("cannot+push")
                .doesNotContain("public");
    }

    @Test
    void retry_refusedWhileTheTaskIsQueuedIsNotShownAsQueued() throws Exception {
        when(admissions.retry(any())).thenReturn(new TaskAdmissionService.RetryResult(ATTEMPT_ID, null, TaskAttempt.State.RESERVED, "task already has a live attempt", true));

        mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "1").param("returnTo", "flow"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE + "?error=task+already+has+a+live+attempt"))
                .andExpect(flash().attributeCount(0));
    }

    @Test
    void taskDetail_showsTheAgentsQuestionAndItsOptions() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(askedView());
        when(artifactStore.getLatest(EXECUTION_ID, DevelopWorker.OUTCOME)).thenReturn(Optional.of(askedOutcome()));

        var model = mvc.perform(get("/tasks/{taskId}", TASK_ID)).andReturn().getModelAndView().getModel();

        assertThat(fields(model.get("task")))
                .containsEntry("chipLabel", "Needs your answer")
                .containsEntry("chipClass", "chip chip-warning")
                .containsEntry("headline", "The agent stopped with a question")
                .containsEntry("nextAttempt", 2);
        assertThat(model.get("question")).isEqualTo(new AgentQuestion("Which API should change?",
                "Both APIs exist", List.of("The public API", "The internal API"), "I read the code."));
        assertThat(rows(model, "attempts").getFirst()).containsEntry("chipLabel", "Needs your answer")
                .containsEntry("chipClass", "chip chip-warning");
    }

    @Test
    void retry_anAnswerStartsTheNextAttemptWithTheAnswerTheQuestionAndTheAgentsText() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(askedView());
        when(artifactStore.getLatest(EXECUTION_ID, DevelopWorker.OUTCOME)).thenReturn(Optional.of(askedOutcome()));
        when(admissions.retry(any())).thenReturn(new TaskAdmissionService.RetryResult(ATTEMPT_ID, null,
                TaskAttempt.State.RESERVED, "the retry joined the queue", false));

        mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "3")
                        .param("modelId", "glm-5.3").param("effort", "max")
                        .param("answer", "1").param("note", "Keep the change small."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks/" + TASK_ID));

        verify(admissions).retry(retryCaptor.capture());
        assertThat(TaskRunSettings.settings(retryCaptor.getValue().runSettings()).instructions()).isEqualTo("""
                My answer to your question: The internal API
                Keep the change small.

                Your question was: Which API should change?

                What you wrote before the question in the last attempt:
                I read the code.""");
    }

    @Test
    void retry_myOwnAnswerNeedsItsText() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(askedView());
        when(artifactStore.getLatest(EXECUTION_ID, DevelopWorker.OUTCOME)).thenReturn(Optional.of(askedOutcome()));

        MvcResult result = mvc.perform(post("/tasks/{taskId}/retry", TASK_ID).param("expectedVersion", "3")
                        .param("answer", "own").param("note", "  "))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(java.net.URLDecoder.decode(result.getResponse().getRedirectedUrl(),
                java.nio.charset.StandardCharsets.UTF_8))
                .contains("Write your answer in the text field, or choose one of the agent's options.");
        verify(admissions, never()).retry(any());
    }

    private static TaskView askedView() {
        var stopped = taskView(TASK_ID, "MODBAR-941", "Open", TaskView.TaskState.STOPPED, false, true);
        var reason = "clarify-implementation: " + TaskStops.DECISION_REASON + "Which API should change?";
        var attempt = new TaskView.AttemptHistoryEntry(UUID.randomUUID(), 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.AdmittedVia.MANUAL, TaskAttempt.State.STOPPED, "clarify-implementation", reason, null,
                null, EXECUTION_ID, Instant.parse("2026-10-23T07:59:00Z"), Instant.parse("2026-10-23T08:00:00Z"),
                true, 600, emptyUsage(), null);
        return new TaskView(stopped.taskId(), stopped.displayKey(), stopped.jiraUrl(), stopped.title(),
                stopped.projectKey(), stopped.jiraStatus(), stopped.state(), null, reason, "clarify-implementation",
                null, stopped.destinationRepository(), null, null, true, false, stopped.version(),
                stopped.inputFingerprint(), stopped.destinationFingerprint(), List.of(attempt), stopped.usage(),
                stopped.allowedActions());
    }

    private static Artifact askedOutcome() {
        var outcome = CodingOutcome.needsDecision(new CodingOutcome.Decision(
                CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Which API should change?",
                List.of("The public API", "The internal API"), "Both APIs exist"), "I read the code.", Map.of());
        var json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(outcome);
        return new Artifact(EXECUTION_ID, DevelopWorker.OUTCOME, 1, "application/json", json, "sha", "dev-develop");
    }

    // ----- mark as stopped -----

    @Test
    void markStopped_releasesTheQueueAndStartsTheNextTask() throws Exception {
        when(outcomes.getIfAvailable()).thenReturn(outcomeService);
        when(outcomeService.markStopped(TASK_ID, ATTEMPT_ID)).thenReturn(null);

        mvc.perform(post("/tasks/{taskId}/attempts/{attemptId}/mark-stopped", TASK_ID, ATTEMPT_ID)
                        .param("returnTo", "flow"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "Closed as failed; the queue continues."));

        verify(dispatcher).dispatchNextQuietly();
    }

    @Test
    void markStopped_failureSaysFactoryCouldNotCloseTheAttempt() throws Exception {
        when(outcomes.getIfAvailable()).thenReturn(outcomeService);
        when(outcomeService.markStopped(TASK_ID, ATTEMPT_ID)).thenThrow(new IllegalStateException("connection lost"));

        mvc.perform(post("/tasks/{taskId}/attempts/{attemptId}/mark-stopped", TASK_ID, ATTEMPT_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks/" + TASK_ID + "?error=Factory+could+not+close+the+attempt+as+failed%3B"
                        + "+try+again+in+a+moment%2C+and+see+the+Factory+log+if+it+fails+again."));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void markStopped_refusalDoesNotDispatch() throws Exception {
        when(outcomes.getIfAvailable()).thenReturn(outcomeService);
        when(outcomeService.markStopped(TASK_ID, ATTEMPT_ID))
                .thenReturn("Only a task that needs inspection can be closed as failed");

        mvc.perform(post("/tasks/{taskId}/attempts/{attemptId}/mark-stopped", TASK_ID, ATTEMPT_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks/" + TASK_ID
                        + "?error=Only+a+task+that+needs+inspection+can+be+closed+as+failed"));

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
        return fields(result.getModelAndView().getModel().get("task"));
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
                var value = component.getAccessor().invoke(row);
                // The chip and headline of a row or the task card read like fields of their own.
                if (value instanceof AttemptStatus status) {
                    fields.putAll(fields(status));
                }
                fields.put(component.getName(), value);
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

    private static TaskView unsupported(UUID taskId, String key, String reason) {
        var view = taskView(taskId, key, "Open", TaskView.TaskState.UNSUPPORTED, false, false);
        return new TaskView(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(), view.projectKey(),
                view.jiraStatus(), view.state(), null, reason, null, null, null, null, null, true, false,
                view.version(), view.inputFingerprint(), view.destinationFingerprint(), List.of(), view.usage(),
                view.allowedActions());
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

    private static TaskView.AttemptHistoryEntry retryAttempt(int ordinal, String runSettings) {
        var attempt = attempt("STOPPED", "MANUAL", ordinal, runSettings);
        return new TaskView.AttemptHistoryEntry(attempt.attemptId(), ordinal, TaskAttempt.Kind.RETRY,
                attempt.admittedVia(), attempt.state(), attempt.stage(), attempt.reason(), null, null,
                attempt.executionId(), attempt.createdAt(), attempt.startedAt(), true, 600, attempt.usage(),
                runSettings);
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

    /** Tokens reported for a model without a price. */
    private static TaskUsage unpricedUsage() {
        return new TaskUsage(1_000, Map.of(AttemptCharge.Category.INPUT, 1_000L), new TreeMap<>(), false, true,
                List.of());
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
