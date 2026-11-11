package org.folio.factory.core.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the scheduled retention purge. Retention is destructive, so it
 * is opt-in: {@code enabled} defaults to false. The schedule,
 * {@code factory.retention.run-cron}, is read only by the {@code @Scheduled}
 * placeholder on {@link RetentionService}; these fields are read at runtime.
 */
@ConfigurationProperties(prefix = "factory.retention")
public record RetentionProperties(
        Boolean enabled,
        Integer ttlDays,
        Integer batchSize) {

    public RetentionProperties {
        // Opt-in: absence or an explicit false leaves purging off.
        enabled = enabled != null && enabled;
        ttlDays = ttlDays == null ? 90 : ttlDays;
        // Number of terminal execution trees purged per run; bounds lock footprint.
        batchSize = batchSize == null ? 100 : batchSize;
    }
}
