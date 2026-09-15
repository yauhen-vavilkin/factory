package org.folio.factory.devfactory.decision;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.contract.ResolvedIntent;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.inbox.FileInboxTrigger;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two deterministic ends of a NEEDS_DECISION pause in the Developer Flow.
 * The request end turns the resolved decision into a durable request before
 * the flow's HITL gate pauses the execution; the resume end, after the gate,
 * binds the human answer into the effective task so the unchanged coding and
 * verification steps continue from it. Neither end calls a model.
 */
public final class DecisionWorker implements AgentWorker {
  public static final String REQUEST_WORKER = "dev-decision-request-worker";
  public static final String RESUME_WORKER = "dev-decision-resume-worker";

  private final String id;
  private final TaskResolutionService resolver;
  private final HitlReviewRepository reviews;
  private final JsonMapper json = JsonMapper.builder().build();

  public DecisionWorker(String id, TaskResolutionService resolver, HitlReviewRepository reviews) {
    this.id = id;
    this.resolver = resolver;
    this.reviews = reviews;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public AgentResult execute(AgentContext context) {
    return REQUEST_WORKER.equals(id) ? request(context) : resume(context);
  }

  private AgentResult request(AgentContext context) {
    JsonNode payload = context.triggerPayload();
    JsonNode resolved = payload.path("resolvedIntent");
    JsonNode decision = resolved.path("decision");
    if (!ResolvedIntent.NEEDS_DECISION.equals(resolved.path("status").asString(""))
        || !decision.isObject()) {
      throw new AgentExecutionException("decision flow admitted a task without a pending decision");
    }
    String decisionId = DecisionArtifacts.decisionId(context.executionId(), decision.path("id").asString());
    ObjectNode request = json.createObjectNode();
    request.put("schema", DecisionArtifacts.REQUEST_SCHEMA);
    request.put("outcome", ResolvedIntent.NEEDS_DECISION);
    request.put("executionId", context.executionId().toString());
    request.put("taskId", payload.path("taskId").asString());
    request.put("runKey", payload.path("runKey").asString());
    request.put("decisionId", decisionId);
    request.put("category", decision.path("category").asString());
    request.put("question", decision.path("question").asString());
    request.put("whyItMatters", decision.path("whyItMatters").asString());
    request.set("options", decision.path("options").deepCopy());
    String recommended = decision.path("recommendedOptionId").asString(null);
    if (recommended == null) {
      request.putNull("recommendation");
    } else {
      request.putObject("recommendation").put("optionId", recommended)
          .put("rationale", decision.path("recommendationRationale").asString(""));
    }
    request.set("facts", decision.path("facts").deepCopy());
    request.put("requiredInput", decision.path("requiredInput").asString());
    request.put("howToAnswer", "./scripts/factory decide " + context.executionId()
        + " --decision-id " + decisionId + " --option <optionId> --reviewer <name>"
        + " (or --free-text '<answer>'); REJECT the review to cancel the task");
    ObjectNode references = request.putObject("references");
    references.put("provenance", decision.path("provenance").asString());
    references.put("semanticTaskHash", payload.path("semanticTaskHash").asString());
    references.put("intentHash", resolved.path("intentHash").asString());
    references.put("repository", resolved.path("repository").path("canonicalSlug").asString(null));
    references.set("repositoryCandidates", resolved.path("repository").path("candidates").deepCopy());
    references.put("exactRevision", resolved.path("repository").path("exactRevision").asString(null));
    references.put("verificationPlanId", resolved.path("verificationPlan").path("id").asString(null));
    request.put("requestedAt", Instant.now().toString());
    String requestContent = json.writeValueAsString(request);
    String answer = json.writeValueAsString(DecisionArtifacts.unanswered(context.executionId(), decisionId,
        DecisionArtifacts.requestSha256(requestContent)));
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("outcome", ResolvedIntent.NEEDS_DECISION);
    metrics.put("decision_id", decisionId);
    return new AgentResult(Map.of(DecisionArtifacts.REQUEST, requestContent, DecisionArtifacts.ANSWER, answer),
        metrics);
  }

  private AgentResult resume(AgentContext context) {
    JsonNode payload = context.triggerPayload();
    String requestContent = context.requireInput(DecisionArtifacts.REQUEST).content();
    String answerContent = context.requireInput(DecisionArtifacts.ANSWER).content();
    JsonNode request = json.readTree(requestContent);
    ObjectNode answer = (ObjectNode) json.readTree(answerContent);
    String mode;
    if ("UNANSWERED".equals(answer.path("status").asString(""))) {
      answer = approvedRecommendation(context, request, requestContent)
          .orElseThrow(() -> new AgentExecutionException("DECISION_ANSWER_MISSING: the decision gate was "
              + "approved without an answer and the request has no recommendation; supply "
              + DecisionArtifacts.ANSWER + " (AMEND) to resume"));
      mode = "APPROVED_RECOMMENDATION";
    } else {
      mode = answer.path("selectedOptionId").asString("").isBlank() ? "FREE_TEXT" : "SELECTED_OPTION";
    }
    try {
      DecisionArtifacts.requireAnswerMatches(answer, context.executionId(), requestContent);
    } catch (IllegalArgumentException mismatch) {
      throw new AgentExecutionException("DECISION_ANSWER_MISMATCH: " + mismatch.getMessage());
    }

    String selectedId = answer.path("selectedOptionId").asString("");
    JsonNode option = selectedId.isBlank() ? null : DecisionArtifacts.option(request, selectedId);
    boolean repositorySelection = DecisionArtifacts.REPOSITORY_SELECTION.equals(request.path("category").asString());
    ObjectNode task;
    ArrayNode retained = json.createArrayNode();
    ArrayNode invalidated = json.createArrayNode();
    retained.add("original task text, goal and acceptance criteria (trigger payload)");
    retained.add("decision facts and options (" + DecisionArtifacts.REQUEST + ")");
    if (repositorySelection) {
      TaskRequest original = json.treeToValue(payload.path("resolvedIntent").path("task"), TaskRequest.class);
      ResolvedIntent selected = resolver.resolveSelectedRepository(original, selectedId);
      if (!"RESOLVED".equals(selected.status())) {
        throw new AgentExecutionException("RESOLUTION_BLOCKED: selected repository " + selectedId
            + " cannot run this task: " + selected.code() + " " + selected.message());
      }
      task = FileInboxTrigger.payloadFor(selected);
      invalidated.add("admission-time repository candidates " + request.path("references")
          .path("repositoryCandidates") + ": replaced by the selected repository");
      invalidated.add("admission-time unresolved revision, profile and verification plan: re-resolved for "
          + selectedId + "@" + selected.repository().exactRevision());
    } else {
      task = (ObjectNode) payload.deepCopy();
      ((ObjectNode) task.path("resolvedIntent")).put("status", "RESOLVED");
      retained.add("admission resolution: repository " + request.path("references").path("repository").asString()
          + "@" + request.path("references").path("exactRevision").asString() + ", execution profile, "
          + "verification plan " + request.path("references").path("verificationPlanId").asString()
          + " (the answer does not change repository, revision or plan)");
    }
    invalidated.add("none of preparation, baseline, coding or verification evidence: no such evidence exists "
        + "before the decision; all of it is produced after resume for the effective task");

    String decisionText = option == null
        ? "Human decision on \"" + request.path("question").asString() + "\": "
            + answer.path("freeText").asString()
        : "Human decision on \"" + request.path("question").asString() + "\": " + option.path("label").asString()
            + ". Consequence accepted: " + option.path("consequence").asString();
    String criterionId = "DECISION-" + request.path("decisionId").asString()
        .substring(request.path("decisionId").asString().indexOf('/') + 1);
    ((ArrayNode) task.withArray("acceptance")).add(decisionText);
    ((ArrayNode) task.withArray("acceptanceCriteria")).addObject()
        .put("id", criterionId).put("text", decisionText).put("source", "HUMAN_DECISION");
    ObjectNode decisionEntry = ((ArrayNode) task.withArray("decisionAnswers")).addObject();
    decisionEntry.put("decisionId", request.path("decisionId").asString());
    decisionEntry.put("question", request.path("question").asString());
    decisionEntry.put("answer", option == null ? answer.path("freeText").asString() : option.path("label").asString());
    if (option != null) {
      decisionEntry.put("selectedOptionId", selectedId);
    }
    decisionEntry.put("answeredBy", answer.path("answeredBy").asString());

    ObjectNode resolution = json.createObjectNode();
    resolution.put("schema", "DevFlowDecisionResolution/v1");
    resolution.put("executionId", context.executionId().toString());
    resolution.put("decisionId", request.path("decisionId").asString());
    resolution.put("category", request.path("category").asString());
    resolution.put("resolutionMode", mode);
    resolution.put("selectedOptionId", option == null ? null : selectedId);
    resolution.put("freeText", option == null ? answer.path("freeText").asString() : null);
    resolution.put("answeredBy", answer.path("answeredBy").asString());
    resolution.put("answeredAt", answer.path("answeredAt").asString());
    resolution.put("requestSha256", DecisionArtifacts.requestSha256(requestContent));
    resolution.put("answerSha256", ArtifactStore.sha256(answerContent));
    resolution.put("resumedAt", Instant.now().toString());
    resolution.put("resumesAt", "prepare");
    resolution.put("investigationRepeated", repositorySelection
        ? "deterministic resolution re-run for the selected repository only" : "no");
    ObjectNode effective = resolution.putObject("effectiveTask");
    effective.put("repository", task.path("resolvedIntent").path("repository").path("canonicalSlug").asString());
    effective.put("exactRevision", task.path("baseRevision").asString());
    effective.put("verificationPlanId", task.path("resolvedIntent").path("verificationPlan").path("id").asString());
    effective.put("decisionCriterionId", criterionId);
    resolution.set("retained", retained);
    resolution.set("invalidated", invalidated);
    task.set("decisionResolution", json.createObjectNode()
        .put("artifact", DecisionArtifacts.RESOLUTION).put("decisionId", request.path("decisionId").asString()));
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("decision_id", request.path("decisionId").asString());
    metrics.put("resolution_mode", mode);
    return new AgentResult(Map.of(DecisionArtifacts.TASK, json.writeValueAsString(task),
        DecisionArtifacts.RESOLUTION, json.writeValueAsString(resolution)), metrics);
  }

  /** APPROVE without an answer means "accept the recommendation", attributed to the approving reviewer. */
  private Optional<ObjectNode> approvedRecommendation(AgentContext context, JsonNode request, String requestContent) {
    String recommended = request.path("recommendation").path("optionId").asString("");
    if (recommended.isBlank()) {
      return Optional.empty();
    }
    return reviews.findByExecutionIdOrderByCreatedAtAsc(context.executionId()).stream()
        .filter(review -> DecisionArtifacts.GATE_ID.equals(review.getGateId())
            && review.getStatus() == HitlReviewStatus.APPROVED)
        .max(Comparator.comparing(HitlReview::getDecidedAt))
        .map(review -> DecisionArtifacts.answer(request, requestContent, recommended, null,
            review.getReviewer(), review.getComments()));
  }
}
