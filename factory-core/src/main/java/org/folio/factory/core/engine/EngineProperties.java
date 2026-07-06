package org.folio.factory.core.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.engine")
public record EngineProperties(
        Boolean enabled,
        Long pollIntervalMs,
        Integer batchSize,
        Integer workerThreads,
        Long leaseTimeoutSeconds) {

    public EngineProperties {
        enabled = enabled == null || enabled;
        pollIntervalMs = pollIntervalMs == null ? 2000L : pollIntervalMs;
        batchSize = batchSize == null ? 5 : batchSize;
        workerThreads = workerThreads == null ? 4 : workerThreads;
        // Must exceed the longest legitimate single step (Karate runs are capped
        // at 15 minutes) or the reaper re-queues live executions.
        leaseTimeoutSeconds = leaseTimeoutSeconds == null ? 1800L : leaseTimeoutSeconds;
    }
}
