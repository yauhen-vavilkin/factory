package org.folio.factory.core.retention;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Purges a single execution tree in one transaction. Separated from
 * {@link RetentionService} so each tree is its own transaction (a failure on one
 * tree does not roll back the others, and row locks are released promptly) and so
 * the {@code @Transactional} boundary is a real cross-bean call, not a
 * self-invocation the proxy would ignore.
 */
@Service
public class RetentionPurger {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurger.class);

    private final RetentionRepository repository;
    private final AuditLog auditLog;

    public RetentionPurger(RetentionRepository repository, AuditLog auditLog) {
        this.repository = repository;
        this.auditLog = auditLog;
    }

    /**
     * Deletes the root and all descendants: artifacts, then hitl_reviews, then the
     * executions themselves. Returns {@link Outcome#skipped()} without deleting
     * anything if the tree contains a node that is not safe to purge, or if the root
     * has already vanished (concurrent purge).
     */
    @Transactional
    public Outcome purgeTree(UUID rootId) {
        List<UUID> treeIds = repository.collectTreeIds(rootId);
        if (treeIds.isEmpty()) {
            return Outcome.skippedTree();
        }
        List<UUID> blocking = repository.findUnpurgeableInTree(treeIds, RetentionService.PURGEABLE_STATUSES);
        if (!blocking.isEmpty()) {
            log.warn("Skipping retention purge of execution tree {} ({} nodes): {} node(s) still active or escalated",
                    rootId, treeIds.size(), blocking.size());
            return Outcome.skippedTree();
        }
        int artifacts = repository.deleteArtifactsForExecutions(treeIds);
        int hitlReviews = repository.deleteHitlReviewsForExecutions(treeIds);
        int executions = repository.deleteExecutions(treeIds);
        // audit_event has no FK to pipeline_execution and only forbids UPDATE/DELETE,
        // so inserting a purge record that references the now-deleted root is fine.
        auditLog.record(rootId, AuditEventType.RETENTION_PURGED, null,
                Map.of("treeSize", treeIds.size(),
                        "executions", executions,
                        "artifacts", artifacts,
                        "hitlReviews", hitlReviews));
        return new Outcome(false, executions, artifacts, hitlReviews);
    }

    public record Outcome(boolean skipped, int executions, int artifacts, int hitlReviews) {
        public static Outcome skippedTree() {
            return new Outcome(true, 0, 0, 0);
        }
    }
}
