package org.folio.factory.devfactory.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the readable conversation of each coding run. The application stores it; this module has
 * no database. Writes are best effort: a lost entry never changes the coding result.
 */
public interface CodingTranscripts {

    /** One coding run of an execution: the initial run, a continuation, or a PR feedback round. */
    Sink open(UUID executionId, UUID runId, String stepId);

    /** Entries arrive in order. Every text in {@code detail} is already redacted and cut. */
    interface Sink extends AutoCloseable {
        void add(Entry entry);

        /** Writes what is still buffered. */
        @Override
        default void close() {
        }
    }

    record Entry(String kind, Instant at, Map<String, Object> detail) {
    }

    Sink NONE = entry -> {
    };

    static CodingTranscripts none() {
        return (executionId, runId, stepId) -> NONE;
    }
}
