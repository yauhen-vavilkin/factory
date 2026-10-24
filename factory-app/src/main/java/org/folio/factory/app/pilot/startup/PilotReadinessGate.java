package org.folio.factory.app.pilot.startup;

import org.folio.factory.app.pilot.persistence.PilotReadinessEvidence;
import org.folio.factory.app.pilot.persistence.PilotReadinessEvidenceRepository;
import org.folio.factory.app.web.DeveloperPricingProperties;
import org.folio.factory.core.retention.RetentionProperties;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Fail-closed gate for paid pilot starts: destructive retention refuses
 * activation instead of deleting history, and a queued start waits for passed,
 * current readiness evidence with a known rate card. S05's ownership, paid-start
 * claim and sync checks stay upstream of this gate and keep their authority.
 */
@Component
public class PilotReadinessGate implements PilotExecutionReadiness {

    private final PilotReadinessEvidenceRepository evidence;
    private final PilotRuntimeIdentity identity;
    private final RetentionProperties retention;
    private final DeveloperPricingProperties pricing;
    private final DevRuntimeProperties settings;

    public PilotReadinessGate(PilotReadinessEvidenceRepository evidence, PilotRuntimeIdentity identity,
                              RetentionProperties retention, DeveloperPricingProperties pricing,
                              DevRuntimeProperties settings) {
        this.evidence = evidence;
        this.identity = identity;
        this.retention = retention;
        this.pricing = pricing;
        this.settings = settings;
    }

    @Override
    public void requireExecutionReady() {
        if (retention.enabled()) {
            throw new IllegalStateException(PilotStartupService.RETENTION_BLOCKER);
        }
        Optional<PilotReadinessEvidence> passed = evidence.findTopByPassedTrueOrderByCheckedAtDesc();
        if (passed.isEmpty()) {
            throw new IllegalStateException("Pilot execution is blocked: "
                    + PilotStartupService.NOT_RUN_BLOCKER + "; run the pilot start action first.");
        }
        if (!passed.get().getRuntimeFingerprint().equals(identity.current().identity())) {
            throw new IllegalStateException("Pilot execution is blocked: "
                    + PilotStartupService.STALE_BLOCKER);
        }
        String model = settings.coding().claudeCode().model();
        if (pricing.rateFor(model).isEmpty()) {
            throw new IllegalStateException("Pilot execution is blocked: no rate card for " + model
                    + "; add it to config/developer-pricing.yaml.");
        }
    }
}
