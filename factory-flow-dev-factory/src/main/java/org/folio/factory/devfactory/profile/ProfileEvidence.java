package org.folio.factory.devfactory.profile;

import java.util.List;

/** Static repository evidence; unknown values remain explicit. */
public record ProfileEvidence(
    String language,
    String buildTool,
    String languageVersion,
    String framework,
    String frameworkVersion,
    String wrapperVersion,
    String dockerRequirement,
    List<String> evidencePaths,
    List<String> unknowns) {
}
