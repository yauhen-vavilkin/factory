package org.folio.factory.app.tasks;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.support.DevFlowHarness;
import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.app.support.SharedWireMock;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.execution.PersistedTaskAttemptPolicy;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskPublicationIntent;
import org.folio.factory.app.tasks.persistence.TaskPublicationIntentRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.app.tasks.presentation.TaskView;
import org.folio.factory.app.tasks.source.TaskDiscoveryService;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.folio.factory.app.support.DevFlowHarness.issue;
import static org.folio.factory.app.support.DevFlowHarness.stubSearch;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;

/**
 * The full deterministic M1 scenario in one Spring context against real
 * PostgreSQL, the real engine and discovery wiring, fixture Jira/GitHub
 * endpoints and a scripted coding runtime. A scan of two projects and a manual
 * run of three tasks seed the queue; then one task reaches a ready pull request
 * while a failed task stops with a readable reason and the queue continues, an
 * explicit retry reaches a new outcome, a task added by its key joins the same
 * queue without a scan, a lost publication response holds the attempt for
 * inspection until the developer marks it as stopped, and an incomplete usage
 * component keeps the task projection incomplete. Jira stays read-only, no task attempt opens an intermediate
 * review, coding starts once per attempt and no pull request is duplicated.
 */
@FactoryIntegrationTest
@Import({SharedWireMock.Jira.class, SharedWireMock.GitHub.class})
@ActiveProfiles({"engine", "task-execution"})
@TestPropertySource(properties = {
        "factory.limits.dedup.window=PT0S",
        "factory.tasks.source.assignee=Jane Developer",
        "factory.connectors.github.token=fixture-github-token",
        "factory.dev-factory.runtime.plans.java21-unit[0]=mvn",
        "factory.dev-factory.runtime.plans.java21-unit[1]=test",
        "factory.dev-factory.repositories.mod-bar.source-repo=folio-org/mod-bar",
        "factory.dev-factory.repositories.mod-bar.base-branch=master",
        "factory.dev-factory.repositories.mod-bar.build-image=maven:3.9-eclipse-temurin-21",
        "factory.dev-factory.repositories.mod-bar.verification-plan=java21-unit",
        "factory.dev-factory.repositories.mod-bar.jira-projects[0]=MODBAR",
        "factory.dev-factory.repositories.mod-bar.delivery.repository=folio-org/mod-bar",
        "factory.dev-factory.repositories.mod-foo.source-repo=folio-org/mod-foo",
        "factory.dev-factory.repositories.mod-foo.base-branch=master",
        "factory.dev-factory.repositories.mod-foo.build-image=maven:3.9-eclipse-temurin-21",
        "factory.dev-factory.repositories.mod-foo.verification-plan=java21-unit",
        "factory.dev-factory.repositories.mod-foo.jira-projects[0]=MODFOO",
        "factory.dev-factory.repositories.mod-foo.delivery.repository=folio-org/mod-foo"})
