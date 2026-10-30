package org.folio.factory.app.pilot;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.admission.PilotRetryService;
import org.folio.factory.app.pilot.execution.PersistedPilotAttemptPolicy;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotPublicationIntent;
import org.folio.factory.app.pilot.persistence.PilotPublicationIntentRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.presentation.PilotTaskQuery;
import org.folio.factory.app.pilot.presentation.PilotTaskView;
import org.folio.factory.app.pilot.source.PilotDiscoveryService;
import org.folio.factory.app.pilot.source.PilotJiraInstance;
import org.folio.factory.app.web.DeveloperPricingProperties;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DeliveryReceipt;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The full deterministic M1 scenario in one Spring context against real
 * PostgreSQL, the real engine and discovery wiring, fixture Jira/GitHub
 * endpoints and a scripted coding runtime: multi-project discovery routes two
 * projects to different repositories and keeps unsupported matches visible, a
 * manual subset is admitted exactly once, one task reaches a ready pull request
 * while a failed task stops with a readable reason and the queue continues, an
 * explicit retry reaches a new outcome, a task added by its key joins the same
 * queue without a scan, started work holds after the task leaves My tasks and
 * changes in Jira, a lost publication response holds the attempt for inspection
 * until the developer marks it as stopped, and an incomplete usage component
 * keeps the task projection incomplete. Jira stays read-only, no pilot attempt opens an intermediate
 * review, coding starts once per attempt and no pull request is duplicated.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.poll-interval-ms=250",
        "factory.limits.dedup.window=PT0S",
        "factory.pilot.execution.enabled=true",
        "factory.pilot.execution.deadline=PT30M",
        "factory.pilot.execution.recovery-poll-interval-ms=3600000",
        "factory.pilot.execution.dispatch-interval-ms=3600000",
        "factory.pilot.source.assignee=Jane Developer",
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
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PilotEndToEndTest {

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

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static final WireMockServer jira = new WireMockServer(0);
    static final WireMockServer GITHUB = new WireMockServer(0);

    static final Path gitRoot = createGitRoot();
    static final Path BAR_WORK = createRepository("folio-org/mod-bar", "master");
    static final Path FOO_WORK = createRepository("folio-org/mod-foo", "master");
    static final Path CODING_CHANGE = createCodingChangeFixture();
    static final Path VERIFICATION_REPORTS = createVerificationFixture();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        jira.start();
        GITHUB.start();
        registry.add("factory.connectors.jira.base-url", jira::baseUrl);
        registry.add("factory.connectors.github.base-url", GITHUB::baseUrl);
        registry.add("factory.dev-factory.git-base-url", () -> gitRoot.toUri().toString());
        GITHUB.stubFor(get(urlPathEqualTo("/repos/" + BAR + "/pulls"))
                .withQueryParam("state", equalTo("all")).willReturn(okJson("[]")));
        GITHUB.stubFor(get(urlPathEqualTo("/repos/" + FOO + "/pulls"))
                .withQueryParam("state", equalTo("all")).willReturn(okJson("[]")));
        GITHUB.stubFor(post(urlPathEqualTo("/repos/" + BAR + "/pulls"))
                .willReturn(okJson("{\"html_url\": \"" + BAR_PR + "\"}")));
        GITHUB.stubFor(post(urlPathEqualTo("/repos/" + FOO + "/pulls"))
                .willReturn(okJson("{\"html_url\": \"" + FOO_PR + "\"}")));
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
    DeveloperPricingProperties pricing;

    @Autowired
    PilotDiscoveryService discovery;

    @Autowired
    PilotAdmissionService admissions;

    @Autowired
    PersistedPilotAttemptPolicy attemptPolicy;

    @Autowired
    PilotRetryService retries;

    @Autowired
    PilotDispatcher dispatcher;

    @Autowired
    PilotOutcomeService outcomes;

    @Autowired
    PilotJiraInstance jiraInstance;

    @Autowired
    PilotTaskRepository tasks;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PilotPublicationIntentRepository intents;

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
    PilotTaskQuery queries;

    private final JsonMapper json = JsonMapper.builder().build();
    private static final Map<String, Integer> codingCalls = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, CodingRequest> codingRequests = new java.util.concurrent.ConcurrentHashMap<>();

    @Test
    @Order(1)
    void discoveryRoutesTwoProjectsAndKeepsUnsupportedMatchesVisible() {
        stubSearch(
                issue("8101", "MODBAR-101", "MODBAR", "Add validation to mod-bar", "2026-10-01T09:00:00.000+0000", ""),
                issue("8103", "MODFOO-103", "MODFOO", "Refactor the mod-foo exporter", "2026-10-01T09:15:00.000+0000", ""),
                issue("8102", "MODBAR-102", "MODBAR", "Clarify the limit handling", "2026-10-01T09:30:00.000+0000", ""),
                issue("8104", "OTHERP-104", "OTHERP", "Task in an unmapped project", "2026-10-01T09:40:00.000+0000", ""),
                issue("8105", "MODBAR-105", "MODBAR", "Blocked by preparation", "2026-10-01T09:45:00.000+0000",
                        linked("MODBAR-999")));

        PilotDiscoveryService.SyncResult result = discovery.sync();

        assertThat(result.complete()).isTrue();
        assertThat(result.discoveredCount()).isEqualTo(5);
        assertThat(eligibility("8101")).isEqualTo(PilotTask.Eligibility.ELIGIBLE);
        assertThat(routingReason("8101")).isEqualTo("repository mod-bar");
        assertThat(eligibility("8103")).isEqualTo(PilotTask.Eligibility.ELIGIBLE);
        assertThat(routingReason("8103")).isEqualTo("repository mod-foo");
        assertThat(eligibility("8104")).isEqualTo(PilotTask.Eligibility.INELIGIBLE);
        assertThat(routingReason("8104"))
                .isEqualTo("Project OTHERP is not configured: no repository for it in config/developer-repositories.yaml");
        assertThat(eligibility("8105")).as("links never block a task").isEqualTo(PilotTask.Eligibility.ELIGIBLE);
        assertThat(attempts.count()).isZero();
        assertThat(executions.count()).isZero();
        assertThat(jiraStayedReadOnly()).isTrue();
    }

    @Test
    @Order(2)
    void manualSubsetAdmitsExactlyTheSelectedEligibleTasksOnce() {
        // A refused preflight holds admission's best-effort dispatch, so the
        // attempts stay queued for the poller.
        doThrow(new IllegalArgumentException("coding runtime not ready"))
                .when(preflight).requireReady(eq("dev-factory"), any());
        var selections = List.of(
                selectionFor("8101"), selectionFor("8103"), selectionFor("8102"), selectionFor("8104"));

        var result = admissions.runSelected(new PilotCommands.RunSelected("e2e-run-1", selections));

        assertThat(result.admittedTaskIds()).as(String.valueOf(result)).containsExactlyInAnyOrder(
                taskId("8101"), taskId("8103"), taskId("8102"));
        assertThat(result.rejectedReasons()).containsOnlyKeys(taskId("8104"));
        assertThat(result.rejectedReasons().get(taskId("8104"))).contains("not eligible");
        for (String issueId : List.of("8101", "8102", "8103")) {
            assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId(issueId)))
                    .singleElement()
                    .satisfies(attempt -> {
                        assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED);
                        assertThat(attempt.getKind()).isEqualTo(PilotAttempt.Kind.INITIAL);
                        assertThat(attempt.getAdmittedVia()).isEqualTo(PilotAttempt.AdmittedVia.MANUAL);
                    });
        }
        assertThat(executions.count()).isZero();

        var repeated = admissions.runSelected(new PilotCommands.RunSelected("e2e-run-1",
                List.of(selectionFor("8101"))));
        assertThat(repeated.admittedTaskIds()).containsExactly(taskId("8101"));
        assertThat(attempts.count()).isEqualTo(3);
        assertThat(jiraStayedReadOnly()).isTrue();
    }

    @BeforeEach
    void stubFlowCollaborators() {
        stubCodingOutcomes();
        stubWorkloads();
        stubDelivery();
    }

    @Test
    @Order(3)
    void firstTaskRunsTheDeveloperFlowToAReadyPullRequest() {
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(stateManager.get(executionId).getTriggerPayload()).contains("MODBAR-101");
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        UUID attemptId = attempts.findByExecutionId(executionId).orElseThrow().getId();

        awaitDelivered(executionId);
        PilotPublicationIntent intent = intents.findByExecutionId(executionId).orElseThrow();
        assertThat(intent.getRepository()).isEqualTo(BAR);
        assertThat(intent.getBaseBranch()).isEqualTo("master");
        Candidate candidate = json.readValue(latest(executionId, DevelopWorker.CANDIDATE), Candidate.class);
        assertThat(intent.getBaseSha()).isEqualTo(candidate.baseSha());
        assertThat(intent.getTreeSha()).isEqualTo(candidate.treeSha());
        assertThat(intent.getPatchSha256()).isEqualTo(candidate.patchSha256());
        assertThat(intent.getHeadBranch()).startsWith("factory/modbar-101-");
        stubPullRequestRead(BAR, 11, intent.getHeadBranch());

        PilotOutcomeService.AttemptOutcome ready = outcomes.refresh(attemptId);

        assertThat(ready.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.PR_READY);
        assertThat(ready.publicationIdentity()).isEqualTo(BAR_PR);
        assertThat(attempts.findById(attemptId).orElseThrow().getState()).isEqualTo(PilotAttempt.State.PR_READY);
        assertThat(attempts.findById(attemptId).orElseThrow().getPublicationIdentity()).isEqualTo(BAR_PR);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
        assertThat(json.readTree(latest(executionId, DeliveryWorker.DELIVERY)).path("pullRequestUrl").asString())
                .isEqualTo(BAR_PR);
        // "Implemented the change" does not use the report template, so the pull request opens as a draft.
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + BAR + "/pulls"))).getFirst()
                .getBodyAsString()).contains("\"draft\":true", "## Agent report");
        assertThat(json.readTree(latest(executionId, DeliveryWorker.DELIVERY)).path("pullRequestDraft").asBoolean())
                .isTrue();
        assertThat(attempts.findById(attemptId).orElseThrow().getDraftReasons()).contains("four expected sections");
        assertThat(json.readTree(latest(executionId, VerifyWorker.RESULT)).path("state").asString())
                .isEqualTo("DELIVERED");
        assertThat(jira.findAll(getRequestedFor(urlEqualTo("/rest/api/2/issue/MODBAR-101"))))
                .as("the approved snapshot is consumed without a second Jira read")
                .isEmpty();
        assertThat(codingCalls).containsEntry("MODBAR-101", 1);
        assertThat(attempts.findById(attemptId).orElseThrow().getPaidStartAt()).isNotNull();
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(reviewGateIds(executionId)).containsOnly("pr-feedback"));
        assertThat(jiraStayedReadOnly()).isTrue();
    }

    @Test
    @Order(4)
    void failedTaskStopsWithAReadableReasonAndTheQueueContinues() {
        UUID failedExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(stateManager.get(failedExecutionId).getTriggerPayload()).contains("MODFOO-103");
        awaitTerminal(failedExecutionId);
        UUID failedAttemptId = attempts.findByExecutionId(failedExecutionId).orElseThrow().getId();

        PilotOutcomeService.AttemptOutcome stopped = outcomes.refresh(failedAttemptId);

        assertThat(stopped.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(stopped.reason()).contains("DEVELOPMENT_FAILED", "the coding model rejected the task");
        assertThat(attempts.findById(failedAttemptId).orElseThrow().getResultReason())
                .contains("DEVELOPMENT_FAILED", "the coding model rejected the task");
        assertThat(json.readTree(latest(failedExecutionId, VerifyWorker.RESULT)).path("state").asString())
                .isEqualTo("DEVELOPMENT_FAILED");
        assertThat(json.readTree(latest(failedExecutionId, DeliveryWorker.DELIVERY)).path("state").asString())
                .isEqualTo("NOT_RUN");
        declaredOutputs().forEach(name -> assertThat(artifactStore.getLatest(failedExecutionId, name))
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
        assertThat(jiraStayedReadOnly()).isTrue();
    }

    @Test
    @Order(5)
    void explicitRetryRunsAFreshAttemptToANewOutcomeAndKeepsHistoryAndCosts() {
        UUID heldExecutionId = dispatcher.dispatchNext().orElseThrow();
        UUID heldAttemptId = attempts.findByExecutionId(heldExecutionId).orElseThrow().getId();
        PilotTask failedTask = tasks.findById(taskId("8103")).orElseThrow();

        String runSettings = "{\"profileId\":\"glm-5.3\",\"profileLabel\":\"GLM-5.3 (Z.ai)\","
                + "\"model\":\"glm-5.3\",\"endpointOrigin\":\"https://api.z.ai\",\"effort\":\"low\","
                + "\"maxTurns\":33,\"instructions\":\"Use the wiki REST URL.\"}";
        var firstClick = retries.retry(new PilotCommands.Retry("e2e-retry-103", failedTask.getId(),
                failedTask.getVersion(), PilotAdmissionService.inputFingerprint(failedTask),
                PilotAdmissionService.destinationFingerprint(failedTask), runSettings));
        var duplicateClick = retries.retry(new PilotCommands.Retry("e2e-retry-103", failedTask.getId(),
                failedTask.getVersion(), PilotAdmissionService.inputFingerprint(failedTask),
                PilotAdmissionService.destinationFingerprint(failedTask)));

        assertThat(firstClick.attemptId()).isNotEqualTo(heldAttemptId);
        assertThat(firstClick.state()).isEqualTo("RESERVED");
        assertThat(firstClick.executionId()).isNull();
        assertThat(duplicateClick.attemptId()).isEqualTo(firstClick.attemptId());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("8103"))).hasSize(2);
        assertThat(attempts.findById(firstClick.attemptId()).orElseThrow().getKind())
                .isEqualTo(PilotAttempt.Kind.RETRY);
        assertThat(executions.count()).isEqualTo(3);

        awaitTerminal(heldExecutionId);
        PilotOutcomeService.AttemptOutcome heldOutcome = outcomes.refresh(heldAttemptId);
        assertThat(heldOutcome.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(heldOutcome.reason()).contains("Which acceptance criterion defines the limit?");
        assertThat(reviewGateIds(heldExecutionId)).isEmpty();
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();

        UUID retryExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(attempts.findByExecutionId(retryExecutionId).orElseThrow().getId())
                .isEqualTo(firstClick.attemptId());
        awaitDelivered(retryExecutionId);
        PilotPublicationIntent intent = intents.findByExecutionId(retryExecutionId).orElseThrow();
        assertThat(intent.getRepository()).isEqualTo(FOO);
        stubPullRequestRead(FOO, 21, intent.getHeadBranch());

        PilotOutcomeService.AttemptOutcome retryOutcome = outcomes.refresh(firstClick.attemptId());

        assertThat(retryOutcome.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.PR_READY);
        // The settings chosen at Retry reach the coding request; the first attempt had none.
        assertThat(codingRequests.get("MODFOO-103").runSettings())
                .isEqualTo(new CodingRequest.RunSettings("glm-5.3", "low", 33, "Use the wiki REST URL."));
        assertThat(attempts.findById(firstClick.attemptId()).orElseThrow().getRunSettings()).contains("glm-5.3");
        assertThat(retryOutcome.publicationIdentity()).isEqualTo(FOO_PR);
        List<PilotAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(taskId("8103"));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getState()).isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(history.get(0).getResultReason()).contains("the coding model rejected the task");
        assertThat(history.get(1).getState()).isEqualTo(PilotAttempt.State.PR_READY);
        assertThat(history.get(1).getPublicationIdentity()).isEqualTo(FOO_PR);
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + FOO + "/pulls"))))
                .hasSize(1);
        assertThat(jiraStayedReadOnly()).isTrue();
    }

    @Test
    @Order(6)
    void taskAddedByKeyJoinsTheQueueWithoutAScanAndCannotBeAddedTwice() {
        // Keep the run queued (a refused preflight holds dispatch) so the next
        // scenario starts it after the task leaves My tasks.
        doThrow(new IllegalArgumentException("coding runtime not ready"))
                .when(preflight).requireReady(eq("dev-factory"), any());
        stubSearch(issue("8106", "MODBAR-106", "MODBAR", "Expose the new endpoint", "2026-10-01T10:00:00.000+0000", ""));

        var added = admissions.runByKey("e2e-key-106", jira.baseUrl() + "/browse/MODBAR-106");

        assertThat(added.problem()).isNull();
        assertThat(added.issueKey()).isEqualTo("MODBAR-106");
        PilotTask task = tasks.findById(added.taskId()).orElseThrow();
        assertThat(task.isInScope()).as("a key does not put the task into My tasks").isFalse();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId()))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED);
                    assertThat(attempt.getKind()).isEqualTo(PilotAttempt.Kind.INITIAL);
                    assertThat(attempt.getAdmittedVia()).isEqualTo(PilotAttempt.AdmittedVia.BY_KEY);
                });
        var again = admissions.runByKey("e2e-key-106-again", "modbar-106");
        assertThat(again.problem()).isEqualTo("MODBAR-106 is already queued or running");
        assertThat(admissions.runByKey("e2e-key-bad", "not a key").problem()).startsWith("Enter a Jira key");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId())).hasSize(1);

        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .willReturn(aResponse().withStatus(503).withBody("<html>" + "maintenance ".repeat(500) + "</html>")));
        assertThat(admissions.runByKey("e2e-key-107", "MODBAR-107").problem())
                .isEqualTo("Jira could not be read: HTTP 503");
        stubSearch(issue("8106", "MODBAR-106", "MODBAR", "Expose the new endpoint", "2026-10-01T10:00:00.000+0000", ""));
        assertThat(jiraStayedReadOnly()).isTrue();
    }

    @Test
    @Order(7)
    void startedWorkHoldsWhenTheTaskLeavesMyTasksAndChangesInJira() {
        UUID holderExecutionId = dispatcher.dispatchNext().orElseThrow();
        UUID holderAttemptId = attempts.findByExecutionId(holderExecutionId).orElseThrow().getId();
        assertThat(attempts.findById(holderAttemptId).orElseThrow().getTaskId()).isEqualTo(taskId("8106"));
        PilotTask retryTarget = tasks.findById(taskId("8102")).orElseThrow();
        String approvedSnapshot = retryTarget.getIssueSnapshot();

        var queuedRetry = retries.retry(new PilotCommands.Retry("e2e-retry-102", retryTarget.getId(),
                retryTarget.getVersion(), PilotAdmissionService.inputFingerprint(retryTarget),
                PilotAdmissionService.destinationFingerprint(retryTarget)));
        assertThat(queuedRetry.state()).isEqualTo("RESERVED");
        assertThat(queuedRetry.executionId()).isNull();

        stubSearch(
                issue("8101", "MODBAR-101", "MODBAR", "Add validation to mod-bar", "2026-10-01T09:00:00.000+0000", ""),
                issue("8103", "MODFOO-103", "MODFOO", "Refactor the mod-foo exporter", "2026-10-01T09:15:00.000+0000", ""),
                issue("8102", "MODBAR-102", "MODBAR", "Revised: the limit handling is now specified",
                        "2026-10-01T09:30:00.000+0000", ""),
                issue("8104", "OTHERP-104", "OTHERP", "Task in an unmapped project", "2026-10-01T09:40:00.000+0000", ""),
                issue("8105", "MODBAR-105", "MODBAR", "Blocked by preparation", "2026-10-01T09:45:00.000+0000",
                        linked("MODBAR-999")));
        assertThat(discovery.sync().complete()).isTrue();
        assertThat(tasks.findById(taskId("8106")).orElseThrow().isInScope()).isFalse();

        awaitTerminal(holderExecutionId);
        assertThat(outcomes.refresh(holderAttemptId).state())
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);

        UUID retryExecutionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt started = attempts.findById(queuedRetry.attemptId()).orElseThrow();
        assertThat(started.getExecutionId()).isEqualTo(retryExecutionId);
        assertThat(started.getApprovedSnapshot()).isEqualTo(approvedSnapshot);
        awaitTerminal(retryExecutionId);
        assertThat(outcomes.refresh(queuedRetry.attemptId()).state())
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);
        List<PilotAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(taskId("8102"));
        assertThat(history).hasSize(2);
        assertThat(history.get(1).getResultReason()).contains("Which acceptance criterion defines the limit?");
        assertThat(executions.count()).isEqualTo(6);
        assertThat(jiraStayedReadOnly()).isTrue();
    }

    @Test
    @Order(8)
    void lostPublicationResponseHoldsTheAttemptForInspectionWithoutARetry() {
        GITHUB.stubFor(post(urlPathEqualTo("/repos/" + BAR + "/pulls")).willReturn(okJson("{}")));
        PilotTask uncertainTask = tasks.findById(taskId("8106")).orElseThrow();

        var retry = retries.retry(new PilotCommands.Retry("e2e-retry-106", uncertainTask.getId(),
                uncertainTask.getVersion(), PilotAdmissionService.inputFingerprint(uncertainTask),
                PilotAdmissionService.destinationFingerprint(uncertainTask)));
        assertThat(retry.executionId()).isNotNull();
        UUID executionId = retry.executionId();

        awaitTerminal(executionId);
        assertThat(json.readTree(latest(executionId, DeliveryWorker.DELIVERY)).path("state").asString())
                .isEqualTo("DELIVERED");
        assertThat(json.readTree(latest(executionId, DeliveryWorker.DELIVERY)).path("pullRequestUrl").asString())
                .isEmpty();

        PilotOutcomeService.AttemptOutcome uncertain = outcomes.refresh(retry.attemptId());

        assertThat(uncertain.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(uncertain.released()).isFalse();
        assertThat(attempts.findById(retry.attemptId()).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(retry.attemptId());
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        var refused = retries.retry(new PilotCommands.Retry("e2e-retry-106-late", uncertainTask.getId(),
                uncertainTask.getVersion(), PilotAdmissionService.inputFingerprint(uncertainTask),
                PilotAdmissionService.destinationFingerprint(uncertainTask)));
        assertThat(refused.attemptId()).isEqualTo(retry.attemptId());
        assertThat(refused.state()).isEqualTo("NEEDS_INSPECTION");
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + BAR + "/pulls"))))
                .as("one create request per delivered attempt, never a duplicate pull request")
                .hasSize(2);
        assertThat(jiraStayedReadOnly()).isTrue();
    }

    @Test
    @Order(9)
    void markAsStoppedReleasesTheQueueAndTheHeldRunCanNoLongerPublish() {
        PilotAttempt held = attempts.findByTaskIdOrderByOrdinalAsc(taskId("8106")).getLast();
        assertThat(held.getState()).isEqualTo(PilotAttempt.State.NEEDS_INSPECTION);
        assertThat(outcomes.markStopped(attempts.findByTaskIdOrderByOrdinalAsc(taskId("8101")).getFirst().getId()))
                .as("only an attempt that needs inspection can be stopped")
                .isNotNull();

        assertThat(outcomes.markStopped(held.getId())).isNull();

        PilotAttempt stopped = attempts.findById(held.getId()).orElseThrow();
        assertThat(stopped.getState()).isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(stopped.getResultReason())
                .as("the held run lost its publication response, so the reason warns about a possible pull request")
                .startsWith(PilotOutcomeService.MARKED_STOPPED + "; " + PilotOutcomeService.PULL_REQUEST_MAY_EXIST);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
        assertThat(attemptPolicy.ownsActiveSlot(held.getExecutionId())).isFalse();
        assertThat(dispatcher.dispatchNext()).isEmpty();
        assertThat(outcomes.markStopped(held.getId())).isNotNull();
    }

    @Test
    @Order(10)
    void taskProjectionPreservesHistoryAndTokenomicsIncludingIncompleteUsage() {
        PilotTaskQuery.MyTasks myTasks = queries.myTasks(PageRequest.of(0, 10));

        assertThat(myTasks.available()).extracting(PilotTaskView::displayKey)
                .containsExactlyInAnyOrder("OTHERP-104", "MODBAR-105");
        assertThat(myTasks.triggered().getContent()).extracting(PilotTaskView::displayKey)
                .containsExactlyInAnyOrder("MODBAR-101", "MODFOO-103", "MODBAR-102", "MODBAR-106");
        assertThat(queries.detail(taskId("8101")).state()).isEqualTo(PilotTaskView.TaskState.PR_READY);
        assertThat(queries.detail(taskId("8103")).state()).isEqualTo(PilotTaskView.TaskState.PR_READY);
        assertThat(queries.detail(taskId("8102")).state()).isEqualTo(PilotTaskView.TaskState.STOPPED);
        assertThat(queries.detail(taskId("8106")).state()).isEqualTo(PilotTaskView.TaskState.STOPPED);
        assertThat(queries.detail(taskId("8105")).state()).isEqualTo(PilotTaskView.TaskState.AWAITING_SELECTION);
        assertThat(queries.detail(taskId("8104")).state()).isEqualTo(PilotTaskView.TaskState.UNSUPPORTED);
        assertThat(queries.detail(taskId("8105")).jiraStatus()).isEqualTo("To Do");

        PilotTaskView completeTask = queries.detail(taskId("8103"));
        assertThat(completeTask.attempts()).hasSize(2);
        assertThat(completeTask.usage().complete()).isTrue();
        assertThat(completeTask.usage().tokens()).isEqualTo(300_000L);
        assertThat(completeTask.usage().estimatesByCurrency().get("USD")).isEqualByComparingTo("1.00");
        assertThat(completeTask.attempts().get(0).usage().estimatesByCurrency().get("USD"))
                .isEqualByComparingTo("0.40");
        assertThat(completeTask.attempts().get(1).usage().estimatesByCurrency().get("USD"))
                .isEqualByComparingTo("0.60");
        assertThat(completeTask.attempts().get(1).pullRequestUrl()).isEqualTo(FOO_PR);

        PilotTaskView incompleteTask = queries.detail(taskId("8106"));
        assertThat(incompleteTask.usage().complete()).isFalse();
        assertThat(incompleteTask.usage().estimatesByCurrency().get("USD")).isEqualByComparingTo("0.55");

        PilotTaskView unstartedTask = queries.detail(taskId("8105"));
        assertThat(unstartedTask.usage().estimatesByCurrency()).isEmpty();
        assertThat(unstartedTask.usage().complete()).isTrue();

        assertThat(attempts.count()).isEqualTo(7);
        assertThat(executions.count()).isEqualTo(7);
        assertThat(codingCalls).containsEntry("MODBAR-101", 1)
                .containsEntry("MODFOO-103", 2)
                .containsEntry("MODBAR-102", 2)
                .containsEntry("MODBAR-106", 2);
        assertThat(attempts.findAll().stream().filter(attempt -> attempt.getPaidStartAt() != null).count())
                .isEqualTo(7);
        assertThat(reviews.findAll()).allSatisfy(review ->
                assertThat(review.getGateId()).isEqualTo("pr-feedback"));
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + BAR + "/pulls")))).hasSize(2);
        assertThat(GITHUB.findAll(postRequestedFor(urlPathEqualTo("/repos/" + FOO + "/pulls")))).hasSize(1);
        assertThat(jiraStayedReadOnly()).isTrue();
    }

    private boolean jiraStayedReadOnly() {
        assertThat(jira.getAllServeEvents()).allSatisfy(event ->
                assertThat(event.getRequest().getUrl()).isEqualTo("/rest/api/2/search/jql"));
        return true;
    }

    private List<String> reviewGateIds(UUID executionId) {
        return reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .map(review -> review.getGateId())
                .toList();
    }

    private List<String> declaredOutputs() {
        return flowRegistry.require("dev-factory").agentChain().stream()
                .flatMap(step -> step.outputs().stream())
                .distinct()
                .toList();
    }

    private void stubCodingOutcomes() {
        when(coding.id()).thenReturn("stub");
        when(coding.image()).thenReturn("stub:image");
        when(coding.identity(any())).thenReturn(Map.of("runtime", "stub", "image", "stub:image",
                "model", "claude-sonnet-5-5"));
        when(pricing.rateFor(any())).thenReturn(Optional.of(new DeveloperPricingProperties.Rate(
                "claude-sonnet-5-5", "USD", LocalDate.of(2026, 10, 22),
                URI.create("https://pricing.fixture.invalid"), BigDecimal.valueOf(2),
                BigDecimal.valueOf(0.1), BigDecimal.ONE, BigDecimal.TEN)));
        when(coding.code(any(), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            CodingRequest request = invocation.getArgument(1);
            codingCalls.merge(request.issueKey(), 1, Integer::sum);
            codingRequests.put(request.issueKey(), request);
            return outcomeFor(request.issueKey());
        });
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
                    "Which acceptance criterion defines the limit?", List.of(), "No limit is specified"),
                    REPEAT_USAGE);
            case "MODBAR-106" -> codingCalls.get(issueKey) == 1
                    ? CodingOutcome.needsDecision(new CodingOutcome.Decision(
                            CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS,
                            "Which module should expose the endpoint?", List.of(), "Not stated"), PARTIAL_USAGE)
                    : CodingOutcome.completed("Implemented the change", COMPLETE_USAGE);
            default -> throw new IllegalStateException("Unexpected coding request " + issueKey);
        };
    }

    private void stubWorkloads() {
        var scope = mock(DockerWorkloads.StepScope.class);
        var baseline = mock(DockerWorkloads.Workload.class);
        var codingWorkload = mock(DockerWorkloads.Workload.class);
        var verification = mock(DockerWorkloads.Workload.class);
        when(docker.beginStep(any(), anyString())).thenReturn(scope);
        when(scope.createTrusted(anyString(), any(), anyString())).thenReturn(baseline);
        when(scope.createSeeded(anyString(), any(), anyString(), eq("coding"))).thenReturn(codingWorkload);
        when(scope.createSeeded(anyString(), any(), anyString(), eq("verification"))).thenReturn(verification);
        when(baseline.execute(anyList(), anyInt(), anyInt(), any()))
                .thenReturn(new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        when(verification.execute(anyList(), anyInt()))
                .thenReturn(new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        when(baseline.name()).thenReturn("stub-baseline");
        when(codingWorkload.name()).thenReturn("stub-coding");
        when(verification.name()).thenReturn("stub-verification");
        doAnswer(invocation -> {
            copyFixture(CODING_CHANGE, invocation.getArgument(0, Path.class));
            return null;
        }).when(codingWorkload).export(any());
        doAnswer(invocation -> {
            copyFixture(VERIFICATION_REPORTS, invocation.getArgument(0, Path.class));
            return null;
        }).when(verification).export(any());
    }

    private void stubDelivery() {
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), anyBoolean(), anyInt()))
                .thenAnswer(invocation -> {
                    String executionId = invocation.getArgument(0, String.class);
                    String issueKey = invocation.getArgument(1, String.class);
                    Candidate candidate = invocation.getArgument(4, Candidate.class);
                    DeliveryTarget target = invocation.getArgument(6, DeliveryTarget.class);
                    boolean stableBranch = invocation.getArgument(8, Boolean.class);
                    return new DeliveryReceipt(executionId, target.repository(),
                            CandidateDelivery.branchFor(executionId, issueKey, candidate, stableBranch),
                            candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "d".repeat(40));
                });
    }

    private void stubSearch(String... issues) {
        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .withRequestBody(matchingJsonPath("$[?(!@.nextPageToken)]"))
                .willReturn(okJson("{\"issues\": [" + String.join(", ", issues) + "]}")));
    }

    private void stubPullRequestRead(String repository, int number, String headBranch) {
        GITHUB.stubFor(get(urlEqualTo("/repos/" + repository + "/pulls/" + number))
                .willReturn(okJson("{\"number\": " + number + ", \"state\": \"open\", \"merged\": false,"
                        + "\"html_url\": \"https://github.com/" + repository + "/pull/" + number + "\","
                        + "\"head\": {\"ref\": \"" + headBranch + "\", \"sha\": \"" + "d".repeat(40) + "\","
                        + "\"repo\": {\"full_name\": \"" + repository + "\"}},"
                        + "\"base\": {\"ref\": \"master\", \"repo\": {\"full_name\": \"" + repository + "\"}}}")));
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

    private String latest(UUID executionId, String name) {
        return artifactStore.getLatest(executionId, name).orElseThrow().getContent();
    }

    private UUID taskId(String issueId) {
        return tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issueId).orElseThrow().getId();
    }

    private PilotTask.Eligibility eligibility(String issueId) {
        return tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issueId).orElseThrow().getEligibility();
    }

    private String routingReason(String issueId) {
        return tasks.findByJiraInstanceAndIssueId(jiraInstance.current(), issueId).orElseThrow().getRoutingReason();
    }

    private PilotCommands.Selection selectionFor(String issueId) {
        PilotTask task = tasks.findById(taskId(issueId)).orElseThrow();
        return new PilotCommands.Selection(task.getId(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task),
                PilotAdmissionService.destinationFingerprint(task));
    }

    private static String issue(String id, String key, String project, String summary, String created,
                                String extraFields) {
        return """
                {"id": "%s", "key": "%s", "fields": {"summary": "%s", "description": "Description of %s", \
                "status": {"name": "To Do"}, "issuetype": {"name": "Story"}, "labels": ["backend"], \
                "created": "%s", "components": [{"name": "api"}], \
                "project": {"key": "%s"}%s}}""".formatted(id, key, summary, key, created, project, extraFields);
    }

    private static String linked(String otherKey) {
        return ", \"issuelinks\": [{\"type\": {\"inward\": \"is blocked by\", \"outward\": \"blocks\"}, "
                + "\"inwardIssue\": {\"key\": \"" + otherKey + "\", \"fields\": {\"summary\": \"Preparation\", "
                + "\"status\": {\"name\": \"Done\"}}}}]";
    }

    private static void copyFixture(Path source, Path target) {
        try (var paths = Files.walk(source)) {
            for (Path entry : paths.toList()) {
                Path destination = target.resolve(source.relativize(entry));
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot export fixture workspace", e);
        }
    }

    private static Path createGitRoot() {
        try {
            return Files.createTempDirectory("pilot-e2e-git");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path createRepository(String sourceRepo, String branch) {
        Path bare = gitRoot.resolve(sourceRepo + ".git");
        Path work = gitRoot.resolve("work-" + sourceRepo.replace('/', '-'));
        git(gitRoot, "init", "--bare", "-b", branch, bare.toString());
        git(gitRoot, "init", "-b", branch, work.toString());
        writeWorkspaceFile(work, "README.md", "# fixture " + sourceRepo + "\n");
        git(work, "add", ".");
        git(work, "-c", "user.name=fixture", "-c", "user.email=fixture@example.org",
                "commit", "--allow-empty", "-m", "init " + sourceRepo);
        git(work, "push", bare.toString(), branch);
        return work;
    }

    private static Path createCodingChangeFixture() {
        Path directory = createTempDirectory("pilot-e2e-coding-");
        writeWorkspaceFile(directory, "README.md", "# fixture changed by the stubbed coding run\n");
        return directory;
    }

    private static Path createVerificationFixture() {
        Path directory = createTempDirectory("pilot-e2e-verify-");
        writeWorkspaceFile(directory, "target/surefire-reports/TEST-FixtureSuite.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="FixtureSuite" tests="2" skipped="0" failures="0" errors="0" time="0.01">
                  <testcase name="works" classname="FixtureSuite" time="0.005"/>
                  <testcase name="alsoWorks" classname="FixtureSuite" time="0.005"/>
                </testsuite>
                """);
        return directory;
    }

    private static Path createTempDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeWorkspaceFile(Path root, String relativeName, String content) {
        try {
            Path file = root.resolve(relativeName);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IllegalStateException(command + " failed: " + output);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
