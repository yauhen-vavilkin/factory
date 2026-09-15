package org.folio.factory.app.web;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.decision.DecisionArtifacts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operator boundary for a Developer Flow NEEDS_DECISION: shows the pending
 * request and records the smallest answer (one option id or a short free-text
 * answer) through the core HITL decision service as an attributed AMEND of
 * {@code decision-answer.json}. The execution comes from the URL and the named
 * decision id must be the active one, so a stale answer is refused before it
 * can resume anything.
 */
@RestController
@RequestMapping("/api/dev/executions/{executionId}/decision")
public class DevDecisionController {
  private final HitlReviewRepository reviews;
  private final HitlDecisionService decisions;
  private final ArtifactStore artifacts;
  private final JsonMapper json;

  public DevDecisionController(HitlReviewRepository reviews, HitlDecisionService decisions,
                               ArtifactStore artifacts, JsonMapper json) {
    this.reviews = reviews;
    this.decisions = decisions;
    this.artifacts = artifacts;
    this.json = json;
  }

  public record AnswerRequest(String decisionId, String selectedOptionId, String freeText,
                              String reviewer, String comment) {
  }

  @GetMapping
  public Map<String, Object> pending(@PathVariable("executionId") UUID executionId) {
    HitlReview review = pendingReview(executionId);
    return Map.of("reviewId", review.getId(), "request", json.readTree(requestContent(executionId)));
  }

  @PostMapping
  public Map<String, Object> answer(@PathVariable("executionId") UUID executionId,
                                    @RequestBody AnswerRequest body) {
    if (body.reviewer() == null || body.reviewer().isBlank()) {
      throw new IllegalArgumentException("reviewer is required");
    }
    HitlReview review = pendingReview(executionId);
    String requestContent = requestContent(executionId);
    JsonNode request = json.readTree(requestContent);
    if (body.decisionId() == null || !body.decisionId().equals(request.path("decisionId").asString())) {
      throw new IllegalStateException("decision '" + body.decisionId() + "' is not the active decision of execution "
          + executionId + " ('" + request.path("decisionId").asString() + "')");
    }
    JsonNode answer = DecisionArtifacts.answer(request, requestContent, body.selectedOptionId(), body.freeText(),
        body.reviewer(), body.comment());
    DecisionArtifacts.requireAnswerMatches(answer, executionId, requestContent);
    decisions.decide(review.getId(), HitlDecision.AMEND, body.reviewer(), body.comment(),
        Map.of(DecisionArtifacts.ANSWER, json.writeValueAsString(answer)));
    return Map.of("reviewId", review.getId(), "executionId", executionId, "answer", answer);
  }

  private HitlReview pendingReview(UUID executionId) {
    return reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
        .filter(review -> review.getStatus() == HitlReviewStatus.PENDING
            && DecisionArtifacts.GATE_ID.equals(review.getGateId()))
        .reduce((first, second) -> second)
        .orElseThrow(() -> new NoSuchElementException("execution " + executionId
            + " has no pending developer decision"));
  }

  private String requestContent(UUID executionId) {
    return artifacts.getLatest(executionId, DecisionArtifacts.REQUEST)
        .orElseThrow(() -> new NoSuchElementException("execution " + executionId + " has no decision request"))
        .getContent();
  }
}
