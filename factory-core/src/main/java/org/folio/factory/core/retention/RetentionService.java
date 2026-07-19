package org.folio.factory.core.retention;

import org.folio.factory.core.domain.ExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Flow-agnostic data retention. On a configurable cron cadence, purges expired
 * terminal execution trees so artifact content, trigger payloads and HITL review
 * packages do not grow without bound.
 *
 * <p>Opt-in and destructive: the whole bean is gated on
 * {@code factory.retention.enabled=true}, so when retention is off no scheduled task
 * is even registered. audit_event is never purged — it is append-only and retained
 * for compliance; its execution_id is nullable and unconstrained, so the rows simply
 * become orphaned references once their execution is gone.</p>
 */
@Component
@ConditionalOnProperty(prefix = "factory.retention", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /**
     * Statuses that are safe to purge. FAILED_ESCALATED is deliberately excluded:
     * although it is terminal, such a run has a pending escalation review and can
     * still be resumed, so its data must survive.
     */
    public static final List<String> PURGEABLE_STATUSES = List.of(
            ExecutionStatus.COMPLETED.name(),
            ExecutionStatus.REJECTED.name(),
            ExecutionStatus.CANCELLED.name());

    private final RetentionRepository repository;
    private final RetentionPurger purger;
    private final RetentionProperties properties;

    public RetentionService(RetentionRepository repository, RetentionPurger purger,
                            RetentionProperties properties) {
        this.repository = repository;
        this.purger = purger;
        this.properties = properties;
    }

    @Scheduled(cron = "${factory.retention.run-cron:0 30 3 * * *}")
    public void purgeExpired() {
        if (properties.ttlDays() < 1) {
            // A non-positive TTL would set the threshold at (or after) now and purge
            // every terminal run immediately. Refuse rather than delete everything; to
            // turn retention off set factory.retention.enabled=false.
            log.warn("Refusing retention purge: factory.retention.ttl-days={} is not >= 1", properties.ttlDays());
            return;
        }
        Instant threshold = Instant.now().minus(Duration.ofDays(properties.ttlDays()));
        List<UUID> roots = repository.findPurgeableRootIds(PURGEABLE_STATUSES, threshold, properties.batchSize());
        if (roots.isEmpty()) {
            return;
        }
        int trees = 0;
        int executions = 0;
        int artifacts = 0;
        int hitlReviews = 0;
        int skipped = 0;
        for (UUID root : roots) {
            try {
                RetentionPurger.Outcome outcome = purger.purgeTree(root);
                if (outcome.skipped()) {
                    skipped++;
                    continue;
                }
                trees++;
                executions += outcome.executions();
                artifacts += outcome.artifacts();
                hitlReviews += outcome.hitlReviews();
            } catch (RuntimeException e) {
                // One bad tree must not abort the whole run.
                log.warn("Retention purge failed for execution tree {}: {}", root, e.getMessage());
            }
        }
        log.info("Retention purge removed {} tree(s): {} executions, {} artifacts, {} hitl reviews "
                        + "({} candidate(s), {} skipped, ttl {} day(s))",
                trees, executions, artifacts, hitlReviews, roots.size(), skipped, properties.ttlDays());
    }
}
