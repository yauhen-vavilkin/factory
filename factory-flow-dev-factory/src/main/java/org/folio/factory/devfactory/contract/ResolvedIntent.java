package org.folio.factory.devfactory.contract;

import java.util.List;
import org.folio.factory.devfactory.profile.ExecutionProfile;
import org.folio.factory.devfactory.profile.ProfileEvidence;
import org.folio.factory.devfactory.profile.VerificationPlan;

/**
 * Explainable deterministic decision. It is not yet an execution-ready contract.
 * {@code status} is RESOLVED, BLOCKED, or NEEDS_DECISION; only the last carries
 * a {@link Decision}, which the decision flow turns into a human request.
 */
public record ResolvedIntent(
    String schema,
    String status,
    String code,
    String message,
    TaskRequest task,
    String semanticTaskHash,
    String admissionKey,
    RepositoryDecision repository,
    ProfileEvidence profileEvidence,
    ExecutionProfile profile,
    VerificationPlan verificationPlan,
    List<String> unknowns,
    Decision decision,
    boolean executionReady,
    String intentHash) {

  public static final String NEEDS_DECISION = "NEEDS_DECISION";

  public record RepositoryDecision(String status, String canonicalSlug, String origin,
                                   String requestedRef, String exactRevision,
                                   List<String> candidates, List<String> clarificationNeeds) {
  }

  /**
   * The open question in decision-interface form: what is known, what must be
   * chosen, why it matters, and the concrete options. A recommendation is
   * present only when the declaring evidence supports one.
   */
  public record Decision(String id, String category, String question, String whyItMatters,
                         List<TaskRequest.Option> options, String recommendedOptionId,
                         String recommendationRationale, List<Fact> facts, String requiredInput,
                         String provenance) {
  }

  public record Fact(String statement, String source) {
  }
}
