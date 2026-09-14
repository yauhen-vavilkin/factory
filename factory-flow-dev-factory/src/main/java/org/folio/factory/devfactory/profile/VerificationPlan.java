package org.folio.factory.devfactory.profile;

import java.util.List;

/** Trusted verification obligations frozen before coding. */
public record VerificationPlan(
    String id,
    String catalogVersion,
    List<Check> checks,
    String configurationHash) {

  /**
   * Minimal capability declaration, not a general capability engine: a check
   * whose command runs the repository's Docker/Testcontainers integration
   * suite declares it here so preparation can prove the sandbox can run it
   * before any coding budget is spent.
   */
  public record Check(String id, List<String> argv, boolean required,
                      List<String> acceptanceIds, String reportGlob,
                      boolean requiresDocker) {
  }
}
