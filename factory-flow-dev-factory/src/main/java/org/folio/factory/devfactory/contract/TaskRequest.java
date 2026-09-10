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
    boolean legacyAdapted) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public record SourceIdentity(String type, String id, String project, String component) {
  }

  public record AcceptanceCriterion(String id, String text, String source) {
  }
}
