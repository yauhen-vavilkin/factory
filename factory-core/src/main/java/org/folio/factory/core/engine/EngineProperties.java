package org.folio.factory.core.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.engine")
public record EngineProperties(
        Boolean enabled,
        Long pollIntervalMs,
        Integer batchSize,
        Integer workerThreads,
        Long leaseTimeoutSeconds,
        Integer shutdownAwaitSeconds,
        Boolean reclaimRunningOnStartup) {

    public EngineProperties {
        enabled = enabled == null || enabled;
        pollIntervalMs = pollIntervalMs == null ? 2000L : pollIntervalMs;
        batchSize = batchSize == null ? 5 : batchSize;
        workerThreads = workerThreads == null ? 4 : workerThreads;
        // Agent steps renew their lease periodically, including during long calls.
        leaseTimeoutSeconds = leaseTimeoutSeconds == null ? 1800L : leaseTimeoutSeconds;
        if (leaseTimeoutSeconds < 1) {
            throw new IllegalArgumentException("leaseTimeoutSeconds must be positive");
        }
        // Bounded drain budget on shutdown; a step still running past it is
        // abandoned at forced pool shutdown and recovered by the reaper after
        // leaseTimeoutSeconds. Non-null by construction (used as int below).
        shutdownAwaitSeconds = shutdownAwaitSeconds == null ? 30 : shutdownAwaitSeconds;
        // On startup, requeue any RUNNING rows left behind by a prior process so a
        // crash does not strand work (and, with the concurrency cap on, does not tie
        // up cap slots) until the lease reaper clears them. SINGLE-INSTANCE ASSUMPTION:
        // at startup no execution can legitimately be RUNNING for this process. In a
        // multi-instance deployment set this false so one instance's restart never
        // requeues a peer's live work.
        reclaimRunningOnStartup = reclaimRunningOnStartup == null || reclaimRunningOnStartup;
    }
}
