package org.folio.factory.app.pilot.startup;

/**
 * The readiness precondition every pilot dispatch must pass before a paid
 * start. Implemented by {@link PilotReadinessGate}; permissive only where a
 * caller has already established applicable readiness.
 */
public interface PilotExecutionReadiness {

    /** Throws IllegalStateException with the concrete correction when a paid start may not happen. */
    void requireExecutionReady();
}
