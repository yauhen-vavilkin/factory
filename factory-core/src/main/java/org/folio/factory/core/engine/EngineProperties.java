package org.folio.factory.core.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.engine")
public record EngineProperties(
        Boolean enabled,
        Long pollIntervalMs,
        Integer maxConcurrentExecutions,
        Integer batchSize,
        Integer workerThreads,
        Long leaseTimeoutSeconds) {

    public EngineProperties {
        enabled = enabled == null || enabled;
        pollIntervalMs = pollIntervalMs == null ? 2000L : pollIntervalMs;
        maxConcurrentExecutions = maxConcurrentExecutions == null ? 1 : maxConcurrentExecutions;
        batchSize = batchSize == null ? 1 : batchSize;
        workerThreads = workerThreads == null ? 1 : workerThreads;
        if (maxConcurrentExecutions < 1 || batchSize < 1 || workerThreads < 1) {
            throw new IllegalArgumentException("Factory engine concurrency values must be at least 1");
        }
        // Must exceed the longest legitimate single step (Karate runs are capped
        // at 15 minutes) or the reaper re-queues live executions.
        leaseTimeoutSeconds = leaseTimeoutSeconds == null ? 1800L : leaseTimeoutSeconds;
    }
}
