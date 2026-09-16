package org.folio.factory.devfactory.decision;

import java.time.Instant;
import java.util.UUID;
import org.folio.factory.core.service.ArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The NEEDS_DECISION interface between Developer Flow and a human: the request
 * artifact written before the pause, the answer artifact the human supplies
 * through the existing HITL gate (AMEND), and the one validation rule both the
 * amendment validator and the resuming step apply.
 */
public final class DecisionArtifacts {
  public static final String GATE_ID = "developer-decision";
  public static final String REQUEST = "decision-request.json";
  public static final String ANSWER = "decision-answer.json";
  public static final String RESOLUTION = "decision-resolution.json";
  public static final String TASK = "task.json";
  public static final String REQUEST_SCHEMA = "DevFlowDecisionRequest/v1";
  public static final String ANSWER_SCHEMA = "DevFlowDecisionAnswer/v1";
  public static final String REPOSITORY_SELECTION = "REPOSITORY_SELECTION";
  public static final String VERIFICATION_PLAN = "VERIFICATION_PLAN";
  private static final int MAX_FREE_TEXT = 2000;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private DecisionArtifacts() {
  }

  /** Decision ids are execution-scoped so an answer for another execution can never match. */
  public static String decisionId(UUID executionId, String localId) {
    return executionId + "/" + localId;
  }

  public static String requestSha256(String requestContent) {
    return ArtifactStore.sha256(requestContent);
  }

  public static ObjectNode unanswered(UUID executionId, String decisionId, String requestSha256) {
    ObjectNode answer = JSON.createObjectNode();
    answer.put("schema", ANSWER_SCHEMA);
    answer.put("status", "UNANSWERED");
    answer.put("executionId", executionId.toString());
    answer.put("decisionId", decisionId);
    answer.put("requestSha256", requestSha256);
    return answer;
  }

  /** The smallest attributable answer: one option id, or a short free-text answer. */
  public static ObjectNode answer(JsonNode request, String requestContent, String selectedOptionId,
                                  String freeText, String answeredBy, String comment) {
    ObjectNode answer = JSON.createObjectNode();
    answer.put("schema", ANSWER_SCHEMA);
    answer.put("status", "ANSWERED");
    answer.put("executionId", request.path("executionId").asString());
    answer.put("decisionId", request.path("decisionId").asString());
    answer.put("requestSha256", requestSha256(requestContent));
    putNullable(answer, "selectedOptionId", blankToNull(selectedOptionId));
    putNullable(answer, "freeText", blankToNull(freeText));
    answer.put("answeredBy", answeredBy);
    answer.put("answeredAt", Instant.now().toString());
    putNullable(answer, "comment", blankToNull(comment));
    return answer;
  }

  /**
   * Accepts an answer only for the active request of the named execution.
   *
   * @throws IllegalArgumentException naming the first mismatch
   */
  public static void requireAnswerMatches(JsonNode answer, UUID executionId, String requestContent) {
    JsonNode request = JSON.readTree(requestContent);
    if (!ANSWER_SCHEMA.equals(answer.path("schema").asString(""))
        || !"ANSWERED".equals(answer.path("status").asString(""))) {
      throw new IllegalArgumentException("decision answer must be a " + ANSWER_SCHEMA + " with status ANSWERED");
    }
    if (!executionId.toString().equals(answer.path("executionId").asString(""))
        || !executionId.toString().equals(request.path("executionId").asString(""))) {
      throw new IllegalArgumentException("decision answer belongs to execution "
          + answer.path("executionId").asString("<none>") + ", not " + executionId);
    }
    if (!request.path("decisionId").asString("").equals(answer.path("decisionId").asString(""))) {
      throw new IllegalArgumentException("decision answer names decision '"
          + answer.path("decisionId").asString("<none>") + "' but the active decision is '"
          + request.path("decisionId").asString() + "'");
    }
    if (!requestSha256(requestContent).equals(answer.path("requestSha256").asString(""))) {
      throw new IllegalArgumentException("decision answer was made for a different version of the request");
    }
    if (answer.path("answeredBy").asString("").isBlank()) {
      throw new IllegalArgumentException("decision answer must name who answered (answeredBy)");
    }
    String selected = answer.path("selectedOptionId").asString("");
    String freeText = answer.path("freeText").asString("");
    if (selected.isBlank() == freeText.isBlank()) {
      throw new IllegalArgumentException("decision answer needs exactly one of selectedOptionId or freeText");
    }
    if (!selected.isBlank() && option(request, selected) == null) {
      throw new IllegalArgumentException("selectedOptionId '" + selected + "' is not an option of this decision");
    }
    if (!freeText.isBlank()) {
      if (REPOSITORY_SELECTION.equals(request.path("category").asString(""))) {
        throw new IllegalArgumentException("a repository selection must choose one of the candidate options");
      }
      if (VERIFICATION_PLAN.equals(request.path("category").asString(""))) {
        throw new IllegalArgumentException("a verification plan selection must choose one of the trusted plans");
      }
      if (freeText.length() > MAX_FREE_TEXT) {
        throw new IllegalArgumentException("freeText answer must be at most " + MAX_FREE_TEXT + " characters");
      }
    }
  }

  public static JsonNode option(JsonNode request, String optionId) {
    JsonNode options = request.path("options");
    if (options instanceof ArrayNode array) {
      for (JsonNode option : array) {
        if (optionId.equals(option.path("id").asString(""))) {
          return option;
        }
      }
    }
    return null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static void putNullable(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }
}
