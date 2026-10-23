package org.folio.factory.devfactory.pilot;

import java.util.Map;
import java.util.UUID;

/**
 * The approved admission view granted to one pilot execution: the immutable
 * attempt identity, the bounded approved issue snapshot, the admitted repository
 * and the input/scope/destination identity fingerprints. Workers consume this
 * view instead of re-reading mutable external state.
 */
public record PilotExecutionContext(
        UUID attemptId,
        Map<String, Object> approvedSnapshot,
        String repositoryKey,
        Kind admissionKind,
        String inputFingerprint,
        String scopeFingerprint,
        String destinationFingerprint) {

    public enum Kind { INITIAL, RETRY }

    public PilotExecutionContext {
        approvedSnapshot = approvedSnapshot == null ? Map.of() : Map.copyOf(approvedSnapshot);
    }
}
