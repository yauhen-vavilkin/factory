package org.folio.factory.app.tasks;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.worker.CodingDecisionGateWorker;
import org.folio.factory.devfactory.worker.DecisionGateWorker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;
import org.folio.factory.app.support.SharedDatabase;

/**
 * Task executions run the existing Developer Flow end to end against the real
 * engine and PostgreSQL with a fake coding runtime, workload layer and Git
 * fixtures. Task ambiguity and coding decisions stop the attempt through the
 * ordinary non-ready artifact chain without a HITL review or another paid call,
 * while the same flow started manually still opens its existing reviews.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "factory.engine.poll-interval-ms=250"
})
@Import({StubLlmConfiguration.class, SharedDatabase.class})
@DirtiesContext
class TaskAutonomousFlowIntegrationTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String DESCRIPTION = "As an operator I want X. AC1: X happens.";

    static WireMockServer jira = new WireMockServer(0);
    static WireMockServer github = new WireMockServer(0);
    static final Path gitRoot = createGitRoot();
    static final String barSha = createRepository("folio-org/mod-bar", "master");
    static final String fooSha = createRepository("folio-org/mod-foo", "master");
    static final String fooStorageSha = createRepository("folio-org/mod-foo-storage", "main");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        jira.start();
        github.start();
        registry.add("factory.connectors.jira.base-url", jira::baseUrl);
        registry.add("factory.connectors.jira.email", () -> "bot@example.org");
        registry.add("factory.connectors.jira.api-token", () -> "token");
        registry.add("factory.connectors.github.base-url", github::baseUrl);
        registry.add("factory.connectors.github.token", () -> "token");
        registry.add("factory.dev-factory.git-base-url", () -> gitRoot.toUri().toString());
        registry.add("factory.dev-factory.runtime.plans.java-maven-test[0]", () -> "mvn");
        registry.add("factory.dev-factory.runtime.plans.java-maven-test[1]", () -> "test");
        repository(registry, "mod-bar", "folio-org/mod-bar", "master", "MODBAR");
        repository(registry, "mod-foo", "folio-org/mod-foo", "master", "MODFOO");
        repository(registry, "mod-foo-storage", "folio-org/mod-foo-storage", "main", "MODFOO");
    }

    private static void repository(DynamicPropertyRegistry registry, String key, String source, String branch,
                                   String project) {
        String prefix = "factory.dev-factory.repositories." + key + ".";
        registry.add(prefix + "source-repo", () -> source);
        registry.add(prefix + "base-branch", () -> branch);
        registry.add(prefix + "build-image", () -> "maven:3.9-eclipse-temurin-21");
        registry.add(prefix + "verification-plan", () -> "java-maven-test");
        registry.add(prefix + "jira-projects[0]", () -> project);
    }

    @AfterAll
    static void stopWireMock() {
        jira.stop();
        github.stop();
    }

    @TempDir
    Path workspace;

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoBean
    CodingRuntime coding;

    @MockitoBean
    DockerWorkloads docker;

    @MockitoBean
    CandidateFreezer freezer;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    AuditLog auditLog;

    @Autowired
    HitlReviewRepository reviews;

    @Autowired
    HitlDecisionService decisionService;

    @Autowired
    PipelineRouter router;

    @Autowired
    FlowRegistry flowRegistry;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskAttemptRepository attempts;

    @Autowired
    TaskInstallationRepository installations;

    @Autowired
    TaskAdmissionService admissions;

    @Autowired
    TaskDispatcher dispatcher;

    @Autowired
    TaskAttemptPolicy taskPolicy;

    private final JsonMapper json = JsonMapper.builder().build();
    private final FrontmatterCodec codec = new FrontmatterCodec();

    @BeforeEach
    void setUp() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        installations.save(TaskInstallations.seeded());
        jira.resetAll();
        github.resetAll();
        stubWorkloads();
        stubCodingIdentity();
    }

    private void stubWorkloads() {
        var scope = mock(DockerWorkloads.StepScope.class);
        var workload = mock(DockerWorkloads.Workload.class);
        when(docker.beginStep(any(), anyString())).thenReturn(scope);
        when(scope.createTrusted(anyString(), any(), anyString())).thenReturn(workload);
        when(scope.createSeeded(anyString(), any(), anyString(), anyString())).thenReturn(workload);
        when(workload.execute(anyList(), anyInt(), anyInt(), any()))
                .thenReturn(new org.folio.factory.devfactory.runtime.Processes.Result(0, "[INFO] BUILD SUCCESS"));
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
    }

    private void stubCodingIdentity() {
        when(coding.id()).thenReturn("stub");
        when(coding.image()).thenReturn("stub:image");
        when(coding.identity(any())).thenReturn(Map.of("runtime", "stub", "image", "stub:image"));
    }

    @Test
    void taskAmbiguousMappingStopsThroughTheArtifactChainWithoutReviewOrPaidWork() {
        admit("40001", "MODFOO-401", "repository mod-foo");

        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        awaitStatus(executionId, ExecutionStatus.COMPLETED);

        List<HitlReview> taskHitlReviews = reviews.findByExecutionIdOrderByCreatedAtAsc(executionId);
        assertThat(taskHitlReviews).isEmpty();
        verify(coding, never()).code(any(), any(), anyInt(), any(), any());
        assertThat(github.findAll(anyRequestedFor(anyUrl())))
                .as("publicationInvocations")
                .isEmpty();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        String resultReason = attempt.getResultReason();
        assertThat(resultReason).contains("ambiguous");

        var intake = metadata(executionId, "dev_intake.md");
        assertThat(intake.path("state").asString()).isEqualTo("BLOCKED");
        assertThat(intake.path("reason").asString()).isEqualTo("AMBIGUOUS_REPOSITORY_MAPPING");
        var brief = metadata(executionId, "dev_task_brief.md");
        assertThat(brief.path("state").asString()).isEqualTo("BLOCKED");
        assertThat(brief.path("reason").asString()).isEqualTo("AMBIGUOUS_REPOSITORY_MAPPING");
        assertThat(brief.path("repository").isMissingNode()).isTrue();
        declaredOutputs().forEach(name ->
                assertThat(artifactStore.getLatest(executionId, name))
                        .as(name)
                        .isPresent());
        JsonNode result = json.readTree(latest(executionId, "dev_result.json"));
        assertThat(result.path("state").asString()).isEqualTo("BLOCKED");
        assertThat(json.readTree(latest(executionId, "dev_verification.json")).path("state").asString())
                .isEqualTo("NOT_RUN");
        assertThat(json.readTree(latest(executionId, "dev_delivery.json")).path("state").asString())
                .isEqualTo("NOT_RUN");
        assertThat(latest(executionId, "dev_pr_state.json")).contains("SKIPPED");
        assertThat(jira.findAll(getRequestedFor(urlEqualTo("/rest/api/2/issue/MODFOO-401"))))
                .as("the approved snapshot is consumed without a second Jira read")
                .isEmpty();
    }

    @Test
    void taskMissingRequirementsStopWithoutReviewPreservesUsageAndRunsNoSecondPaidCall() {
        admit("40002", "MODBAR-402", "repository mod-bar");
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", 10L);
        usage.put("outputTokens", 3L);
        when(coding.code(any(), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            Consumer<Map<String, Object>> observer = invocation.getArgument(3);
            observer.accept(new LinkedHashMap<>(Map.of(
                    "activity", "coding_usage", "inputTokens", 10L, "outputTokens", 3L)));
            return CodingOutcome.needsDecision(new CodingOutcome.Decision(
                    CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Which length limit should be enforced?",
                    List.of(), "No limit is specified"), usage);
        });

        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        awaitStatus(executionId, ExecutionStatus.COMPLETED);

        List<HitlReview> taskHitlReviews = reviews.findByExecutionIdOrderByCreatedAtAsc(executionId);
        assertThat(taskHitlReviews).isEmpty();
        verify(coding, times(1)).code(any(), any(), anyInt(), any(), any());
        assertThat(github.findAll(anyRequestedFor(anyUrl())))
                .as("publicationInvocations")
                .isEmpty();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        String resultReason = attempt.getResultReason();
        assertThat(resultReason).contains("Which length limit should be enforced?");

        JsonNode outcome = json.readTree(latest(executionId, "dev_coding_outcome.json"));
        assertThat(outcome.path("status").asString()).isEqualTo("NEEDS_DECISION");
        assertThat(outcome.path("decision").path("question").asString())
                .isEqualTo("Which length limit should be enforced?");
        assertThat(outcome.path("metrics").path("inputTokens").asLong()).isEqualTo(10L);
        JsonNode result = json.readTree(latest(executionId, "dev_result.json"));
        assertThat(result.path("state").asString()).isEqualTo("NEEDS_DECISION");
        assertThat(result.path("reason").asString()).contains("Which length limit should be enforced?");
        assertThat(json.readTree(latest(executionId, "dev_verification.json")).path("state").asString())
                .isEqualTo("NOT_RUN");
        assertThat(json.readTree(latest(executionId, "dev_delivery.json")).path("state").asString())
                .isEqualTo("NOT_RUN");
        assertThat(latest(executionId, "dev_pr_state.json")).contains("SKIPPED");
        List<String> progressDetails = auditLog.forExecution(executionId).stream()
                .filter(event -> event.getEventType() == AuditEventType.RUNTIME_PROGRESS)
                .map(event -> event.getDetail())
                .toList();
        assertThat(progressDetails)
                .as("the one paid coding invocation keeps its usage evidence")
                .anyMatch(detail -> detail.contains("coding_usage") && detail.contains("\"inputTokens\": 10"));
        declaredOutputs().forEach(name ->
                assertThat(artifactStore.getLatest(executionId, name))
                        .as(name)
                        .isPresent());
        assertThat(jira.findAll(getRequestedFor(urlEqualTo("/rest/api/2/issue/MODBAR-402"))))
                .as("the approved snapshot is consumed without a second Jira read")
                .isEmpty();
    }

    @Test
    void persistedPolicyGrantsOnlyTheBoundExecutionAndClaimsOnePaidStart() {
        UUID legacyExecution = UUID.randomUUID();
        assertThat(taskPolicy.find(legacyExecution)).isEmpty();
        assertThat(taskPolicy.claimPaidStart(legacyExecution)).isFalse();
        taskPolicy.recordStop(legacyExecution, "implement", "ignored for legacy executions");

        UUID executionId = UUID.randomUUID();
        Task task = tasks.save(new Task(INSTANCE, "40011", "MODBAR-411", "Title", "MODBAR",
                snapshotJson("MODBAR-411"), "revision-40011", Task.Eligibility.ELIGIBLE,
                "repository mod-bar"));
        TaskAttempt attempt = new TaskAttempt(task.getId(), 1, TaskAttempt.Kind.INITIAL,
                "request-port", TaskAttempt.State.QUEUED);
        attempt.setInputFingerprint("revision-40011");
        attempt.setDestinationFingerprint("destination-hash");
        attempt.setApprovedSnapshot(task.getIssueSnapshot());
        attempt.setExecutionId(executionId);
        attempts.save(attempt);

        var context = taskPolicy.find(executionId).orElseThrow();
        assertThat(context.attemptId()).isEqualTo(attempt.getId());
        assertThat(context.repositoryKey()).isEqualTo("mod-bar");
        assertThat(context.approvedSnapshot()).containsEntry("key", "MODBAR-411");

        assertThat(taskPolicy.claimPaidStart(executionId)).isTrue();
        assertThat(taskPolicy.claimPaidStart(executionId)).isFalse();
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getPaidStartAt()).isNotNull();

        taskPolicy.recordStop(executionId, "read-task", "ambiguous repository mapping");
        taskPolicy.recordStop(executionId, "implement", "a later stop must not win");

        TaskAttempt stored = attempts.findById(attempt.getId()).orElseThrow();
        assertThat(stored.getResultReason()).startsWith("read-task: ");
        assertThat(stored.getResultReason()).contains("ambiguous");
        assertThat(taskPolicy.find(legacyExecution)).isEmpty();
    }

    @Test
    void taskUnverifiableAdmittedRepositoryStopsInsteadOfTrustingTheReDerivedMapping() {
        admit("40003", "MODBAR-403", "mapped to mod-bar");

        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        awaitStatus(executionId, ExecutionStatus.COMPLETED);

        verify(coding, never()).code(any(), any(), anyInt(), any(), any());
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        assertThat(attempt.getResultReason()).contains("cannot be verified");

        var intake = metadata(executionId, "dev_intake.md");
        assertThat(intake.path("state").asString()).isEqualTo("BLOCKED");
        assertThat(intake.path("reason").asString()).isEqualTo("REPOSITORY_MAPPING_CHANGED");
        var brief = metadata(executionId, "dev_task_brief.md");
        assertThat(brief.path("state").asString()).isEqualTo("BLOCKED");
        assertThat(brief.path("reason").asString()).isEqualTo("REPOSITORY_MAPPING_CHANGED");
        assertThat(brief.path("repository").isMissingNode()).isTrue();
        declaredOutputs().forEach(name ->
                assertThat(artifactStore.getLatest(executionId, name))
                        .as(name)
                        .isPresent());
        assertThat(jira.findAll(getRequestedFor(urlEqualTo("/rest/api/2/issue/MODBAR-403"))))
                .as("the approved snapshot is consumed without a second Jira read")
                .isEmpty();
    }

    @Test
    void legacyAmbiguousMappingStillOpensItsRepositoryReview() {
        stubIssue("MODFOO-421");

        UUID executionId = start("MODFOO-421");

        HitlReview review = awaitPendingReview(executionId, DecisionGateWorker.GATE_ID);
        assertThat(review.getStepIndex()).isEqualTo(1);
        assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);
        assertThat(artifactStore.getLatest(executionId, "dev_task_brief.md")).isEmpty();

        decisionService.decide(review.getId(), HitlDecision.REJECT, "dev-lead", "not now", null);

        assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).hasSize(1);
    }

    @Test
    void legacyCodingDecisionStillOpensItsReview() {
        stubIssue("MODBAR-422");
        when(coding.code(any(), any(), anyInt(), any(), any())).thenReturn(CodingOutcome.needsDecision(
                new CodingOutcome.Decision(CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS,
                        "Which acceptance criterion defines the limit?", List.of(), "No limit specified"),
                Map.of()));

        UUID executionId = start("MODBAR-422");

        HitlReview review = awaitPendingReview(executionId, CodingDecisionGateWorker.GATE_ID);
        assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);
        verify(coding, times(1)).code(any(), any(), anyInt(), any(), any());

        decisionService.decide(review.getId(), HitlDecision.REJECT, "dev-lead", "not now", null);

        assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).hasSize(1);
        verify(coding, times(1)).code(any(), any(), anyInt(), any(), any());
    }

    private void admit(String issueId, String displayKey, String routingReason) {
        Task task = tasks.save(new Task(INSTANCE, issueId, displayKey, "Title of " + displayKey,
                "MODBAR", snapshotJson(displayKey), "revision-" + issueId,
                Task.Eligibility.ELIGIBLE, routingReason));
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
        var result = admissions.runSelected(new TaskCommands.RunSelected("run-" + issueId,
                List.of(new TaskCommands.Selection(task.getId(), task.getVersion(),
                        TaskAdmissionChecks.inputFingerprint(task),
                        TaskAdmissionChecks.destinationFingerprint(task))), null));
        assertThat(result.rejectedReasons()).isEmpty();
    }

    private List<String> declaredOutputs() {
        return flowRegistry.require("dev-factory").agentChain().stream()
                .flatMap(step -> step.outputs().stream())
                .distinct()
                .toList();
    }

    private UUID start(String issueKey) {
        return router.routeManual("dev-factory",
                json.readTree(json.writeValueAsString(Map.of("issueKey", issueKey))));
    }

    private void stubIssue(String key) {
        String fields = json.writeValueAsString(Map.of(
                "summary", "Summary of " + key,
                "description", DESCRIPTION,
                "status", Map.of("name", "Open"),
                "issuetype", Map.of("name", "Story"),
                "labels", List.of("backend")));
        jira.stubFor(get(urlEqualTo("/rest/api/2/issue/" + key)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\": \"" + key + "\", \"fields\": " + fields + "}")));
    }

    private String snapshotJson(String issueKey) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("key", issueKey);
        snapshot.put("summary", "Summary of " + issueKey);
        snapshot.put("status", "Open");
        snapshot.put("type", "Story");
        snapshot.put("labels", List.of());
        snapshot.put("components", List.of());
        snapshot.put("description", DESCRIPTION);
        snapshot.put("comments", List.of());
        snapshot.put("links", List.of());
        snapshot.put("truncated", List.of());
        return json.writeValueAsString(snapshot);
    }

    private void awaitStatus(UUID executionId, ExecutionStatus status) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(status));
    }

    private HitlReview awaitPendingReview(UUID executionId, String gateId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId))
                        .anyMatch(r -> r.getGateId().equals(gateId) && r.getStatus() == HitlReviewStatus.PENDING));
        List<HitlReview> all = reviews.findByExecutionIdOrderByCreatedAtAsc(executionId);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private String latest(UUID executionId, String name) {
        return artifactStore.getLatest(executionId, name).orElseThrow().getContent();
    }

    private JsonNode metadata(UUID executionId, String name) {
        return codec.parse(latest(executionId, name)).metadata();
    }

    private static Path createGitRoot() {
        try {
            return Files.createTempDirectory("task-autonomous-git");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String createRepository(String sourceRepo, String branch) {
        Path bare = gitRoot.resolve(sourceRepo + ".git");
        Path work = gitRoot.resolve("work-" + sourceRepo.replace('/', '-'));
        git(gitRoot, "init", "--bare", "-b", branch, bare.toString());
        git(gitRoot, "init", "-b", branch, work.toString());
        git(work, "-c", "user.name=fixture", "-c", "user.email=fixture@example.org",
                "commit", "--allow-empty", "-m", "init " + sourceRepo);
        git(work, "push", bare.toString(), branch);
        return git(work, "rev-parse", "HEAD").strip();
    }

    private static String git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IllegalStateException(command + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
