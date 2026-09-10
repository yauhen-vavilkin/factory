package org.folio.factory.devfactory.contract;

import java.util.List;
import java.util.Map;
import org.folio.factory.devfactory.profile.ExecutionProfile;
import org.folio.factory.devfactory.profile.VerificationPlan;

/** Frozen ExecutionContract/v1. contractHash covers canonical bytes excluding itself. */
public record ExecutionContract(
    String schema,
    TaskIdentity task,
    SourceSnapshot source,
    List<Acceptance> acceptance,
    List<String> unknowns,
    Map<String, Object> constraints,
    ExecutionProfile executionProfile,
    VerificationPlan verificationPlan,
    PreparationReferences preparation,
    Policies policies,
    ExecutionProfile.Budgets budgets,
    ConfigurationReferences configuration,
    String contractHash) {

  public record TaskIdentity(String type, String id, String runKey, String deliveryMode,
                             String semanticTaskHash, String rawTaskText, String goal, String notes) {
  }

  public record SourceSnapshot(String canonicalSlug, String approvedOrigin, String requestedRef,
                               String exactRevision, String sourceSnapshotHash) {
  }

  public record Acceptance(String id, String text, String provenance) {
  }

  public record PreparationReferences(String sourceSnapshotHash, String imageDigest, String platform,
                                      String dependencySeedHash, String baselineHash) {
  }

  public record Policies(String networkPolicy, String filesystemPolicy,
                         String secretPolicy, String commandPolicy) {
  }

  public record ConfigurationReferences(String factoryRevision, String flowDescriptorHash,
                                        String harnessHash, String modelProfileHash,
                                        String profileCatalogHash, String verificationCatalogHash) {
  }
}
