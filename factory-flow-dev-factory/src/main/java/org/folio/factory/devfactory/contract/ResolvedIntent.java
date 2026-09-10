package org.folio.factory.devfactory.contract;

import java.util.List;
import org.folio.factory.devfactory.profile.ExecutionProfile;
import org.folio.factory.devfactory.profile.ProfileEvidence;
import org.folio.factory.devfactory.profile.VerificationPlan;

/** Explainable deterministic decision. It is not yet an execution-ready contract. */
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
    List<Ambiguity> ambiguities,
    boolean executionReady,
    String intentHash) {

  public record RepositoryDecision(String status, String canonicalSlug, String origin,
                                   String requestedRef, String exactRevision,
                                   List<String> candidates, List<String> clarificationNeeds) {
  }

  public record Ambiguity(String id, String question, List<String> permittedAlternatives,
                          String provenance) {
  }
}
