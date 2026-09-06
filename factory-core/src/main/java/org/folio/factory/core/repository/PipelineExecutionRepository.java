package org.folio.factory.core.repository;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * Child executions in one of the given states whose parent is still waiting on
     * them. Scoped to waiting parents so periodic reconciliation stays cheap as
     * finished executions accumulate over time.
     */
    @Query("""
            SELECT c FROM PipelineExecution c
            WHERE c.status IN :statuses
              AND c.parentExecutionId IS NOT NULL
              AND EXISTS (SELECT p.id FROM PipelineExecution p
                          WHERE p.id = c.parentExecutionId AND p.status = :parentStatus)
            """)
    List<PipelineExecution> findChildrenWithWaitingParent(
            @Param("statuses") List<ExecutionStatus> statuses,
            @Param("parentStatus") ExecutionStatus parentStatus);

    List<PipelineExecution> findAllByOrderByCreatedAtDesc();

    Optional<PipelineExecution> findByFlowIdAndAdmissionKey(String flowId, String admissionKey);
}
