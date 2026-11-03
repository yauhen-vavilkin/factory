package org.folio.factory.core.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.folio.factory.core.domain.ExecutionStatus;
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
@RequiredArgsConstructor
@Slf4j
public class RetentionService {

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
        var totals = purgeTrees(roots);
        log.info("Retention purge removed {} tree(s): {} executions, {} artifacts, {} hitl reviews "
                        + "({} candidate(s), {} skipped, ttl {} day(s))",
                totals.trees, totals.executions, totals.artifacts, totals.hitlReviews, roots.size(),
                totals.skipped, properties.ttlDays());
    }

    private PurgeTotals purgeTrees(List<UUID> roots) {
        var totals = new PurgeTotals();
        for (UUID root : roots) {
            try {
                totals.add(purger.purgeTree(root));
            } catch (RuntimeException e) {
                // One bad tree must not abort the whole run.
                log.warn("Retention purge failed for execution tree {}: {}", root, e.getMessage());
            }
        }
        return totals;
    }

    private static final class PurgeTotals {
        private int trees;
        private int executions;
        private int artifacts;
        private int hitlReviews;
        private int skipped;

        void add(RetentionPurger.Outcome outcome) {
            if (outcome.skipped()) {
                skipped++;
                return;
            }
            trees++;
            executions += outcome.executions();
            artifacts += outcome.artifacts();
            hitlReviews += outcome.hitlReviews();
        }
    }
}
