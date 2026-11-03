package org.folio.factory.app;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.support.DevFlowHarness;
import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.app.support.SharedWireMock;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.devfactory.decision.DevArtifactAmendmentValidator;
import org.folio.factory.devfactory.worker.DecisionGateWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Developer Flow intake against the real engine, PostgreSQL and HITL services:
 * fixture Jira responses, local Git fixture repositories for base resolution, and
 * the plugin-local conditional decision gate.
 */
@FactoryIntegrationTest
@Import(SharedWireMock.Jira.class)
@ActiveProfiles("engine")
// Own context: running engine with its own repository catalog and git fixture; closing it stops its schedulers and frees its connections.
@DirtiesContext
class DeveloperIntakeDecisionTest {

    // Admission is covered separately; these fixtures exercise deliberately blocked intake decisions.
    @MockitoBean
    DeveloperPreflight preflight;

    private static final String GATE_ID = DecisionGateWorker.GATE_ID;
    private static final int DECISION_STEP_INDEX = 1;
    private static final String DESCRIPTION = "As an operator I want X. AC1: X happens.";

    static final WireMockServer jira = SharedWireMock.JIRA;
    static final Path gitRoot = DevFlowHarness.createGitRoot("dev-factory-git");
    static final String barSha = DevFlowHarness.createRepository(gitRoot, "folio-org/mod-bar", "master");
    static final String fooSha = DevFlowHarness.createRepository(gitRoot, "folio-org/mod-foo", "master");
    static final String fooStorageSha = DevFlowHarness.createRepository(gitRoot, "folio-org/mod-foo-storage", "main");

    static {
        DevFlowHarness.createRepository(gitRoot, "folio-org/mod-wrong-branch", "main");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("factory.connectors.jira.email", () -> "bot@example.org");
        registry.add("factory.connectors.jira.api-token", () -> "token");
        registry.add("factory.dev-factory.git-base-url", () -> gitRoot.toUri().toString());
        repository(registry, "mod-bar", "folio-org/mod-bar", "master", "MODBAR");
        repository(registry, "mod-foo", "folio-org/mod-foo", "master", "MODFOO");
        repository(registry, "mod-foo-storage", "folio-org/mod-foo-storage", "main", "MODFOO");
        repository(registry, "mod-wrong-branch", "folio-org/mod-wrong-branch", "master", "MODWRONG");
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

    @LocalServerPort
    int port;

    @Autowired
    PipelineRouter router;

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
    DecisionGateWorker decisionGateWorker;

    @Autowired
    FlowRegistry flowRegistry;

    @Autowired
    DevArtifactAmendmentValidator devValidator;

    private final JsonMapper json = JsonMapper.builder().build();
    private RestClient rest;

    private DevFlowHarness harness;

    @BeforeEach
    void setUp() {
        harness = new DevFlowHarness(stateManager, artifactStore, reviews);
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        jira.resetAll();
    }

    @AfterEach
    void noJiraWrites() {
        List<String> nonReads = new ArrayList<>();
        jira.findAll(anyRequestedFor(anyUrl())).stream()
                .filter(r -> !"GET".equals(r.getMethod().getName()))
                .forEach(r -> nonReads.add(r.getMethod() + " " + r.getUrl()));
        assertThat(nonReads).as("Developer Flow must not write to Jira").isEmpty();
    }

    @Test
    void readyIssueResolvesWithoutReview() {
        String longDescription = DESCRIPTION + "x".repeat(25_000);
        DevFlowHarness.stubIssue(jira, "MODBAR-1", longDescription, """
                "comment": {"comments": [
                  {"author": {"displayName": "PO"}, "created": "2026-09-01", "body": "Keep it small"}]},
                "issuelinks": [
                  {"type": {"inward": "is blocked by", "outward": "blocks"},
                   "inwardIssue": {"key": "MODBAR-0", "fields": {"summary": "Prep", "status": {"name": "Done"}}}}]
                """);

        UUID executionId = start("MODBAR-1");
        harness.awaitStatus(executionId, ExecutionStatus.COMPLETED);

        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).isEmpty();
        JsonNode brief = harness.metadata(executionId, "dev_task_brief.md");
        assertThat(brief.path("state").asString()).isEqualTo("INTAKE_READY");
        assertThat(brief.path("decision").path("required").asBoolean()).isFalse();
        JsonNode repository = brief.path("repository");
        assertThat(repository.path("key").asString()).isEqualTo("mod-bar");
        assertThat(repository.path("source_repo").asString()).isEqualTo("folio-org/mod-bar");
        assertThat(repository.path("base_branch").asString()).isEqualTo("master");
        assertThat(repository.path("base_sha").asString()).isEqualTo(barSha);
        assertThat(repository.path("verification_plan").asString()).isEqualTo("java-maven-test");
        assertThat(repository.path("build_image").asString()).isEqualTo("maven:3.9-eclipse-temurin-21");

        JsonNode issue = brief.path("issue");
        assertThat(issue.path("key").asString()).isEqualTo("MODBAR-1");
        assertThat(issue.path("summary").asString()).isEqualTo("Summary of MODBAR-1");
        assertThat(issue.path("description").asString()).startsWith(DESCRIPTION).endsWith("[truncated]");
        assertThat(issue.path("truncated").get(0).asString()).isEqualTo(
                "description: kept 20000 of " + longDescription.length() + " characters");
        assertThat(issue.path("comments").get(0).path("body").asString()).isEqualTo("Keep it small");
        assertThat(issue.path("links").get(0).path("key").asString()).isEqualTo("MODBAR-0");
        assertThat(issue.path("links").get(0).path("relation").asString()).isEqualTo("is blocked by");
        JsonNode codingRequest = json.readTree(harness.latest(executionId, "dev_coding_request.json"));
        assertThat(codingRequest.path("issueKey").asString()).isEqualTo("MODBAR-1");
        assertThat(codingRequest.path("comments").get(0).path("body").asString()).isEqualTo("Keep it small");
        assertThat(codingRequest.path("linkedIssues").get(0).path("key").asString()).isEqualTo("MODBAR-0");
        assertThat(codingRequest.path("acceptanceCriteria")).isEmpty();
        assertThat(codingRequest.path("repository").path("baseSha").asString()).isEqualTo(barSha);
        jira.verify(1, getRequestedFor(urlEqualTo("/rest/api/2/issue/MODBAR-1")));
        jira.verify(0, getRequestedFor(urlEqualTo("/rest/api/2/issue/MODBAR-0")));
    }

