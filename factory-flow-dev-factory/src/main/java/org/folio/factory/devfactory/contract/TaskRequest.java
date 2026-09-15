package org.folio.factory.devfactory.contract;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Versioned, lossless task intent accepted at the Factory trust boundary. */
public record TaskRequest(
    int schemaVersion,
    SourceIdentity source,
    String repository,
    String baseRevision,
    String baseRef,
    String profileId,
    String verificationPlanId,
    String runKey,
    String deliveryMode,
    JsonNode metadata,
    String goal,
    List<AcceptanceCriterion> acceptanceCriteria,
    JsonNode constraints,
    String notes,
    String rawTaskText,
    boolean legacyAdapted,
    List<DeclaredDecision> decisions) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public TaskRequest {
    decisions = decisions == null ? List.of() : List.copyOf(decisions);
  }

  public TaskRequest(int schemaVersion, SourceIdentity source, String repository, String baseRevision,
                     String baseRef, String profileId, String verificationPlanId, String runKey,
                     String deliveryMode, JsonNode metadata, String goal,
                     List<AcceptanceCriterion> acceptanceCriteria, JsonNode constraints, String notes,
                     String rawTaskText, boolean legacyAdapted) {
    this(schemaVersion, source, repository, baseRevision, baseRef, profileId, verificationPlanId, runKey,
        deliveryMode, metadata, goal, acceptanceCriteria, constraints, notes, rawTaskText, legacyAdapted,
        List.of());
  }

  public record SourceIdentity(String type, String id, String project, String component) {
  }

  public record AcceptanceCriterion(String id, String text, String source) {
  }

  /**
   * A material choice the task author knows the task leaves open (for example
   * two valid readings of a requirement). It is never guessed: an execution
   * carrying one pauses for a human answer before any coding budget is spent.
   * {@code evidence} names repository files whose lines matching the terms are
   * read at the exact base revision and shown to the human as discovered facts.
   */
  public record DeclaredDecision(String id, String category, String question, String whyItMatters,
                                 List<Option> options, String recommendedOptionId,
                                 String recommendationRationale, List<Evidence> evidence) {
    public DeclaredDecision {
      options = options == null ? List.of() : List.copyOf(options);
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
  }

  public record Option(String id, String label, String consequence) {
  }

  public record Evidence(String path, List<String> terms) {
    public Evidence {
      terms = terms == null ? List.of() : List.copyOf(terms);
    }
  }
}
