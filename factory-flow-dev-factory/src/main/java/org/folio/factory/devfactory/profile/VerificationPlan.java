package org.folio.factory.devfactory.profile;

import java.util.List;

/** Trusted verification obligations frozen before coding. */
public record VerificationPlan(
    String id,
    String catalogVersion,
    List<Check> checks,
    String configurationHash) {

  public record Check(String id, List<String> argv, boolean required,
                      List<String> acceptanceIds, String reportGlob) {
  }
}
