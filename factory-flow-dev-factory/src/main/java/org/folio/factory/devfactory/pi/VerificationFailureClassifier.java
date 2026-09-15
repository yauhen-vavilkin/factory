package org.folio.factory.devfactory.pi;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic routing for the single Developer Flow verification repair.
 *
 * <p>Routing reads only the status and reason Factory itself recorded. Check
 * output inside the verification evidence is candidate-controlled and never
 * changes the route: verification attributes a failure to the environment or
 * to dependency infrastructure only from Factory-owned evidence (a check with
 * no green baseline, or a failed trusted mirror probe).</p>
 */
final class VerificationFailureClassifier {
  private static final Set<String> REPAIRABLE = Set.of(
      "CANDIDATE_TESTS_FAILED", "TASK_CHECK_FAILED", "SUREFIRE_EVIDENCE_FAILED",
      "VERIFICATION_FAILED", PiWorker.PROTECTED_PATH_MODIFIED);
  private static final Set<String> FACTORY_ERRORS = Set.of(
      "VERIFICATION_PLAN_MISSING", "PATCH_REJECTED", "CANDIDATE_IDENTITY_FAILED",
      "CANDIDATE_TREE_FAILED");

  private VerificationFailureClassifier() {
  }

  enum Route {
    NONE,
    REPAIR,
    BLOCKED_ENVIRONMENT,
    ERROR,
    FINAL_FAILURE,
    CANCELLED
  }

  record Decision(Route route, String failureClass, String reason) {
  }

  static Decision classify(JsonNode verification) {
    String status = verification.path("status").asText("ERROR");
    String reason = verification.path("reason").asText("VERIFICATION_RESULT_MALFORMED");
    if ("PASS".equals(status)) {
      return new Decision(Route.NONE, "NONE", "VERIFICATION_PASSED");
    }
    if ("BLOCKED_ENVIRONMENT".equals(status) || "BLOCKED_ENVIRONMENT".equals(reason)) {
      return new Decision(Route.BLOCKED_ENVIRONMENT, "ENVIRONMENT", reason);
    }
    if ("CANCELLED".equals(status) || "CANCELLED".equals(reason)
        || "CANCELLATION".equals(reason)) {
      return new Decision(Route.CANCELLED, "CANCELLATION", reason);
    }
    if (PiWorker.DEPENDENCY_MIRROR_UNAVAILABLE.equals(reason)) {
      return new Decision(Route.ERROR, "DEPENDENCY_INFRASTRUCTURE", reason);
    }
    if ("ERROR".equals(status) || "INCOMPLETE".equals(status)) {
      return new Decision(Route.ERROR, "FACTORY_OR_INFRASTRUCTURE", reason);
    }
    if (REPAIRABLE.contains(reason)) {
      return new Decision(Route.REPAIR, "REPAIRABLE_DEFECT", reason);
    }
    if (FACTORY_ERRORS.contains(reason)) {
      return new Decision(Route.ERROR, "FACTORY_OR_PROTOCOL", reason);
    }
    if ("PI_UNSETTLED".equals(reason) || "EMPTY_CANDIDATE".equals(reason)
        || "REPAIR_DID_NOT_CHANGE_CANDIDATE".equals(reason)
        || PiWorker.CODING_BUDGET_EXHAUSTED.equals(reason)) {
      return new Decision(Route.FINAL_FAILURE, "CODING_BUDGET_OR_RESULT", reason);
    }
    String stage = verification.path("stage").asText("");
    if ("PREPARE".equals(stage) || "PI_RUNTIME".equals(stage) || "coding".equals(stage)
        || "REPAIR".equals(stage)) {
      return new Decision(Route.ERROR, "FACTORY_OR_INFRASTRUCTURE", reason);
    }
    return new Decision(Route.ERROR, "UNCLASSIFIED_VERIFICATION_FAILURE", reason);
  }
}
