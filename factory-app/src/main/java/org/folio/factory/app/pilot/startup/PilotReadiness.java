package org.folio.factory.app.pilot.startup;

import java.time.Instant;
import java.util.List;

/**
 * The result of one pilot readiness evaluation. Discovery and execution are
 * separate axes: discovery stays available with a visible execution blocker,
 * and missing rates, failed or unrun inference or unknown usage are never
 * execution-ready. {@code evidenceFingerprint} names the reusable evidence
 * identity backing this report; {@code evidenceCurrent} says whether it still
 * matches the installation's runtime configuration.
 */
public record PilotReadiness(
        boolean discoveryAvailable,
        boolean executionReady,
        List<String> blockers,
        String evidenceFingerprint,
        boolean evidenceCurrent,
        PilotRuntimeSmoke.SmokeEvidence smoke,
        String smokeDisclosure,
        Instant checkedAt) {

    public PilotReadiness {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
