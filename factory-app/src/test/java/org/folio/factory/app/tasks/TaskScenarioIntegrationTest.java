package org.folio.factory.app.tasks;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.support.DevFlowHarness;
import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.app.support.SharedWireMock;
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.tasks.presentation.AttemptFailure;
import org.folio.factory.app.tasks.source.TaskDiscoveryService;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.engine.ExecutionClaimService;
import org.folio.factory.core.engine.ExecutionEngine;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.folio.factory.app.support.DevFlowHarness.issue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * The tier A scenarios of the demo readiness plan that pass on today's code. A person starts each run by
 * HTTP as the pages do, the engine advances it in the test thread with the poller off, and the scenario
 * ends on the text of My tasks, the task page and the run page. Only the agent, Docker, delivery, GitHub
 * and Jira are scripted ({@link DevFlowHarness}); every reason, state and artifact comes from the real
 * workers and task services. Each scenario has its own Jira task and frees the single slot at its end.
 */
@FactoryIntegrationTest
@Import({SharedWireMock.Jira.class, SharedWireMock.GitHub.class})
@ActiveProfiles("task-execution")
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
        "factory.dev-factory.repositories.mod-bar.delivery.repository=folio-org/mod-bar"})
// Own context: task queue with its own catalog, Jira and GitHub; closing it stops its schedulers and frees its connections.
@DirtiesContext
class TaskScenarioIntegrationTest {

