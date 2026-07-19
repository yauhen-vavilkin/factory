package org.folio.factory.core.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the scheduled retention purge. Retention is destructive, so it
 * is opt-in: {@code enabled} defaults to false. {@code runCron} is consumed by the
 * {@code @Scheduled} placeholder on {@link RetentionService}; the other fields are
 * read at runtime.
 */
@ConfigurationProperties(prefix = "factory.retention")
public record RetentionProperties(
        Boolean enabled,
        Integer ttlDays,
        Integer batchSize,
        String runCron) {

    public RetentionProperties {
        // Opt-in: absence or an explicit false leaves purging off.
        enabled = enabled != null && enabled;
        ttlDays = ttlDays == null ? 90 : ttlDays;
        // Number of terminal execution trees purged per run; bounds lock footprint.
        batchSize = batchSize == null ? 100 : batchSize;
        runCron = runCron == null || runCron.isBlank() ? "0 30 3 * * *" : runCron;
    }
}
