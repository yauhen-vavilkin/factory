package org.folio.factory.devfactory.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.devfactory.contract.ResolvedIntent;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.inbox.FileInboxTrigger;
import org.folio.factory.devfactory.profile.TrustedProfileCatalog;
import org.folio.factory.devfactory.resolution.RepositoryAccess;
import org.folio.factory.devfactory.resolution.RepositoryCatalog;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class DecisionWorkerTest {
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private final JsonMapper json = JsonMapper.builder().build();
  private final HitlReviewRepository reviews = mock(HitlReviewRepository.class);
  private final TaskResolutionService resolver = new TaskResolutionService(new RepositoryCatalog(),
      new FakeAccess(), new TrustedProfileCatalog());
  private final DecisionWorker requestWorker = new DecisionWorker(DecisionWorker.REQUEST_WORKER, resolver, reviews);
  private final DecisionWorker resumeWorker = new DecisionWorker(DecisionWorker.RESUME_WORKER, resolver, reviews);
  private final UUID executionId = UUID.randomUUID();

  @Test
  void requestIsACompleteDecisionInterfaceWithoutModelCalls() {
    AgentResult result = request(declaredPayload(null));
    JsonNode request = json.readTree(result.outputs().get(DecisionArtifacts.REQUEST));
    JsonNode answer = json.readTree(result.outputs().get(DecisionArtifacts.ANSWER));

    assertThat(request.path("outcome").asString()).isEqualTo("NEEDS_DECISION");
    assertThat(request.path("decisionId").asString()).isEqualTo(executionId + "/limit");
    assertThat(request.path("options")).hasSize(2);
    assertThat(request.path("facts").toString()).contains("maxItems", SHA);
    assertThat(request.path("references").path("verificationPlanId").asString()).isNotBlank();
    assertThat(request.path("recommendation").isNull()).isTrue();
    assertThat(answer.path("status").asString()).isEqualTo("UNANSWERED");
    assertThat(answer.path("requestSha256").asString())
        .isEqualTo(DecisionArtifacts.requestSha256(result.outputs().get(DecisionArtifacts.REQUEST)));
  }

  @Test
  void answeredDecisionResumesWithRetainedResolutionAndBindsTheAnswerIntoTheTask() {
    JsonNode payload = declaredPayload(null);
    String request = request(payload).outputs().get(DecisionArtifacts.REQUEST);
    String answer = answer(request, "raise", null);

    AgentResult resumed = resume(payload, request, answer);

    JsonNode task = json.readTree(resumed.outputs().get(DecisionArtifacts.TASK));
    JsonNode resolution = json.readTree(resumed.outputs().get(DecisionArtifacts.RESOLUTION));
    assertThat(task.path("baseRevision").asString()).isEqualTo(payload.path("baseRevision").asString());
    assertThat(task.path("resolvedIntent").path("status").asString()).isEqualTo("RESOLVED");
    assertThat(task.path("goal").asString()).isEqualTo(payload.path("goal").asString());
    assertThat(task.path("acceptanceCriteria").toString()).contains("DECISION-limit", "Raise to 100",
        "HUMAN_DECISION");
    assertThat(task.path("decisionAnswers").get(0).path("answeredBy").asString()).isEqualTo("operator");
    assertThat(resolution.path("resolutionMode").asString()).isEqualTo("SELECTED_OPTION");
    assertThat(resolution.path("investigationRepeated").asString()).isEqualTo("no");
    assertThat(resolution.path("retained").toString()).contains("admission resolution");
    assertThat(resolution.path("invalidated").toString()).contains("no such evidence exists before the decision");
  }

  @Test
  void staleOrForeignAnswersNeverResume() {
    JsonNode payload = declaredPayload(null);
    String request = request(payload).outputs().get(DecisionArtifacts.REQUEST);

    ObjectNode otherDecision = (ObjectNode) json.readTree(answer(request, "raise", null));
    otherDecision.put("decisionId", executionId + "/older");
    ObjectNode otherExecution = (ObjectNode) json.readTree(answer(request, "raise", null));
    otherExecution.put("executionId", UUID.randomUUID().toString());
    ObjectNode otherRequestVersion = (ObjectNode) json.readTree(answer(request, "raise", null));
    otherRequestVersion.put("requestSha256", "0".repeat(64));

    for (ObjectNode stale : List.of(otherDecision, otherExecution, otherRequestVersion)) {
      assertThatThrownBy(() -> resume(payload, request, json.writeValueAsString(stale)))
          .isInstanceOf(AgentExecutionException.class).hasMessageContaining("DECISION_ANSWER_MISMATCH");
    }
    assertThatThrownBy(() -> resume(payload, request, answer(request, "unknown", null)))
        .hasMessageContaining("not an option");
  }

  @Test
  void approvalWithoutAnswerUsesOnlyAnEvidenceBackedRecommendation() {
    JsonNode payload = declaredPayload(null);
    String request = request(payload).outputs().get(DecisionArtifacts.REQUEST);
    String unanswered = request(payload).outputs().get(DecisionArtifacts.ANSWER);
    assertThatThrownBy(() -> resume(payload, request, unanswered))
        .hasMessageContaining("DECISION_ANSWER_MISSING");

    JsonNode recommended = declaredPayload("raise");
    AgentResult requested = request(recommended);
    HitlReview approved = new HitlReview(executionId, DecisionArtifacts.GATE_ID, 1, "{}");
    approved.decide(HitlReviewStatus.APPROVED, "APPROVE", "lead", "agree", null);
    when(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId)).thenReturn(List.of(approved));

    JsonNode resolution = json.readTree(resume(recommended, requested.outputs().get(DecisionArtifacts.REQUEST),
        requested.outputs().get(DecisionArtifacts.ANSWER)).outputs().get(DecisionArtifacts.RESOLUTION));
    assertThat(resolution.path("resolutionMode").asString()).isEqualTo("APPROVED_RECOMMENDATION");
    assertThat(resolution.path("selectedOptionId").asString()).isEqualTo("raise");
    assertThat(resolution.path("answeredBy").asString()).isEqualTo("lead");
  }

  @Test
  void repositorySelectionInvalidatesAdmissionScopeAndReResolvesTheSelectedRepository() {
    TaskRequest conflicting = task("folio-org/folio-module-sidecar", "MGRENTITLE", List.of());
    ResolvedIntent intent = resolver.resolve(conflicting);
    assertThat(intent.status()).isEqualTo(ResolvedIntent.NEEDS_DECISION);
    JsonNode payload = FileInboxTrigger.payloadFor(intent);
    String request = request(payload).outputs().get(DecisionArtifacts.REQUEST);
    assertThatThrownBy(() -> resume(payload, request, answer(request, null, "whichever is fine")))
        .hasMessageContaining("must choose one of the candidate options");

    AgentResult resumed = resume(payload, request, answer(request, "folio-org/mgr-tenant-entitlements", null));

    JsonNode task = json.readTree(resumed.outputs().get(DecisionArtifacts.TASK));
    JsonNode resolution = json.readTree(resumed.outputs().get(DecisionArtifacts.RESOLUTION));
    assertThat(payload.path("repoUrl").isNull()).isTrue();
    assertThat(task.path("repoUrl").asString()).isEqualTo("https://github.com/folio-org/mgr-tenant-entitlements.git");
    assertThat(task.path("baseRevision").asString()).isEqualTo(SHA);
    assertThat(task.path("resolvedIntent").path("profile").isObject()).isTrue();
    assertThat(task.path("constraints").path("checks")).isNotEmpty();
    assertThat(resolution.path("invalidated").toString()).contains("admission-time repository candidates",
        "re-resolved for folio-org/mgr-tenant-entitlements");
  }

  @Test
  void verificationPlanSelectionResolvesTheChosenTrustedPlanAtTheAdmittedRevision() {
    ResolvedIntent intent = resolver.resolve(task(null, "MGRENTITLE", List.of(), null));
    assertThat(intent.code()).isEqualTo(DecisionArtifacts.VERIFICATION_PLAN);
    JsonNode payload = FileInboxTrigger.payloadFor(intent);
    assertThat(payload.path("constraints").path("checks")).isEmpty();
    String request = request(payload).outputs().get(DecisionArtifacts.REQUEST);
    assertThatThrownBy(() -> resume(payload, request, answer(request, null, "unit tests are enough")))
        .hasMessageContaining("must choose one of the trusted plans");

    AgentResult resumed = resume(payload, request, answer(request, TrustedProfileCatalog.JAVA_MAVEN_VERIFY_IT, null));

    JsonNode task = json.readTree(resumed.outputs().get(DecisionArtifacts.TASK));
    assertThat(task.path("baseRevision").asString()).isEqualTo(SHA);
    assertThat(task.path("resolvedIntent").path("status").asString()).isEqualTo("RESOLVED");
    assertThat(task.path("resolvedIntent").path("verificationPlan").path("id").asString())
        .isEqualTo(TrustedProfileCatalog.JAVA_MAVEN_VERIFY_IT);
    assertThat(task.path("constraints").path("checks").get(0).path("command").asString())
        .isEqualTo("mvn -B -ntp clean verify");
    assertThat(json.readTree(resumed.outputs().get(DecisionArtifacts.RESOLUTION))
        .path("effectiveTask").path("verificationPlanId").asString())
        .isEqualTo(TrustedProfileCatalog.JAVA_MAVEN_VERIFY_IT);
  }

  @Test
  void anotherDecisionCannotResumeATaskWhosePlanIsUnresolved() {
    TaskRequest.DeclaredDecision missing = new TaskRequest.DeclaredDecision("jira-task-requirements",
        "TASK_REQUIREMENTS_MISSING", "What must the change do?", "No requirements", List.of(), null, null,
        List.of());
    JsonNode payload = FileInboxTrigger.payloadFor(resolver.resolve(task(null, "MGRENTITLE", List.of(missing),
        null)));
    String request = request(payload).outputs().get(DecisionArtifacts.REQUEST);

    assertThatThrownBy(() -> resume(payload, request, answer(request, null, "Raise the limit to 100")))
        .hasMessageContaining("VERIFICATION_PLAN_UNRESOLVED");
  }

  private AgentResult request(JsonNode payload) {
    return requestWorker.execute(new AgentContext(executionId, "decision-request", Map.of(), payload, Map.of(),
        List.of(DecisionArtifacts.REQUEST, DecisionArtifacts.ANSWER)));
  }

  private AgentResult resume(JsonNode payload, String request, String answer) {
    Map<String, ArtifactContent> inputs = new LinkedHashMap<>();
    inputs.put(DecisionArtifacts.REQUEST, new ArtifactContent(DecisionArtifacts.REQUEST, 1, "application/json", request));
    inputs.put(DecisionArtifacts.ANSWER, new ArtifactContent(DecisionArtifacts.ANSWER, 2, "application/json", answer));
    return resumeWorker.execute(new AgentContext(executionId, "decision-resume", inputs, payload, Map.of(),
        List.of(DecisionArtifacts.TASK, DecisionArtifacts.RESOLUTION)));
  }

  private String answer(String request, String option, String freeText) {
    return json.writeValueAsString(DecisionArtifacts.answer(json.readTree(request), request, option, freeText,
        "operator", null));
  }

  private JsonNode declaredPayload(String recommended) {
    TaskRequest.DeclaredDecision declared = new TaskRequest.DeclaredDecision("limit", "PRODUCT_SEMANTICS",
        "Raise the limit or remove it?", "External API contract", List.of(
            new TaskRequest.Option("raise", "Raise to 100", "Bounded"),
            new TaskRequest.Option("remove", "Remove", "Unbounded")), recommended,
        recommended == null ? null : "bounded is safer", List.of(new TaskRequest.Evidence("schema.json",
            List.of("maxItems"))));
    ResolvedIntent intent = resolver.resolve(task(null, "MGRENTITLE", List.of(declared)));
    assertThat(intent.status()).isEqualTo(ResolvedIntent.NEEDS_DECISION);
    return FileInboxTrigger.payloadFor(intent);
  }

  private TaskRequest task(String repository, String project, List<TaskRequest.DeclaredDecision> decisions) {
    return task(repository, project, decisions, TrustedProfileCatalog.JAVA_MAVEN_VERIFY);
  }

  private TaskRequest task(String repository, String project, List<TaskRequest.DeclaredDecision> decisions,
                           String plan) {
    return new TaskRequest(1, new TaskRequest.SourceIdentity("JIRA", "ANY-1", project, null), repository, SHA,
        null, null, plan, "default", "LOCAL_ONLY", json.createObjectNode(), "Change the limit",
        List.of(new TaskRequest.AcceptanceCriterion("AC-1", "Limit changed", "TASK")), json.createObjectNode(),
        null, "raw", false, decisions);
  }

  private static final class FakeAccess implements RepositoryAccess {
    @Override public String resolveBranch(String slug, String branch) {
      return SHA;
    }

    @Override public String verifyCommit(String slug, String sha) {
      return sha;
    }

    @Override public Optional<byte[]> readFile(String slug, String sha, String path, int maxBytes) {
      return switch (path) {
        case "pom.xml" -> Optional.of("<project><properties><java.version>21</java.version></properties></project>"
            .getBytes(StandardCharsets.UTF_8));
        case "schema.json" -> Optional.of("{\"maxItems\": 25}".getBytes(StandardCharsets.UTF_8));
        default -> Optional.empty();
      };
    }
  }
}
