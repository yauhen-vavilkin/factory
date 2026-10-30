package org.folio.factory.devfactory.pilot;

import org.folio.factory.devfactory.runtime.CodingRequest;

import java.util.Map;
import java.util.UUID;

/**
 * The approved admission view granted to one pilot execution: the immutable
 * attempt identity, the bounded approved issue snapshot, the admitted repository
 * and the input/destination identity fingerprints, and the run settings chosen
 * when the run was started (null: the defaults). Workers consume this
 * view instead of re-reading mutable external state.
 */
public record PilotExecutionContext(
        UUID attemptId,
        Map<String, Object> approvedSnapshot,
        String repositoryKey,
        Kind admissionKind,
        String inputFingerprint,
        String destinationFingerprint,
        CodingRequest.RunSettings runSettings) {

    public enum Kind { INITIAL, RETRY }

    public PilotExecutionContext(UUID attemptId, Map<String, Object> approvedSnapshot, String repositoryKey,
                                 Kind admissionKind, String inputFingerprint, String destinationFingerprint) {
        this(attemptId, approvedSnapshot, repositoryKey, admissionKind, inputFingerprint, destinationFingerprint,
                null);
    }

    public PilotExecutionContext {
        approvedSnapshot = approvedSnapshot == null ? Map.of() : Map.copyOf(approvedSnapshot);
    }
}
