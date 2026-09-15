package org.folio.factory.devfactory.pi;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Deterministic routing for the single Developer Flow verification repair. */
final class VerificationFailureClassifier {
  private static final Set<String> REPAIRABLE = Set.of(
      "CANDIDATE_TESTS_FAILED", "TASK_CHECK_FAILED", "SUREFIRE_EVIDENCE_FAILED",
      "VERIFICATION_FAILED");
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
    if ("ERROR".equals(status) || "INCOMPLETE".equals(status)) {
      return new Decision(Route.ERROR, "FACTORY_OR_INFRASTRUCTURE", reason);
    }
    if (infrastructureFailureSignature(verification)) {
      return new Decision(Route.ERROR, "DEPENDENCY_INFRASTRUCTURE", reason);
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

  private static boolean infrastructureFailureSignature(JsonNode verification) {
    String evidence = verification.toString().toLowerCase(java.util.Locale.ROOT);
    return evidence.contains("could not transfer artifact")
        || evidence.contains("could not find artifact")
        || evidence.contains("temporary failure in name resolution")
        || evidence.contains("unknown host")
        || evidence.contains("pkix path building failed")
        || evidence.contains("status code: 401")
        || evidence.contains("status code: 403")
        || (evidence.contains("maven/repository")
            && (evidence.contains("connection refused") || evidence.contains("timed out")));
  }
}
