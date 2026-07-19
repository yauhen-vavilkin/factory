package org.folio.factory.core.retention;

import org.folio.factory.core.domain.PipelineExecution;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The ONLY delete surface in the control plane. Retention removes whole expired,
 * terminal execution trees; it never mutates a surviving row, so artifact/execution
 * immutability is preserved. audit_event is deliberately absent here: it is
 * append-only (DB trigger) and retained for compliance.
 *
 * <p>All statements are native SQL. The pipeline_execution self-FK
 * ({@code fk_parent_execution}) has no ON DELETE clause, so it defaults to NO
 * ACTION, whose referential check is deferred to end-of-statement. That makes the
 * single-statement {@link #deleteExecutions} safe even when the id set contains both
 * parents and their children (RESTRICT would fail; NO ACTION does not).</p>
 */
public interface RetentionRepository extends Repository<PipelineExecution, UUID> {

    /**
     * Top-level executions (no parent) in a purgeable terminal state whose
     * completion is older than the threshold. Ordered oldest-first and capped so a
     * run does its work in bounded batches.
     */
    @Query(value = """
            SELECT id FROM pipeline_execution
            WHERE parent_execution_id IS NULL
              AND status IN (:statuses)
              AND completed_at IS NOT NULL
              AND completed_at < :threshold
            ORDER BY completed_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findPurgeableRootIds(@Param("statuses") List<String> statuses,
                                    @Param("threshold") Instant threshold,
                                    @Param("limit") int limit);

    /**
     * The root plus every descendant execution (sub-flow children, recursively).
     */
    @Query(value = """
            WITH RECURSIVE tree AS (
                SELECT id FROM pipeline_execution WHERE id = :rootId
                UNION ALL
                SELECT c.id FROM pipeline_execution c
                    JOIN tree t ON c.parent_execution_id = t.id
            )
            SELECT id FROM tree
            """, nativeQuery = true)
    List<UUID> collectTreeIds(@Param("rootId") UUID rootId);

    /**
     * Ids in the tree whose status is NOT purgeable (e.g. a resumable
     * FAILED_ESCALATED sub-flow child, or a still-running node after a crash). A
     * non-empty result means the whole tree must be left in place.
     */
    @Query(value = """
            SELECT id FROM pipeline_execution
            WHERE id IN (:ids) AND status NOT IN (:purgeableStatuses)
            """, nativeQuery = true)
    List<UUID> findUnpurgeableInTree(@Param("ids") Collection<UUID> ids,
                                     @Param("purgeableStatuses") List<String> purgeableStatuses);

    @Modifying
    @Query(value = "DELETE FROM artifact WHERE execution_id IN (:ids)", nativeQuery = true)
    int deleteArtifactsForExecutions(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query(value = "DELETE FROM hitl_review WHERE execution_id IN (:ids)", nativeQuery = true)
    int deleteHitlReviewsForExecutions(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query(value = "DELETE FROM pipeline_execution WHERE id IN (:ids)", nativeQuery = true)
    int deleteExecutions(@Param("ids") Collection<UUID> ids);
}
