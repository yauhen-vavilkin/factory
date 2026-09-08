package org.folio.factory.core.engine;

import java.util.UUID;

/**
 * Recovery-bundle seam of the engine (T25): a worker may publish an
 * attempt-keyed recovery bundle of its produced artifacts before its step's
 * outputs are durably persisted. Once the engine has persisted every declared
 * output of the attempt, the attempt's work is acknowledged and its bundle is
 * disposable — the engine then calls {@link #discardAcknowledged(UUID, String, int)}
 * for exactly that one attempt.
 *
 * <p>Implementations are discovered as Spring beans and injected into the
 * engine as a list (empty when no plugin provides one, mirroring
 * {@link StepPostProcessor}). The only destructive action is
 * attempt-scoped; a discarded bundle must never affect a sibling attempt's
 * preserved copy. Failures of {@code discardAcknowledged} must never fail the
 * step: a leaked bundle is the safe direction, destruction-before-ack is not.</p>
 */
public interface StepRecoveryStore {

    /**
     * Deletes exactly the acknowledged attempt's bundle
     * ({@code <root>/<executionId>/<stepId>/attempt-<attempt>/}). Must be a
     * no-op when no bundle exists for the attempt.
     */
    void discardAcknowledged(UUID executionId, String stepId, int attempt);
}