    private static final String BAR = "folio-org/mod-bar";
    private static final String BASE_README = "# fixture folio-org/mod-bar\n";
    private static final Map<String, Object> USAGE = Map.of(
            "inputTokens", 100_000L, "outputTokens", 20_000L, "cacheReadTokens", 0L, "cacheWriteTokens", 0L);
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    static final WireMockServer JIRA = SharedWireMock.JIRA;
    static final WireMockServer GITHUB = SharedWireMock.GITHUB;
    static final Path GIT_ROOT = DevFlowHarness.createGitRoot("task-scenario-git");
    static final Path BAR_WORK = DevFlowHarness.createRepositoryWithFiles(GIT_ROOT, BAR, "master",
            Map.of("README.md", BASE_README));
    static final Path AGENT_CHANGE = DevFlowHarness.workspace("task-scenario-change-",
            Map.of("README.md", "# fixture changed by the scripted agent\n"));
    static final Path NO_CHANGE = DevFlowHarness.workspace("task-scenario-unchanged-", Map.of("README.md", BASE_README));
    static final Path PASSING_REPORTS = DevFlowHarness.passingReports();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("factory.dev-factory.git-base-url", () -> GIT_ROOT.toUri().toString());
    }

    @BeforeAll
    static void scanTheTasks(@Autowired TaskDiscoveryService discovery) {
        JIRA.resetAll();
        GITHUB.resetAll();
        DevFlowHarness.stubSearch(JIRA,
                issue("8201", "MODBAR-201", "MODBAR", "Validate the limit", "2026-10-01T09:00:00.000+0000", ""),
                issue("8202", "MODBAR-202", "MODBAR", "Nothing to change", "2026-10-01T09:01:00.000+0000", ""),
                issue("8203", "MODBAR-203", "MODBAR", "Unclear limit", "2026-10-01T09:02:00.000+0000", ""),
                issue("8204", "MODBAR-204", "MODBAR", "Slow task", "2026-10-01T09:03:00.000+0000", ""),
                issue("8205", "MODBAR-205", "MODBAR", "Provider down", "2026-10-01T09:04:00.000+0000", ""),
                issue("8212", "MODBAR-212", "MODBAR", "Gone from Jira", "2026-10-01T09:05:00.000+0000", ""),
                issue("8213", "MODBAR-213", "MODBAR", "Docker down", "2026-10-01T09:06:00.000+0000", ""));
        assertThat(discovery.sync().complete()).isTrue();
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
    TaskRepository tasks;

    @Autowired
    TaskAttemptRepository attempts;

    @Autowired
    TaskInstallationRepository installations;

    @Autowired
    TaskJiraInstance jiraInstance;

    @Autowired
    TaskDispatcher dispatcher;

    @Autowired
    TaskOutcomeService outcomes;

    @Autowired
    StateManager stateManager;

    @Autowired
    ExecutionClaimService claims;

    @Autowired
    ExecutionEngine engine;

    @LocalServerPort
    int port;

    private final RestClient http = RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()))
            .build();
    private final List<CodingRequest> codingRequests = new ArrayList<>();
    /** What the agent does with the next coding request; a scenario replaces it. */
    private Function<CodingRequest, CodingOutcome> agent =
            request -> CodingOutcome.completed("Implemented the change", USAGE);
    private DevFlowHarness.Workloads workloads;

    @BeforeEach
    void scriptTheBoundaries() {
        DevFlowHarness.stubCodingRuntime(coding, models, request -> {
            codingRequests.add(request);
            return agent.apply(request);
        });
        workloads = DevFlowHarness.stubWorkloads(docker, AGENT_CHANGE, PASSING_REPORTS);
        DevFlowHarness.stubDelivery(delivery, receipt -> { });
    }

    /** A scenario that fails half-way must not hold the single slot for the next one. */
    @AfterEach
    void freeTheSlot() {
        UUID active = installations.findById(TaskInstallation.ID).orElseThrow().getActiveAttemptId();
        if (active == null) {
            return;
        }
        UUID executionId = attempts.findById(active).orElseThrow().getExecutionId();
        if (executionId != null && !stateManager.get(executionId).getStatus().isFinal()) {
            stateManager.transition(executionId, ExecutionStatus.CANCELLED, Map.of());
        }
        outcomes.refresh(active);
    }

    /** Scenario 1. */
    @Test
    void agentEditsFilesAndChecksPassButTheReportHasOpenPointsSoADraftPullRequestOpens() {
        UUID taskId = taskId("8201");
        pullRequestOpensAs(31);

        assertRedirect(postRun(taskId));

        assertThat(finish(taskId).getState()).isEqualTo(TaskAttempt.State.PR_READY);
        var pages = pages(taskId);
        assertRow(pages.myTasks(), "Validate the limit MODBAR-201", "$0.40 Draft PR #31");
        assertThat(pages.task()).contains("Draft PR #31 Nothing needed from you Pull request #31 is open as a draft",
                "Factory opened it as a draft because: The agent's final report does not use the four expected"
                        + " sections", "Open pull request #31");
        assertThat(pages.run()).contains("Pull request #31 is open as a draft", "2 · Implement changes Passed",
                "3 · Verify changes Passed · 2 tests", "4 · Create pull request Passed · draft");
        assertNoInternalWording(pages);
    }

    /** Scenario 2. */
    @Test
    void agentFinishesWithoutChangingAFile() {
        UUID taskId = taskId("8202");
        workloads.agentLeaves(NO_CHANGE);

        assertRedirect(postRun(taskId));

        assertThat(finish(taskId).getState()).isEqualTo(TaskAttempt.State.STOPPED);
        assertFailedWithoutPullRequest(taskId, "Nothing to change MODBAR-202", TaskStops.NO_CHANGES_REASON);
    }

    /** Scenario 3, and a second Answer that starts no second attempt (scenario 13). */
    @Test
    void answerToTheAgentsQuestionStartsAttemptTwoThatOpensAPullRequest() {
        UUID taskId = taskId("8203");
        agent = request -> CodingOutcome.needsDecision(new CodingOutcome.Decision(
                CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Should the limit be 100 or 1000?",
                List.of("Use 100", "Use 1000"), "The story names no number"), "", USAGE);
        assertRedirect(postRun(taskId));
        assertThat(finish(taskId).getState()).isEqualTo(TaskAttempt.State.STOPPED);
        assertAsksForAnAnswer(taskId);

        agent = request -> CodingOutcome.completed("Implemented the change", USAGE);
        pullRequestOpensAs(33);
        assertRedirect(postRetry(taskId, Map.of("answer", "0")));
        assertThat(postRetry(taskId, Map.of("answer", "0")).getStatusCode().value()).isEqualTo(302);

        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId)).hasSize(2);
        assertThat(finish(taskId).getState()).isEqualTo(TaskAttempt.State.PR_READY);
        assertThat(codingRequests.getLast().runSettings().instructions())
                .startsWith("My answer to your question: Use 100");
        var pages = pages(taskId);
        assertRow(pages.myTasks(), "Unclear limit MODBAR-203", "$0.80 Draft PR #33");
        assertThat(pages.task()).contains("Pull request #33 is open as a draft", "$0.80 spent in 2 attempts",
                "Attempt 2 · answer to the agent's question");
        assertNoInternalWording(pages);
    }

    /**
     * Scenario 4, then scenario 13: a second Run while the attempt is live and a second Retry after it
     * failed give one new attempt and no error page.
     */
    @Test
    void timeLimitStopsTheAttemptAndOneRetryStartsOneNewAttempt() {
        UUID taskId = taskId("8204");
        agent = request -> CodingOutcome.failed("TIME_LIMIT", TaskStops.timeLimitReason(60), USAGE);
        assertRedirect(postRun(taskId));
        assertThat(postRun(taskId).getStatusCode().value()).isEqualTo(302);
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId)).hasSize(1);

        assertThat(finish(taskId).getState()).isEqualTo(TaskAttempt.State.STOPPED);
        var stopped = assertFailedWithoutPullRequest(taskId, "Slow task MODBAR-204", TaskStops.timeLimitReason(60));
        assertThat(stopped.task()).contains("Limits in Configuration");
        assertThat(stopped.run()).contains("Limits in Configuration");

        agent = request -> CodingOutcome.completed("Implemented the change", USAGE);
        pullRequestOpensAs(34);
        assertRedirect(postRetry(taskId, Map.of()));
        assertThat(postRetry(taskId, Map.of()).getStatusCode().value()).isEqualTo(302);
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId)).hasSize(2);
        assertThat(latestAttempt(taskId).getKind()).isEqualTo(TaskAttempt.Kind.RETRY);
        assertThat(finish(taskId).getState()).isEqualTo(TaskAttempt.State.PR_READY);
        assertThat(pages(taskId).task()).contains("Pull request #34 is open as a draft", "Attempt 2 · retry");
    }

    /** Scenario 5: the model endpoint refuses the key. */
    @Test
    void providerFailureStopsTheAttemptWithItsCauseAndWhatToCheck() {
        UUID taskId = taskId("8205");
        String providerMessage = "Claude Code stopped with an error (subtype error_during_execution, API status"
                + " 401). Check the provider and the Developer Flow settings, then retry. Error: Invalid API key";
        agent = request -> CodingOutcome.failed("PROVIDER_FAILED", providerMessage, USAGE);

        assertRedirect(postRun(taskId));

        assertThat(finish(taskId).getState()).isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(installations.findById(TaskInstallation.ID).orElseThrow().getActiveAttemptId())
                .as("a shared outage stops this attempt and the queue continues").isNull();
        assertFailedWithoutPullRequest(taskId, "Provider down MODBAR-205", providerMessage + ".");
    }

    /** Scenario 12: a task that cannot start stops before any run, with the preflight's cause. */
    @Test
    void taskThatCannotStartSaysWhyAndOffersTheNextAttempt() {
        UUID taskId = taskId("8212");
        doThrow(new DeveloperPreflight.TaskNotRunnableException("Jira did not return the requested issue."))
                .when(preflight).requireReady(eq("dev-factory"), any());

        assertRedirect(postRun(taskId));

        TaskAttempt stopped = latestAttempt(taskId);
        assertThat(stopped.getState()).isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(stopped.getExecutionId()).isNull();
        var pages = pages(taskId);
        assertRow(pages.myTasks(), "Gone from Jira Your turn MODBAR-212",
                "Could not start: Jira did not return the requested issue. Failed");
        assertThat(pages.task()).contains("Failed Your turn Attempt 1 failed",
                "Reason Could not start: Jira did not return the requested issue. No pull request was created.",
                "Start attempt 2", "Attempt 1 · first run not started");
        assertNoInternalWording(pages);
    }

    /** Scenario 12: Run while Factory's preflight fails keeps the task queued and says why on both pages. */
    @Test
    void runWhileThePreflightFailsShowsTheQueueNoticeUntilTheQueueMoves() {
        UUID taskId = taskId("8213");
        doThrow(new IllegalArgumentException("Developer Flow preflight failed: Docker is not reachable."))
                .when(preflight).requireReady(eq("dev-factory"), any());
        String notice = "Queued tasks are not starting. Developer Flow preflight failed: Docker is not reachable."
                + " Factory tries again on its next dispatch poll.";

        assertRedirect(postRun(taskId));

        assertThat(latestAttempt(taskId).getState()).isEqualTo(TaskAttempt.State.RESERVED);
        var queued = pages(taskId);
        assertThat(queued.myTasks()).contains(notice);
        assertRow(queued.myTasks(), "Docker down MODBAR-213", "next in line Queued");
        assertThat(queued.task()).contains("Queued Waiting in the queue Next in line " + notice);
        assertNoInternalWording(queued);

        reset(preflight);
        pullRequestOpensAs(35);
        dispatcher.dispatchNext();
        assertThat(finish(taskId).getState()).isEqualTo(TaskAttempt.State.PR_READY);
        assertThat(page("/flows/dev-factory")).doesNotContain("Queued tasks are not starting");
    }

    /** The pages of a failed attempt: its cause on each page, no pull request and the form for the next one. */
    private Pages assertFailedWithoutPullRequest(UUID taskId, String titleAndKey, String reason) {
        var pages = pages(taskId);
        int keyStart = titleAndKey.lastIndexOf(' ');
        assertRow(pages.myTasks(), titleAndKey.substring(0, keyStart) + " Your turn" + titleAndKey.substring(keyStart),
                AttemptFailure.firstSentence(reason));
        assertThat(pages.task()).contains("Failed Your turn Attempt 1 failed", "Reason " + reason,
                "No pull request was created.", "Start attempt 2");
        assertThat(pages.run()).contains("Failed Your turn Attempt 1 failed", "Reason " + reason,
                "No pull request was created.", "Start attempt 2 on the task page",
                "2 · Implement changes Failed", "3 · Verify changes Not started");
        assertNoInternalWording(pages);
        return pages;
    }

    private void assertAsksForAnAnswer(UUID taskId) {
        var pages = pages(taskId);
        assertRow(pages.myTasks(), "Unclear limit Your turn MODBAR-203", "$0.40 Needs your answer");
        assertThat(pages.task()).contains("Needs your answer Your turn The agent stopped with a question",
                "The agent's question Coding agent Should the limit be 100 or 1000?", "Use 100", "Use 1000",
                "Start attempt 2 with this answer");
        assertThat(pages.run()).contains("Needs your answer Your turn The agent stopped with a question",
                "Answer on the task page", "2 · Implement changes Stopped with a question",
                "3 · Verify changes Not started");
        assertNoInternalWording(pages);
    }

    /**
     * The task's row on My tasks reads {@code start}, then only its time ("just now"), then {@code end}; the
     * time holds no capital letter or "$", so the match cannot run into the next row.
     */
    private static void assertRow(String myTasks, String start, String end) {
        assertThat(myTasks).containsPattern(Pattern.quote(start) + " [^$A-Z]{1,20}? " + Pattern.quote(end));
    }

    private record Pages(String myTasks, String task, String run) {

        List<String> all() {
            return List.of(myTasks, task, run);
        }
    }

    /**
     * No page holds internal wording: a fallback reason, a UUID or a Java class name. The run page's
     * technical details name the delivered branch, which holds the run id, so they are left out.
     */
    private static void assertNoInternalWording(Pages pages) {
        assertThat(pages.all()).allSatisfy(text -> assertThat(withoutTechnicalDetails(text))
                .doesNotContain("could say why", "engine execution ended", "Exception")
                .doesNotContainPattern(UUID_PATTERN)
                .doesNotContainPattern("\\b(?:org|java)\\.[a-z]+\\.[a-z]"));
    }

    private static String withoutTechnicalDetails(String text) {
        int details = text.indexOf("Technical details");
        return details < 0 ? text : text.substring(0, details);
    }

    private void pullRequestOpensAs(int number) {
        DevFlowHarness.stubPullRequestCreation(GITHUB, BAR, pullRequestUrl(number));
        DevFlowHarness.stubDelivery(delivery,
                receipt -> DevFlowHarness.stubPullRequestRead(GITHUB, BAR, number, receipt.branch()));
    }

    private static String pullRequestUrl(int number) {
        return "https://github.com/" + BAR + "/pull/" + number;
    }

    /** Runs the latest attempt of the task to its end and settles it as the dispatcher does. */
    private TaskAttempt finish(UUID taskId) {
        TaskAttempt attempt = latestAttempt(taskId);
        assertThat(attempt.getExecutionId()).as("the run started").isNotNull();
        drive(attempt.getExecutionId());
        outcomes.refresh(attempt.getId());
        dispatcher.dispatchNext();
        return attempts.findById(attempt.getId()).orElseThrow();
    }

    /** Advances the run in the test thread until it ends or waits for a person. */
    private void drive(UUID executionId) {
        // PostgreSQL checks next_run_at against its own clock, so a step scheduled now may need another poll.
        await().pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(20))
                .atMost(Duration.ofSeconds(30)).until(() -> {
                    var status = stateManager.get(executionId).getStatus();
                    if (status == ExecutionStatus.AWAITING_HITL || status.isFinal()) {
                        return true;
                    }
                    claims.claim(100).forEach(engine::advance);
                    return false;
                });
    }

    private Pages pages(UUID taskId) {
        UUID executionId = latestAttempt(taskId).getExecutionId();
        return new Pages(page("/flows/dev-factory"), page("/tasks/" + taskId),
                executionId == null ? "" : page("/executions/" + executionId));
    }

    private String page(String path) {
        return DevFlowHarness.visibleText(http.get().uri(url(path)).retrieve().body(String.class));
    }

    /** Run on My tasks for one selected task, with the fields its row sends. */
    private ResponseEntity<Void> postRun(UUID taskId) {
        Task task = tasks.findById(taskId).orElseThrow();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("taskId", taskId.toString());
        form.add("version-" + taskId, String.valueOf(task.getVersion()));
        form.add("input-" + taskId, TaskAdmissionChecks.inputFingerprint(task));
        form.add("destination-" + taskId, TaskAdmissionChecks.destinationFingerprint(task));
        return post("/tasks/run-selected", form);
    }

    /** The task page's form that starts the next attempt; {@code fields} adds an answer or instructions. */
    private ResponseEntity<Void> postRetry(UUID taskId, Map<String, String> fields) {
        Task task = tasks.findById(taskId).orElseThrow();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("expectedVersion", String.valueOf(task.getVersion()));
        form.add("inputFingerprint", TaskAdmissionChecks.inputFingerprint(task));
        form.add("destinationFingerprint", TaskAdmissionChecks.destinationFingerprint(task));
        fields.forEach(form::add);
        return post("/tasks/" + taskId + "/retry", form);
    }

    private ResponseEntity<Void> post(String path, LinkedMultiValueMap<String, String> form) {
        return http.post().uri(url(path)).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().toBodilessEntity();
    }

    private static void assertRedirect(ResponseEntity<Void> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(String.valueOf(response.getHeaders().getLocation())).doesNotContain("error=");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private TaskAttempt latestAttempt(UUID taskId) {
        return attempts.findByTaskIdOrderByOrdinalAsc(taskId).getLast();
    }

    private UUID taskId(String issueId) {
        return tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issueId).orElseThrow().getId();
    }
}
