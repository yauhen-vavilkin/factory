package org.folio.factory.core.registry.model;

import java.util.List;

public record HitlGateSpec(
        String gateId,
        String title,
        String reviewInstructions,
        List<String> reviewedArtifacts) {

    public HitlGateSpec {
        reviewedArtifacts = reviewedArtifacts == null ? List.of() : List.copyOf(reviewedArtifacts);
    }
}
