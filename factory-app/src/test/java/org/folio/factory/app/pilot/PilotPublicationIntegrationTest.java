package org.folio.factory.app.pilot;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;
import org.folio.factory.app.pilot.execution.PersistedPilotAttemptPolicy;
import org.folio.factory.app.pilot.execution.PilotPublicationGuard;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotPublicationIntent;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.persistence.PilotPublicationIntentRepository;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.github.GitHubRestConnector;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryReceipt;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.pilot.PilotAttemptPolicy;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.IntakeResolveWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Publication authority against real PostgreSQL and a mocked GitHub HTTP
 * endpoint: the configured destination (official or fork) publishes branch and
 * pull request into the same repository with the intent persisted before the
 * first remote mutation, a changed authority stops before any remote write, and a lost create response is reconciled through the
 * state-covering lookup instead of creating a duplicate pull request.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.pilot.execution.enabled=true",
        "factory.pilot.execution.deadline=PT30M",
        "factory.pilot.execution.dispatch-interval-ms=3600000",
        "factory.connectors.github.token=fixture-github-token"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotPublicationIntegrationTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String OFFICIAL = "folio-org/folio-module-sidecar";
    private static final String PR_PATH = "/repos/" + OFFICIAL + "/pulls";
    private static final String PR_URL = "https://github.com/" + OFFICIAL + "/pull/21";

    private final JsonMapper json = JsonMapper.builder().build();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void githubMockBaseUrl(DynamicPropertyRegistry registry) {
        GITHUB.start();
        registry.add("factory.connectors.github.base-url", GITHUB::baseUrl);
    }

    static final WireMockServer GITHUB = new WireMockServer(0);

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoBean
    DockerWorkloads docker;

    @MockitoBean
    CandidateDelivery delivery;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    PilotTaskRepository tasks;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PilotAdmissionService admissions;

    @Autowired
    PilotDispatcher dispatcher;

    @Autowired
    PilotOutcomeService outcomes;

    @Autowired
    PilotAttemptPolicy pilotPolicy;

    @Autowired
    DeliveryWorker deliveryWorker;

    @Autowired
    PilotPublicationIntentRepository intents;

    @Autowired
    DevFactoryProperties repositories;

    @Autowired
    DevRuntimeProperties runtime;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetPilotState() {
        GITHUB.resetAll();
        intents.deleteAll();
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        doNothing().when(docker).cleanOwned(any(), anyString());
    }

    @Test
    void officialDestinationPublishesAndPersistsTheIntentBeforeTheFirstRemoteMutation() {
        UUID executionId = admitAndDispatch("70001", "MODSIDECAR-701");
        Candidate candidate = candidate();
        VerificationReceipt receipt = receipt(executionId, candidate);
        AtomicReference<String> deliveredBranch = new AtomicReference<>();
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), anyBoolean(), anyInt()))
                .thenAnswer(invocation -> {
                    assertThat(intents.findByExecutionId(executionId))
                            .as("the publication intent is durable before the first remote mutation")
                            .isPresent();
                    String branch = CandidateDelivery.branchFor(invocation.getArgument(0, String.class),
                            invocation.getArgument(1, String.class),
                            invocation.getArgument(4, Candidate.class),
                            invocation.getArgument(8, Boolean.class));
                    deliveredBranch.set(branch);
                    return new DeliveryReceipt(executionId.toString(), OFFICIAL, branch,
                            candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(),
                            "c".repeat(40));
                });
        stubPullRequestLookup("[]");
        stubPullRequestCreation(prBody(PR_URL));

        var result = deliveryWorker.execute(context(executionId, "MODSIDECAR-701", candidate, receipt));

        var output = json.readTree(result.outputs().get(DeliveryWorker.DELIVERY));
        assertThat(output.path("state").asString()).describedAs(output.path("reason").asString())
                .isEqualTo("DELIVERED");
        assertThat(output.path("pullRequestUrl").asString()).isEqualTo(PR_URL);
        verify(delivery).deliver(anyString(), anyString(), anyString(), anyString(), any(), any(),
                eq(new DeliveryTarget(OFFICIAL, "master")), anyString(), anyBoolean(), anyInt());

        PilotPublicationIntent intent = intents.findByExecutionId(executionId).orElseThrow();
        assertThat(intent.getRepository()).isEqualTo(OFFICIAL);
        assertThat(intent.getBaseBranch()).isEqualTo("master");
        assertThat(intent.getHeadBranch()).isEqualTo(deliveredBranch.get());
        assertThat(intent.getBaseSha()).isEqualTo(candidate.baseSha());
        assertThat(intent.getTreeSha()).isEqualTo(candidate.treeSha());
        assertThat(intent.getPatchSha256()).isEqualTo(candidate.patchSha256());

        UUID attemptId = attempts.findByExecutionId(executionId).orElseThrow().getId();
        writeArtifact(executionId, DeliveryWorker.DELIVERY, result.outputs().get(DeliveryWorker.DELIVERY));
        PilotOutcomeService.AttemptOutcome ready = outcomes.refresh(attemptId);

        assertThat(ready.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.PR_READY);
        assertThat(ready.publicationIdentity()).isEqualTo(PR_URL);
        assertThat(attempts.findById(attemptId).orElseThrow().getPublicationIdentity()).isEqualTo(PR_URL);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
    }

    @Test
    void jiraChangesAfterTheClickDoNotStopPublication() {
        UUID executionId = admitAndDispatch("70011", "MODSIDECAR-711");
        PilotTask task = tasks.findById(attempts.findByExecutionId(executionId).orElseThrow().getTaskId())
                .orElseThrow();
        task.setSourceRevision("revised-by-a-later-scan");
        task.setInScope(false);
        tasks.save(task);
        Candidate candidate = candidate();
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), anyBoolean(), anyInt()))
                .thenAnswer(invocation -> new DeliveryReceipt(executionId.toString(), OFFICIAL,
                        CandidateDelivery.branchFor(invocation.getArgument(0, String.class),
                                invocation.getArgument(1, String.class),
                                invocation.getArgument(4, Candidate.class),
                                invocation.getArgument(8, Boolean.class)),
                        candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "c".repeat(40)));
        stubPullRequestLookup("[]");
        stubPullRequestCreation(prBody(PR_URL));

        var result = deliveryWorker.execute(
                context(executionId, "MODSIDECAR-711", candidate, receipt(executionId, candidate)));

        var output = json.readTree(result.outputs().get(DeliveryWorker.DELIVERY));
        assertThat(output.path("state").asString()).describedAs(output.path("reason").asString())
                .isEqualTo("DELIVERED");
        assertThat(output.path("pullRequestUrl").asString()).isEqualTo(PR_URL);
    }

    @Test
    void changedRoutingAfterAdmissionStopsBeforeAnyRemoteWriteAndPreservesTheCandidate() {
        UUID executionId = admitAndDispatch("70021", "MODSIDECAR-721");
        PilotAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        writeArtifact(executionId, DevelopWorker.CANDIDATE, json.writeValueAsString(candidate()));
        PilotTask task = tasks.findById(attempt.getTaskId()).orElseThrow();
        task.setEligibility(PilotTask.Eligibility.INELIGIBLE);
        task.setRoutingReason("Project MODSIDECAR is not configured: no repository for it in"
                + " config/developer-repositories.yaml");
        tasks.save(task);
        Candidate candidate = candidate();

        var result = deliveryWorker.execute(
                context(executionId, "MODSIDECAR-721", candidate, receipt(executionId, candidate)));

        var output = json.readTree(result.outputs().get(DeliveryWorker.DELIVERY));
        assertThat(output.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(output.path("reason").asString())
                .contains("the consumed repository no longer matches the admitted routing");
        assertThat(intents.findByExecutionId(executionId)).isEmpty();
        assertThat(GITHUB.getAllServeEvents()).isEmpty();
        verifyNoInteractions(delivery);
        assertThat(artifactStore.getLatest(executionId, DevelopWorker.CANDIDATE))
                .as("the frozen candidate is preserved unpublished")
                .isPresent();
    }

    @Test
    void anUnavailableAuthorityRecheckStopsWithoutRemoteWrites() {
        UUID executionId = admitAndDispatch("70031", "MODSIDECAR-731");
        Candidate candidate = candidate();
        var failingTasks = mock(PilotTaskRepository.class);
        when(failingTasks.findById(any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        var failingPolicy = new PersistedPilotAttemptPolicy(attempts, tasks, installations, intents,
                new PilotPublicationGuard(attempts, failingTasks), transactionManager);
        var worker = worker(repositories, failingPolicy);

        var result = worker.execute(
                context(executionId, "MODSIDECAR-731", candidate, receipt(executionId, candidate)));

        var output = json.readTree(result.outputs().get(DeliveryWorker.DELIVERY));
        assertThat(output.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(output.path("reason").asString()).contains("could not be rechecked");
        assertThat(intents.findByExecutionId(executionId)).isEmpty();
        assertThat(GITHUB.getAllServeEvents()).isEmpty();
        verifyNoInteractions(delivery);
    }

    @Test
    void lostCreateResponseIsReconciledWithoutADuplicatePullRequest() {
        UUID executionId = admitAndDispatch("70041", "MODSIDECAR-741");
        Candidate candidate = candidate();
        VerificationReceipt receipt = receipt(executionId, candidate);
        String branch = CandidateDelivery.branchFor(executionId.toString(), "MODSIDECAR-741",
                candidate, true);
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(),
                anyString(), anyBoolean(), anyInt()))
                .thenAnswer(invocation -> new DeliveryReceipt(executionId.toString(), OFFICIAL, branch,
                        candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(),
                        "c".repeat(40)));
        stubPullRequestLookup("[]");
        GITHUB.stubFor(post(urlPathEqualTo(PR_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{oops-not-json")));

        assertThatThrownBy(() -> deliveryWorker.execute(
                context(executionId, "MODSIDECAR-741", candidate, receipt)))
                .as("the create response is lost after the remote accepted the pull request")
                .isInstanceOf(RuntimeException.class);
        assertThat(GITHUB.findAll(postRequestedFor(urlEqualTo(PR_PATH))))
                .as("createdPullRequests")
                .hasSize(1);

        stubPullRequestLookup(pullRequestJson(PR_URL, branch, "c".repeat(40), "closed"));

        var recovered = json.readTree(deliveryWorker.execute(
                context(executionId, "MODSIDECAR-741", candidate, receipt))
                .outputs().get(DeliveryWorker.DELIVERY));

        assertThat(recovered.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(recovered.path("pullRequestUrl").asString()).isEqualTo(PR_URL);
        assertThat(GITHUB.findAll(postRequestedFor(urlEqualTo(PR_PATH))))
                .as("createdPullRequests")
                .hasSize(1);
        assertThat(intents.findByExecutionId(executionId).orElseThrow().getHeadBranch()).isEqualTo(branch);
    }

    @Test
    void retryIsNotAllowedWhilePublicationIdentityIsUnknown() {
        UUID executionId = admitAndDispatch("70051", "MODSIDECAR-751");
        PilotAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        stateManager.transition(executionId, ExecutionStatus.COMPLETED, null);
        writeArtifact(executionId, DeliveryWorker.DELIVERY,
                "{\"state\":\"DELIVERED\",\"deliveryRepository\":\"" + OFFICIAL + "\","
                        + "\"deliveryBranch\":\"factory/modsidecar-751\",\"deliveryCommitSha\":\""
                        + "c".repeat(40) + "\"}");

        PilotOutcomeService.AttemptOutcome outcome = outcomes.refresh(attempt.getId());

        assertThat(outcome.state())
                .as("a delivered candidate without a known pull-request identity is uncertain")
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(outcome.released())
                .as("retryAllowedWithUnknownPublication")
                .isFalse();
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attempt.getId());
        assertThat(intents.findByAttemptId(attempt.getId())).isEmpty();
        assertThat(GITHUB.getAllServeEvents()).isEmpty();
    }

    private void stubPullRequestLookup(String body) {
        GITHUB.stubFor(get(urlPathEqualTo(PR_PATH))
                .withQueryParam("state", equalTo("all"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubPullRequestCreation(String body) {
        GITHUB.stubFor(post(urlPathEqualTo(PR_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static String prBody(String url) {
        return "{\"html_url\":\"" + url + "\"}";
    }

    private static String pullRequestJson(String url, String headBranch, String headSha, String state) {
        return "[{\"html_url\":\"" + url + "\",\"state\":\"" + state + "\",\"merged\":false,"
                + "\"head\":{\"repo\":{\"full_name\":\"" + OFFICIAL + "\"},\"ref\":\"" + headBranch
                + "\",\"sha\":\"" + headSha + "\"},"
                + "\"base\":{\"repo\":{\"full_name\":\"" + OFFICIAL + "\"},\"ref\":\"master\"}}]";
    }

    private DeliveryWorker worker(DevFactoryProperties repositoriesProperties, PilotAttemptPolicy policy) {
        return new DeliveryWorker(repositoriesProperties, runtime,
                new DevDeliveryProperties(Map.of(), true), delivery,
                new GitHubProperties(null, "fixture-github-token"),
                new JiraProperties("https://jira.example.org", null, null),
                new GitHubRestConnector(new GitHubProperties(GITHUB.baseUrl(), "fixture-github-token"),
                        RestClient.builder()),
                new FrontmatterCodec(), stateManager, policy);
    }

    private static DevFactoryProperties.Repository repository(DeliveryTarget destination) {
        return new DevFactoryProperties.Repository("folio-org/folio-module-sidecar", "master",
                "maven:3.9-eclipse-temurin-21", "java21-unit", List.of("MODSIDECAR"), List.of(), destination);
    }

    private static Candidate candidate() {
        String patch = "diff --git a/README.md b/README.md\n";
        return new Candidate("sidecar", "a".repeat(40), "b".repeat(40), Candidate.sha256(patch), patch,
                "CANDIDATE_UNVERIFIED");
    }

    private VerificationReceipt receipt(UUID executionId, Candidate candidate) {
        return new VerificationReceipt(executionId.toString(), "sidecar", candidate.baseSha(),
                candidate.treeSha(), candidate.patchSha256(), "java21-unit", "maven:3.9-eclipse-temurin-21",
                List.of("mvn", "-B", "-ntp", "test"), "fixture", Instant.EPOCH, Instant.EPOCH, 0,
                1, 1, 0, 0, "PASS", "ok", null, 0, List.of("surefire"), List.of());
    }

    private static CodingRequest codingRequest(String issueKey) {
        return new CodingRequest(issueKey, "Task", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("sidecar", OFFICIAL, "master", "a".repeat(40)),
                null);
    }

    private AgentContext context(UUID executionId, String issueKey, Candidate candidate,
                                 VerificationReceipt receipt) {
        String brief = new FrontmatterCodec().render(
                Map.of("issue", Map.of("key", issueKey, "summary", "Task")), "Task");
        Map<String, ArtifactContent> inputs = new java.util.HashMap<>();
        inputs.put(VerifyWorker.RESULT, artifact(VerifyWorker.RESULT,
                "{\"state\":\"VERIFIED\",\"verificationPlan\":\"java21-unit\"}"));
        inputs.put(DevelopWorker.CANDIDATE, artifact(DevelopWorker.CANDIDATE,
                json.writeValueAsString(candidate)));
        inputs.put(VerifyWorker.RECEIPT, artifact(VerifyWorker.RECEIPT,
                json.writeValueAsString(receipt)));
        inputs.put(IntakeResolveWorker.TASK_BRIEF, artifact(IntakeResolveWorker.TASK_BRIEF, brief));
        inputs.put(IntakeResolveWorker.CODING_REQUEST, artifact(IntakeResolveWorker.CODING_REQUEST,
                json.writeValueAsString(codingRequest(issueKey))));
        return new AgentContext(executionId, "publish", inputs, null, Map.of(), List.of());
    }

    private static ArtifactContent artifact(String name, String content) {
        return new ArtifactContent(name, 1, "application/json", content);
    }

    private void writeArtifact(UUID executionId, String name, String content) {
        artifactStore.putMarkdown(executionId, name, content, "pilot-publication-test");
    }

    private UUID admitAndDispatch(String issueId, String displayKey) {
        PilotTask task = tasks.save(new PilotTask(INSTANCE, issueId, displayKey, "Title of " + displayKey,
                "MODSIDECAR", "{\"key\": \"" + displayKey + "\", \"summary\": \"Task\"}",
                "revision-" + issueId, PilotTask.Eligibility.ELIGIBLE, "repository sidecar"));
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
        var result = admissions.runSelected(new PilotCommands.RunSelected("run-" + issueId,
                List.of(new PilotCommands.Selection(task.getId(), task.getVersion(),
                        PilotAdmissionService.inputFingerprint(task),
                        PilotAdmissionService.destinationFingerprint(task)))));
        assertThat(result.rejectedReasons()).isEmpty();
        return dispatcher.dispatchNext().orElseThrow();
    }
}