    @Test
    void emptyDescriptionIssueResolvesWithHonestCodingRequest() {
        DevFlowHarness.stubIssue(jira, "MODBAR-2", "   ", """
                "comment": {"comments": [
                  {"author": {"displayName": "PO"}, "created": "2026-09-02", "body": "Inspect the repository"}]}
                """);

        UUID executionId = start("MODBAR-2");
        harness.awaitStatus(executionId, ExecutionStatus.COMPLETED);

        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).isEmpty();
        JsonNode brief = harness.metadata(executionId, "dev_task_brief.md");
        assertThat(brief.path("state").asString()).isEqualTo("INTAKE_READY");
        JsonNode codingRequest = json.readTree(harness.latest(executionId, "dev_coding_request.json"));
        assertThat(codingRequest.path("issueKey").asString()).isEqualTo("MODBAR-2");
        assertThat(codingRequest.path("summary").asString()).isEqualTo("Summary of MODBAR-2");
        assertThat(codingRequest.path("description").asString()).isEmpty();
        assertThat(codingRequest.path("comments").get(0).path("body").asString())
                .isEqualTo("Inspect the repository");
        assertThat(codingRequest.path("acceptanceCriteria")).isEmpty();
        assertThat(codingRequest.path("repository").path("baseSha").asString()).isEqualTo(barSha);
    }

    @Test
    void unsupportedAndBlockedIssuesEndHonestlyWithoutReview() {
        UUID invalidKey = start("modbar-1; rm -rf /");
        DevFlowHarness.stubIssue(jira, "OTHER-1", DESCRIPTION, "");
        UUID unmapped = start("OTHER-1");
        DevFlowHarness.stubIssue(jira, "MODWRONG-1", DESCRIPTION, "");
        UUID missingBase = start("MODWRONG-1");

        assertOutcome(invalidKey, "UNSUPPORTED", "INVALID_ISSUE_KEY");
        assertOutcome(unmapped, "UNSUPPORTED", "NO_REPOSITORY_MAPPING");
        assertOutcome(missingBase, "BLOCKED", "BASE_BRANCH_NOT_FOUND");
        assertThat(jira.getAllServeEvents()).noneMatch(e -> e.getRequest().getUrl().contains("modbar"));
    }

    @Test
    void ambiguousIssueParksAtDecisionStepAndApproveAcceptsRecommendation() {
        DevFlowHarness.stubIssue(jira, "MODFOO-1", DESCRIPTION, "");
        UUID executionId = start("MODFOO-1");

        HitlReview review = harness.awaitPendingReview(executionId, GATE_ID);
        assertThat(review.getStepIndex()).isEqualTo(DECISION_STEP_INDEX);
        assertThat(stateManager.get(executionId).getCurrentStepIndex()).isEqualTo(DECISION_STEP_INDEX);
        assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);
        assertThat(artifactStore.getLatest(executionId, "dev_task_brief.md")).isEmpty();

        JsonNode reviewPackage = json.readTree(review.getReviewPackage());
        assertThat(reviewPackage.path("flowId").asString()).isEqualTo("dev-factory");
        assertThat(reviewPackage.path("artifacts").findValuesAsString("name"))
                .containsExactly("dev_decision_answer.md", "dev_decision_request.md", "dev_intake.md");
        assertThat(rest.get().uri("/reviews/" + review.getId()).retrieve().body(String.class))
                .contains("Developer Flow: choose the repository").contains("dev_decision_answer.md");

        JsonNode decided = rest.post().uri("/api/hitl/reviews/" + review.getId() + "/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("decision", "APPROVE", "reviewer", "dev-lead"))
                .retrieve().body(JsonNode.class);
        assertThat(decided.path("status").asString()).isEqualTo("APPROVED");

        harness.awaitStatus(executionId, ExecutionStatus.COMPLETED);
        JsonNode brief = harness.metadata(executionId, "dev_task_brief.md");
        assertThat(brief.path("state").asString()).isEqualTo("INTAKE_READY");
        assertThat(brief.path("repository").path("key").asString()).isEqualTo("mod-foo");
        assertThat(brief.path("repository").path("base_sha").asString()).isEqualTo(fooSha);
        assertThat(brief.path("decision").path("choice").asString()).isEqualTo("mod-foo");
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).hasSize(1);
    }

    @Test
    void amendedAnswerIsUsedAfterResume() {
        DevFlowHarness.stubIssue(jira, "MODFOO-2", DESCRIPTION, "");
        UUID executionId = start("MODFOO-2");
        HitlReview review = harness.awaitPendingReview(executionId, GATE_ID);

        String answer = harness.latest(executionId, "dev_decision_answer.md");
        decisionService.decide(review.getId(), HitlDecision.AMEND, "dev-lead", "storage module",
                Map.of("dev_decision_answer.md", answer.replace("choice: \"mod-foo\"", "choice: \"mod-foo-storage\"")));

        harness.awaitStatus(executionId, ExecutionStatus.COMPLETED);
        JsonNode brief = harness.metadata(executionId, "dev_task_brief.md");
        assertThat(brief.path("repository").path("key").asString()).isEqualTo("mod-foo-storage");
        assertThat(brief.path("repository").path("base_branch").asString()).isEqualTo("main");
        assertThat(brief.path("repository").path("base_sha").asString()).isEqualTo(fooStorageSha);
        assertThat(brief.path("decision").path("choice").asString()).isEqualTo("mod-foo-storage");
        assertThat(brief.path("decision").path("recommended").asString()).isEqualTo("mod-foo");
        assertThat(artifactStore.getLatest(executionId, "dev_decision_answer.md").orElseThrow().getCreatedBy())
                .isEqualTo("hitl:dev-lead");
    }

    @Test
    void invalidAmendmentsAreRejectedAndStaleAnswerFailsTheStep() {
        DevFlowHarness.stubIssue(jira, "MODFOO-3", DESCRIPTION, "");
        UUID executionId = start("MODFOO-3");
        HitlReview review = harness.awaitPendingReview(executionId, GATE_ID);
        String answer = harness.latest(executionId, "dev_decision_answer.md");

        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.AMEND, "dev-lead", null,
                Map.of("dev_decision_answer.md", answer.replace("choice: \"mod-foo\"", "choice: \"evil/repo\""))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a configured repository");
        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.AMEND, "dev-lead", null,
                Map.of("dev_decision_answer.md", answer.replace("choice:", "base_sha: \"abc\"\nchoice:"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must contain exactly");
        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.AMEND, "dev-lead", null,
                Map.of("dev_intake.md", "---\nstate: \"MAPPED\"\n---\n")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be amended");
        assertThat(reviews.findById(review.getId()).orElseThrow().getStatus()).isEqualTo(HitlReviewStatus.PENDING);
        assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);

        String stale = answer.replaceAll("request_id: \"[0-9a-f]{64}\"", "request_id: \"" + "0".repeat(64) + "\"");
        decisionService.decide(review.getId(), HitlDecision.AMEND, "dev-lead", null,
                Map.of("dev_decision_answer.md", stale));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(auditLog.forExecution(executionId).stream()
                        .filter(e -> e.getEventType() == AuditEventType.STEP_FAILED)
                        .map(e -> e.getDetail()))
                        .anyMatch(detail -> detail.contains("Decision answer is stale")));
        assertThat(artifactStore.getLatest(executionId, "dev_task_brief.md")).isEmpty();
        assertThat(stateManager.get(executionId).getStatus()).isNotEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    void duplicateDecisionAndDuplicateGateAttemptDoNotDuplicateProgress() {
        DevFlowHarness.stubIssue(jira, "MODFOO-4", DESCRIPTION, "");
        UUID executionId = start("MODFOO-4");
        HitlReview review = harness.awaitPendingReview(executionId, GATE_ID);

        AgentContext repeat = new AgentContext(executionId, "select-repository",
                Map.of("dev_decision_request.md", new ArtifactContent(
                        "dev_decision_request.md", 1, "text/markdown", harness.latest(executionId, "dev_decision_request.md"))),
                null, Map.of(), List.of());
        assertThat(decisionGateWorker.execute(repeat).metrics()).containsEntry("reviewOpened", false);
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).hasSize(1);

        decisionService.decide(review.getId(), HitlDecision.APPROVE, "dev-lead", null, null);
        assertThatThrownBy(() -> decisionService.decide(review.getId(), HitlDecision.APPROVE, "dev-lead", null, null))
                .isInstanceOf(IllegalStateException.class);

        harness.awaitStatus(executionId, ExecutionStatus.COMPLETED);
        assertThat(decisionGateWorker.execute(repeat).metrics()).containsEntry("reviewOpened", false);
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).hasSize(1);
        assertThat(stateManager.get(executionId).getCurrentStepIndex()).isEqualTo(10);
        assertThat(artifactStore.getLatest(executionId, "dev_task_brief.md").orElseThrow().getVersion()).isEqualTo(1);
        assertThat(auditLog.forExecution(executionId).stream()
                .filter(e -> e.getEventType() == AuditEventType.STEP_COMPLETED)
                .map(e -> e.getStepId()))
                .containsExactly("read-task", "select-repository", "prepare-task", "implement",
                        "clarify-implementation", "continue-implementation", "verify", "publish",
                        "initialize-pr-feedback", "pr-follow-up");
    }

    @Test
    void rejectionStopsTheExecutionAndOtherFlowsAreUnaffected() {
        DevFlowHarness.stubIssue(jira, "MODFOO-5", DESCRIPTION, "");
        UUID executionId = start("MODFOO-5");
        HitlReview review = harness.awaitPendingReview(executionId, GATE_ID);

        decisionService.decide(review.getId(), HitlDecision.REJECT, "dev-lead", "not now", null);

        assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(stateManager.get(executionId).getCurrentStepIndex()).isEqualTo(DECISION_STEP_INDEX);
        assertThat(artifactStore.getLatest(executionId, "dev_task_brief.md")).isEmpty();
        assertThat(flowRegistry.find("test-factory")).isPresent();
        assertThatCode(() -> devValidator.validate("test_plan.md", "no frontmatter at all"))
                .doesNotThrowAnyException();
    }

    private UUID start(String issueKey) {
        return router.routeManual("dev-factory", json.readTree(json.writeValueAsString(Map.of("issueKey", issueKey))));
    }

    private void assertOutcome(UUID executionId, String state, String reason) {
        harness.awaitStatus(executionId, ExecutionStatus.COMPLETED);
        JsonNode brief = harness.metadata(executionId, "dev_task_brief.md");
        assertThat(brief.path("state").asString()).isEqualTo(state);
        assertThat(brief.path("reason").asString()).isEqualTo(reason);
        assertThat(brief.path("repository").isMissingNode()).isTrue();
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).isEmpty();
    }
}
