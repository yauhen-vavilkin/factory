package org.folio.factory.app.pilot.startup;

import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.springframework.stereotype.Component;

/**
 * The current runtime fingerprint for this installation: what the next smoke
 * would test. Read probes and status reports compare it against the persisted
 * evidence identity to decide whether recorded evidence is still reusable.
 */
@Component
public class PilotRuntimeIdentity {

    private final DevRuntimeProperties settings;
    private final PilotStartupProperties bounds;

    public PilotRuntimeIdentity(DevRuntimeProperties settings, PilotStartupProperties bounds) {
        this.settings = settings;
        this.bounds = bounds;
    }

    public RuntimeFingerprint current() {
        return RuntimeFingerprint.of(settings.coding().claudeCode(), bounds.smokeMaxTurns(),
                (int) bounds.smokeTimeoutSeconds(), PilotRuntimeSmoke.instructionsFingerprint());
    }
}