// Own context: running engine and task queue with its own catalog, Jira and GitHub; closing it stops its schedulers and frees its connections.
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaskEndToEndTest {

    private static final String BAR = "folio-org/mod-bar";
    private static final String FOO = "folio-org/mod-foo";
    private static final String BAR_PR = "https://github.com/" + BAR + "/pull/11";
    private static final String FOO_PR = "https://github.com/" + FOO + "/pull/21";
    private static final Map<String, Object> COMPLETE_USAGE = Map.of(
            "inputTokens", 100_000L, "outputTokens", 20_000L, "cacheReadTokens", 0L, "cacheWriteTokens", 0L);
    private static final Map<String, Object> REPEAT_USAGE = Map.of(
            "inputTokens", 150_000L, "outputTokens", 30_000L, "cacheReadTokens", 0L, "cacheWriteTokens", 0L);
    private static final Map<String, Object> PARTIAL_USAGE = Map.of(
            "inputTokens", 50_000L, "outputTokens", 5_000L);

    static final WireMockServer jira = SharedWireMock.JIRA;
    static final WireMockServer GITHUB = SharedWireMock.GITHUB;

    static final Path gitRoot = DevFlowHarness.createGitRoot("task-e2e-git");
    static final Path BAR_WORK = DevFlowHarness.createRepositoryWithFiles(gitRoot, "folio-org/mod-bar", "master",
            Map.of("README.md", "# fixture folio-org/mod-bar\n"));
    static final Path FOO_WORK = DevFlowHarness.createRepositoryWithFiles(gitRoot, "folio-org/mod-foo", "master",
            Map.of("README.md", "# fixture folio-org/mod-foo\n"));
    static final Path CODING_CHANGE = DevFlowHarness.workspace("task-e2e-coding-",
            Map.of("README.md", "# fixture changed by the stubbed coding run\n"));
    static final Path VERIFICATION_REPORTS = DevFlowHarness.passingReports();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("factory.dev-factory.git-base-url", () -> gitRoot.toUri().toString());
    }

    // The ordered scenario builds on the stubs and requests of earlier tests, so the servers reset
    // and the queue is seeded once per class instead of before each test.
    @BeforeAll
    static void seedTheQueueFromAScanAndAManualRun(@Autowired TaskDiscoveryService discovery,
                                                   @Autowired TaskAdmissionService admissions,
                                                   @Autowired TaskRepository tasks,
                                                   @Autowired TaskJiraInstance jiraInstance,
                                                   @Autowired DeveloperPreflight preflight) {
        resetWireMockAndStubPullRequestCreation();
        stubSearch(jira,
                issue("8101", "MODBAR-101", "MODBAR", "Add validation to mod-bar", "2026-10-01T09:00:00.000+0000", ""),
                issue("8103", "MODFOO-103", "MODFOO", "Refactor the mod-foo exporter", "2026-10-01T09:15:00.000+0000", ""),
                issue("8102", "MODBAR-102", "MODBAR", "Clarify the limit handling", "2026-10-01T09:30:00.000+0000", ""),
                issue("8104", "OTHERP-104", "OTHERP", "Task in an unmapped project", "2026-10-01T09:40:00.000+0000", ""),
                issue("8105", "MODBAR-105", "MODBAR", "Blocked by preparation", "2026-10-01T09:45:00.000+0000",
                        linked("MODBAR-999")));
        assertThat(discovery.sync().complete()).isTrue();
        // A refused preflight holds admission's best-effort dispatch, so the attempts stay queued.
        doThrow(new IllegalArgumentException("coding runtime not ready"))
                .when(preflight).requireReady(eq("dev-factory"), any());
        var selections = List.of("8101", "8103", "8102").stream()
                .map(issueId -> TaskFixture.selection(
                        tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issueId).orElseThrow()))
                .toList();
        var result = admissions.runSelected(new TaskCommands.RunSelected("e2e-run-1", selections));
        assertThat(result.admittedTaskIds()).as(String.valueOf(result)).hasSize(3);
        reset(preflight);
    }

    private static void resetWireMockAndStubPullRequestCreation() {
        jira.resetAll();
        GITHUB.resetAll();
        DevFlowHarness.stubPullRequestCreation(GITHUB, BAR, BAR_PR);
        DevFlowHarness.stubPullRequestCreation(GITHUB, FOO, FOO_PR);
    }

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoBean
    CodingRuntime coding;

    @MockitoBean
    DockerWorkloads docker;

    @MockitoBean
    CandidateDelivery delivery;

    @MockitoBean
    ModelCatalog models;

    @Autowired
    TaskDiscoveryService discovery;

    @Autowired
    TaskAdmissionService admissions;

    @Autowired
    PersistedTaskAttemptPolicy attemptPolicy;


    @Autowired
    TaskDispatcher dispatcher;

    @Autowired
    TaskOutcomeService outcomes;

    @Autowired
    TaskJiraInstance jiraInstance;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskAttemptRepository attempts;

    @Autowired
    TaskInstallationRepository installations;

    @Autowired
    TaskPublicationIntentRepository intents;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    HitlReviewRepository reviews;

    @Autowired
    FlowRegistry flowRegistry;

    @Autowired
    TaskQuery queries;

    private final JsonMapper json = JsonMapper.builder().build();
    private static final Map<String, Integer> codingCalls = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, CodingRequest> codingRequests = new java.util.concurrent.ConcurrentHashMap<>();

    private DevFlowHarness harness;

    @BeforeEach
    void stubFlowCollaborators() {
        harness = new DevFlowHarness(stateManager, artifactStore, reviews);
        DevFlowHarness.stubCodingRuntime(coding, models, request -> {
            codingCalls.merge(request.issueKey(), 1, Integer::sum);
            codingRequests.put(request.issueKey(), request);
            return outcomeFor(request.issueKey());
        });
        DevFlowHarness.stubWorkloads(docker, CODING_CHANGE, VERIFICATION_REPORTS);
        DevFlowHarness.stubDelivery(delivery, receipt -> { });
    }

    @Test
    @Order(1)
    void firstTaskRunsTheDeveloperFlowToAReadyPullRequest() {
        stubPullRequestReadOnDelivery(BAR, 11);
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(stateManager.get(executionId).getTriggerPayload()).contains("MODBAR-101");
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        UUID attemptId = attempts.findByExecutionId(executionId).orElseThrow().getId();

        awaitDelivered(executionId);
        TaskPublicationIntent intent = intents.findByExecutionId(executionId).orElseThrow();
        assertThat(intent.getRepository()).isEqualTo(BAR);
        assertThat(intent.getBaseBranch()).isEqualTo("master");
        Candidate candidate = json.readValue(harness.latest(executionId, DevelopWorker.CANDIDATE), Candidate.class);
        assertThat(intent.getBaseSha()).isEqualTo(candidate.baseSha());
        assertThat(intent.getTreeSha()).isEqualTo(candidate.treeSha());
        assertThat(intent.getPatchSha256()).isEqualTo(candidate.patchSha256());
        assertThat(intent.getHeadBranch()).startsWith("factory/modbar-101-");

        TaskOutcomeService.AttemptOutcome ready = outcomes.refresh(attemptId);

        assertThat(ready.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.PR_READY);
        assertThat(attempts.findById(attemptId).orElseThrow().getState()).isEqualTo(TaskAttempt.State.PR_READY);
        assertThat(attempts.findById(attemptId).orElseThrow().getPublicationIdentity()).isEqualTo(BAR_PR);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
        assertThat(json.readTree(harness.latest(executionId, DeliveryWorker.DELIVERY)).path("pullRequestUrl").asString())
                .isEqualTo(BAR_PR);
        // "Implemented the change" does not use the report template, so the pull request opens as a draft.
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + BAR + "/pulls"))).getFirst()
                .getBodyAsString()).contains("\"draft\":true", "## Agent report");
        assertThat(json.readTree(harness.latest(executionId, DeliveryWorker.DELIVERY)).path("pullRequestDraft").asBoolean())
                .isTrue();
        assertThat(attempts.findById(attemptId).orElseThrow().getDraftReasons()).contains("four expected sections");
        assertThat(json.readTree(harness.latest(executionId, VerifyWorker.RESULT)).path("state").asString())
                .isEqualTo("DELIVERED");
        assertThat(jira.findAll(getRequestedFor(urlEqualTo("/rest/api/2/issue/MODBAR-101"))))
                .as("the approved snapshot is consumed without a second Jira read")
                .isEmpty();
        assertThat(codingCalls).containsEntry("MODBAR-101", 1);
        assertThat(attempts.findById(attemptId).orElseThrow().getPaidStartAt()).isNotNull();
        await("the follow-up step opens the pr-feedback gate").atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(reviewGateIds(executionId)).containsOnly("pr-feedback"));
        assertJiraStayedReadOnly();
    }

    @Test
    @Order(2)
    void failedTaskStopsWithAReadableReasonAndTheQueueContinues() {
        UUID failedExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(stateManager.get(failedExecutionId).getTriggerPayload()).contains("MODFOO-103");
        awaitTerminal(failedExecutionId);
        UUID failedAttemptId = attempts.findByExecutionId(failedExecutionId).orElseThrow().getId();

        TaskOutcomeService.AttemptOutcome stopped = outcomes.refresh(failedAttemptId);

        assertThat(stopped.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(stopped.reason()).contains("DEVELOPMENT_FAILED", "the coding model rejected the task");
        assertThat(attempts.findById(failedAttemptId).orElseThrow().getResultReason())
                .contains("DEVELOPMENT_FAILED", "the coding model rejected the task");
        assertThat(json.readTree(harness.latest(failedExecutionId, VerifyWorker.RESULT)).path("state").asString())
                .isEqualTo("DEVELOPMENT_FAILED");
        assertThat(json.readTree(harness.latest(failedExecutionId, DeliveryWorker.DELIVERY)).path("state").asString())
                .isEqualTo("NOT_RUN");
        DevFlowHarness.declaredOutputs(flowRegistry).forEach(name -> assertThat(artifactStore.getLatest(failedExecutionId, name))
                .as(name).isPresent());
        assertThat(reviewGateIds(failedExecutionId)).isEmpty();
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + FOO + "/pulls"))))
                .as("a stopped result creates no pull request")
                .isEmpty();

        UUID nextExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(stateManager.get(nextExecutionId).getTriggerPayload()).contains("MODBAR-102");
        assertThat(attempts.findByExecutionId(nextExecutionId).orElseThrow().getTaskId())
                .isEqualTo(taskId("8102"));
        assertThat(executions.count()).isEqualTo(3);
        assertJiraStayedReadOnly();
    }

    @Test
    @Order(3)
    void explicitRetryRunsAFreshAttemptToANewOutcomeAndKeepsHistoryAndCosts() {
        stubPullRequestReadOnDelivery(FOO, 21);
        UUID heldExecutionId = dispatcher.dispatchNext().orElseThrow();
        UUID heldAttemptId = attempts.findByExecutionId(heldExecutionId).orElseThrow().getId();
        Task failedTask = tasks.findById(taskId("8103")).orElseThrow();

        String runSettings = "{\"profileId\":\"codemie-haiku-5.5\",\"profileLabel\":\"Haiku 5.5 (CodeMie)\","
                + "\"model\":\"claude-haiku-5-5\",\"endpointOrigin\":\"http://host.docker.internal:4001\","
                + "\"effort\":\"low\","
                + "\"maxTurns\":33,\"instructions\":\"Use the wiki REST URL.\"}";
        var firstClick = admissions.retry(new TaskCommands.Retry("e2e-retry-103", failedTask.getId(),
                failedTask.getVersion(), TaskAdmissionChecks.inputFingerprint(failedTask),
                TaskAdmissionChecks.destinationFingerprint(failedTask), runSettings));
        var duplicateClick = admissions.retry(new TaskCommands.Retry("e2e-retry-103", failedTask.getId(),
                failedTask.getVersion(), TaskAdmissionChecks.inputFingerprint(failedTask),
                TaskAdmissionChecks.destinationFingerprint(failedTask), null));

        assertThat(firstClick.attemptId()).isNotEqualTo(heldAttemptId);
        assertThat(firstClick.state()).isEqualTo(TaskAttempt.State.RESERVED);
        assertThat(firstClick.executionId()).isNull();
        assertThat(duplicateClick.attemptId()).isEqualTo(firstClick.attemptId());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("8103"))).hasSize(2);
        assertThat(attempts.findById(firstClick.attemptId()).orElseThrow().getKind())
                .isEqualTo(TaskAttempt.Kind.RETRY);
        assertThat(executions.count()).isEqualTo(3);

        awaitTerminal(heldExecutionId);
        TaskOutcomeService.AttemptOutcome heldOutcome = outcomes.refresh(heldAttemptId);
        assertThat(heldOutcome.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(heldOutcome.reason()).contains("Which acceptance criterion defines the limit?");
        assertThat(reviewGateIds(heldExecutionId)).isEmpty();
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();

        UUID retryExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(attempts.findByExecutionId(retryExecutionId).orElseThrow().getId())
                .isEqualTo(firstClick.attemptId());
        awaitDelivered(retryExecutionId);
        TaskPublicationIntent intent = intents.findByExecutionId(retryExecutionId).orElseThrow();
        assertThat(intent.getRepository()).isEqualTo(FOO);

        TaskOutcomeService.AttemptOutcome retryOutcome = outcomes.refresh(firstClick.attemptId());

        assertThat(retryOutcome.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.PR_READY);
        // The settings chosen at Retry reach the coding request; the first attempt had none.
        assertThat(codingRequests.get("MODFOO-103").runSettings())
                .isEqualTo(new CodingRequest.RunSettings("codemie-haiku-5.5", "low", "Use the wiki REST URL."));
        assertThat(attempts.findById(firstClick.attemptId()).orElseThrow().getRunSettings())
                .contains("codemie-haiku-5.5");
        assertThat(attempts.findById(firstClick.attemptId()).orElseThrow().getPublicationIdentity()).isEqualTo(FOO_PR);
        List<TaskAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(taskId("8103"));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getState()).isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(history.get(0).getResultReason()).contains("the coding model rejected the task");
        assertThat(history.get(1).getState()).isEqualTo(TaskAttempt.State.PR_READY);
        assertThat(history.get(1).getPublicationIdentity()).isEqualTo(FOO_PR);
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + FOO + "/pulls"))))
                .hasSize(1);
        assertJiraStayedReadOnly();
    }

    @Test
    @Order(4)
    void taskAddedByKeyJoinsMyTasksRunsFromTheListAndCannotBeAddedTwice() {
        // Keep the run queued (a refused preflight holds dispatch) so the next
        // scenario starts it after a scan that no longer finds the task.
        doThrow(new IllegalArgumentException("coding runtime not ready"))
                .when(preflight).requireReady(eq("dev-factory"), any());
        stubSearch(jira, issue("8106", "MODBAR-106", "MODBAR", "Expose the new endpoint", "2026-10-01T10:00:00.000+0000", ""));

        var added = admissions.addByKey(jira.baseUrl() + "/browse/MODBAR-106");

        assertThat(added.problem()).isNull();
        assertThat(added.issueKey()).isEqualTo("MODBAR-106");
        Task task = tasks.findById(added.taskId()).orElseThrow();
        assertThat(task.isInScope()).as("a key puts the task into My tasks").isTrue();
        assertThat(task.isAddedByKey()).isTrue();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId())).as("adding does not run it").isEmpty();
        assertThat(admissions.addByKey("modbar-106").problem()).isEqualTo("MODBAR-106 is already in your list.");

        var run = admissions.runSelected(new TaskCommands.RunSelected("e2e-key-106", List.of(selectionFor("8106"))));

        assertThat(run.admittedTaskIds()).containsExactly(task.getId());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId()))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getState()).isEqualTo(TaskAttempt.State.RESERVED);
                    assertThat(attempt.getKind()).isEqualTo(TaskAttempt.Kind.INITIAL);
                    assertThat(attempt.getAdmittedVia()).isEqualTo(TaskAttempt.AdmittedVia.BY_KEY);
                });
        assertThat(admissions.addByKey("MODBAR-106").problem())
                .isEqualTo("MODBAR-106 has already run. Retry it from its row under Triggered.");
        assertThat(admissions.addByKey("not a key").problem()).startsWith("Enter a Jira key");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId())).hasSize(1);

        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .willReturn(aResponse().withStatus(503).withBody("<html>" + "maintenance ".repeat(500) + "</html>")));
        assertThat(admissions.addByKey("MODBAR-107").problem())
                .isEqualTo("Jira could not be read: HTTP 503");
        stubSearch(jira, issue("8106", "MODBAR-106", "MODBAR", "Expose the new endpoint", "2026-10-01T10:00:00.000+0000", ""));
        assertJiraStayedReadOnly();
    }

    @Test
    @Order(5)
    void lostPublicationResponseHoldsTheAttemptForInspectionWithoutARetry() {
        UUID firstExecutionId = dispatcher.dispatchNext().orElseThrow();
        UUID firstAttemptId = attempts.findByExecutionId(firstExecutionId).orElseThrow().getId();
        assertThat(attempts.findById(firstAttemptId).orElseThrow().getTaskId()).isEqualTo(taskId("8106"));
        awaitTerminal(firstExecutionId);
        assertThat(outcomes.refresh(firstAttemptId).state())
                .isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        GITHUB.stubFor(post(urlPathEqualTo("/repos/" + BAR + "/pulls")).willReturn(okJson("{}")));
        Task uncertainTask = tasks.findById(taskId("8106")).orElseThrow();

        var retry = admissions.retry(new TaskCommands.Retry("e2e-retry-106", uncertainTask.getId(),
                uncertainTask.getVersion(), TaskAdmissionChecks.inputFingerprint(uncertainTask),
                TaskAdmissionChecks.destinationFingerprint(uncertainTask), null));
        assertThat(retry.executionId()).isNotNull();
        UUID executionId = retry.executionId();

        awaitTerminal(executionId);
        assertThat(json.readTree(harness.latest(executionId, DeliveryWorker.DELIVERY)).path("state").asString())
                .isEqualTo("DELIVERED");
        assertThat(json.readTree(harness.latest(executionId, DeliveryWorker.DELIVERY)).path("pullRequestUrl").asString())
                .isEmpty();

        TaskOutcomeService.AttemptOutcome uncertain = outcomes.refresh(retry.attemptId());

        assertThat(uncertain.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(uncertain.released()).isFalse();
        assertThat(attempts.findById(retry.attemptId()).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(retry.attemptId());
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        var refused = admissions.retry(new TaskCommands.Retry("e2e-retry-106-late", uncertainTask.getId(),
                uncertainTask.getVersion(), TaskAdmissionChecks.inputFingerprint(uncertainTask),
                TaskAdmissionChecks.destinationFingerprint(uncertainTask), null));
        assertThat(refused.attemptId()).isEqualTo(retry.attemptId());
        assertThat(refused.state()).isEqualTo(TaskAttempt.State.NEEDS_INSPECTION);
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + BAR + "/pulls"))))
                .as("one create request per delivered attempt, never a duplicate pull request")
                .hasSize(2);
        assertJiraStayedReadOnly();
    }

    @Test
    @Order(6)
    void markAsStoppedReleasesTheQueueAndTheHeldRunCanNoLongerPublish() {
        TaskAttempt held = attempts.findByTaskIdOrderByOrdinalAsc(taskId("8106")).getLast();
        assertThat(held.getState()).isEqualTo(TaskAttempt.State.NEEDS_INSPECTION);
        assertThat(outcomes.markStopped(taskId("8101"), attempts.findByTaskIdOrderByOrdinalAsc(taskId("8101")).getFirst().getId()))
                .as("only an attempt that needs inspection can be stopped")
                .isNotNull();

        assertThat(outcomes.markStopped(held.getTaskId(), held.getId())).isNull();

        TaskAttempt stopped = attempts.findById(held.getId()).orElseThrow();
        assertThat(stopped.getState()).isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(stopped.getResultReason())
                .as("the held run lost its publication response, so the reason warns about a possible pull request")
                .startsWith(TaskOutcomeService.MARKED_STOPPED + "; " + TaskOutcomeService.PULL_REQUEST_MAY_EXIST);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
        assertThat(attemptPolicy.ownsActiveSlot(held.getExecutionId())).isFalse();
        assertThat(dispatcher.dispatchNext()).isEmpty();
        assertThat(outcomes.markStopped(held.getTaskId(), held.getId())).isNotNull();
    }

    @Test
    @Order(7)
    void taskProjectionPreservesHistoryAndTokenomicsIncludingIncompleteUsage() {
        TaskQuery.MyTasks myTasks = queries.myTasks(PageRequest.of(0, 10));

        assertThat(myTasks.available()).extracting(TaskView::displayKey)
                .containsExactlyInAnyOrder("OTHERP-104", "MODBAR-105");
        assertThat(myTasks.triggered().getContent()).extracting(TaskView::displayKey)
                .containsExactlyInAnyOrder("MODBAR-101", "MODFOO-103", "MODBAR-102", "MODBAR-106");
        assertThat(queries.detail(taskId("8101")).state()).isEqualTo(TaskView.TaskState.PR_READY);
        assertThat(queries.detail(taskId("8103")).state()).isEqualTo(TaskView.TaskState.PR_READY);
        assertThat(queries.detail(taskId("8102")).state()).isEqualTo(TaskView.TaskState.STOPPED);
        assertThat(queries.detail(taskId("8106")).state()).isEqualTo(TaskView.TaskState.STOPPED);
        assertThat(queries.detail(taskId("8105")).state()).isEqualTo(TaskView.TaskState.AWAITING_SELECTION);
        assertThat(queries.detail(taskId("8104")).state()).isEqualTo(TaskView.TaskState.UNSUPPORTED);
        assertThat(queries.detail(taskId("8105")).jiraStatus()).isEqualTo("To Do");

        TaskView completeTask = queries.detail(taskId("8103"));
        assertThat(completeTask.attempts()).hasSize(2);
        assertThat(completeTask.usage().complete()).isTrue();
        assertThat(completeTask.usage().tokens()).isEqualTo(300_000L);
        assertThat(completeTask.usage().estimatesByCurrency().get("USD")).isEqualByComparingTo("1.00");
        assertThat(completeTask.attempts().get(0).usage().estimatesByCurrency().get("USD"))
                .isEqualByComparingTo("0.40");
        assertThat(completeTask.attempts().get(1).usage().estimatesByCurrency().get("USD"))
                .isEqualByComparingTo("0.60");
        assertThat(completeTask.attempts().get(1).pullRequestUrl()).isEqualTo(FOO_PR);

        TaskView incompleteTask = queries.detail(taskId("8106"));
        assertThat(incompleteTask.usage().complete()).isFalse();
        assertThat(incompleteTask.usage().estimatesByCurrency().get("USD")).isEqualByComparingTo("0.55");

        TaskView unstartedTask = queries.detail(taskId("8105"));
        assertThat(unstartedTask.usage().estimatesByCurrency()).isEmpty();
        assertThat(unstartedTask.usage().complete()).isTrue();

        assertThat(attempts.count()).isEqualTo(6);
        assertThat(executions.count()).isEqualTo(6);
        assertThat(codingCalls).containsOnly(Map.entry("MODBAR-101", 1), Map.entry("MODFOO-103", 2),
                Map.entry("MODBAR-102", 1), Map.entry("MODBAR-106", 2));
        assertThat(attempts.findAll().stream().filter(attempt -> attempt.getPaidStartAt() != null).count())
                .isEqualTo(6);
        assertThat(reviews.findAll()).allSatisfy(review ->
                assertThat(review.getGateId()).isEqualTo("pr-feedback"));
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + BAR + "/pulls")))).hasSize(2);
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + FOO + "/pulls")))).hasSize(1);
        assertJiraStayedReadOnly();
    }

    private static void assertJiraStayedReadOnly() {
        assertThat(jira.getAllServeEvents()).allSatisfy(event ->
                assertThat(event.getRequest().getUrl()).isEqualTo("/rest/api/2/search/jql"));
    }

    private List<String> reviewGateIds(UUID executionId) {
        return reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .map(review -> review.getGateId())
                .toList();
    }

    private CodingOutcome outcomeFor(String issueKey) {
        return switch (issueKey) {
            case "MODBAR-101" -> CodingOutcome.completed("Implemented the change", COMPLETE_USAGE);
            case "MODFOO-103" -> codingCalls.get(issueKey) == 1
                    ? CodingOutcome.failed("MODEL_ERROR",
                            "the coding model rejected the task after repeated tool failures", COMPLETE_USAGE)
                    : CodingOutcome.completed("Implemented the change on the fresh attempt", REPEAT_USAGE);
            case "MODBAR-102" -> CodingOutcome.needsDecision(new CodingOutcome.Decision(
                    CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS,
                    "Which acceptance criterion defines the limit?", List.of(), "No limit is specified"), "",
                    REPEAT_USAGE);
            case "MODBAR-106" -> codingCalls.get(issueKey) == 1
                    ? CodingOutcome.needsDecision(new CodingOutcome.Decision(
                            CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS,
                            "Which module should expose the endpoint?", List.of(), "Not stated"), "", PARTIAL_USAGE)
                    : CodingOutcome.completed("Implemented the change", COMPLETE_USAGE);
            default -> throw new IllegalStateException("Unexpected coding request " + issueKey);
        };
    }

    /**
     * Stubs the pull request read while a delivery to the repository answers, before the flow reaches
     * the follow-up step; stubbed later, the step's first read fails and the flow retries it 30 seconds later.
     */
    private void stubPullRequestReadOnDelivery(String repository, int number) {
        DevFlowHarness.stubDelivery(delivery, receipt -> {
            if (receipt.repository().equals(repository)) {
                DevFlowHarness.stubPullRequestRead(GITHUB, repository, number, receipt.branch());
            }
        });
    }

    private void awaitDelivered(UUID executionId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(artifactStore.getLatest(executionId, DeliveryWorker.DELIVERY))
                        .as("delivered")
                        .isPresent()
                        .hasValueSatisfying(artifact ->
                                assertThat(json.readTree(artifact.getContent()).path("state").asString())
                                        .isEqualTo("DELIVERED")));
    }

    private void awaitTerminal(UUID executionId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.COMPLETED));
    }

    private UUID taskId(String issueId) {
        return tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issueId).orElseThrow().getId();
    }

    private TaskCommands.Selection selectionFor(String issueId) {
        return TaskFixture.selection(tasks.findById(taskId(issueId)).orElseThrow());
    }

    private static String linked(String otherKey) {
        return ", \"issuelinks\": [{\"type\": {\"inward\": \"is blocked by\", \"outward\": \"blocks\"}, "
                + "\"inwardIssue\": {\"key\": \"" + otherKey + "\", \"fields\": {\"summary\": \"Preparation\", "
                + "\"status\": {\"name\": \"Done\"}}}}]";
    }
}
