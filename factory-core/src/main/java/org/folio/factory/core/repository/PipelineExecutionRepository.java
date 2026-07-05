package org.folio.factory.core.repository;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PipelineExecutionRepository extends JpaRepository<PipelineExecution, UUID> {

    /**
     * Claims runnable executions with a row lock, skipping rows already claimed by
     * a concurrent poller. Must be called inside a transaction.
     */
    @Query(value = """
            SELECT * FROM pipeline_execution
            WHERE status = 'PENDING' AND next_run_at <= now()
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PipelineExecution> claimRunnable(@Param("limit") int limit);

    List<PipelineExecution> findByStatusAndUpdatedAtBefore(ExecutionStatus status, Instant threshold);

    List<PipelineExecution> findByParentExecutionId(UUID parentExecutionId);

    List<PipelineExecution> findAllByOrderByCreatedAtDesc();
}
