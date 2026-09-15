package org.folio.factory.devfactory.decision;

import java.util.UUID;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.ArtifactAmendmentValidator;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rejects a decision answer before the HITL decision commits unless it answers
 * the active request of an execution that is still waiting at the decision
 * gate. The core validator hook sees only the artifact, so the execution is
 * taken from the answer itself; the resuming step re-checks the answer against
 * its own execution, which closes the remaining cross-posting gap fail-closed.
 */
public final class DecisionAnswerValidator implements ArtifactAmendmentValidator {
  private final ArtifactStore artifacts;
  private final HitlReviewRepository reviews;
  private final JsonMapper json = JsonMapper.builder().build();

  public DecisionAnswerValidator(ArtifactStore artifacts, HitlReviewRepository reviews) {
    this.artifacts = artifacts;
    this.reviews = reviews;
  }

  @Override
  public void validate(String artifactName, String content) {
    if (!DecisionArtifacts.ANSWER.equals(artifactName)) {
      return;
    }
    JsonNode answer;
    try {
      answer = json.readTree(content);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("decision answer is not valid JSON");
    }
    UUID executionId;
    try {
      executionId = UUID.fromString(answer.path("executionId").asString(""));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("decision answer must name a valid executionId");
    }
    boolean waiting = reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
        .anyMatch(review -> review.getStatus() == HitlReviewStatus.PENDING
            && DecisionArtifacts.GATE_ID.equals(review.getGateId()));
    if (!waiting) {
      throw new IllegalArgumentException("execution " + executionId + " has no pending developer decision");
    }
    String request = artifacts.getLatest(executionId, DecisionArtifacts.REQUEST)
        .orElseThrow(() -> new IllegalArgumentException("execution " + executionId + " has no decision request"))
        .getContent();
    DecisionArtifacts.requireAnswerMatches(answer, executionId, request);
  }
}
