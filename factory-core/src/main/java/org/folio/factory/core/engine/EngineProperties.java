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
        leaseTimeoutSeconds = leaseTimeoutSeconds == null ? 600L : leaseTimeoutSeconds;
    }
}
