package org.folio.factory.core.engine;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Short transactional operations used by the poller: claiming runnable executions
 * with row locks, and recovering executions whose worker died mid-step.
 */
@Service
public class ExecutionClaimService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionClaimService.class);

    private final PipelineExecutionRepository executions;

    public ExecutionClaimService(PipelineExecutionRepository executions) {
        this.executions = executions;
    }

    /**
     * Claims up to {@code limit} runnable executions and flips them to RUNNING in
     * one transaction. Concurrent pollers skip each other's locked rows.
     */
    @Transactional
    public List<UUID> claim(int limit) {
        List<PipelineExecution> claimed = executions.claimRunnable(limit);
        for (PipelineExecution execution : claimed) {
            execution.setStatus(ExecutionStatus.RUNNING);
            execution.touchUpdatedAt();
        }
        return claimed.stream().map(PipelineExecution::getId).toList();
    }

    /**
     * Returns a claim that could not be dispatched to the worker pool.
     */
    @Transactional
    public void release(UUID executionId) {
        executions.findById(executionId).ifPresent(execution -> {
            if (execution.getStatus() == ExecutionStatus.RUNNING) {
                execution.setStatus(ExecutionStatus.PENDING);
                execution.setNextRunAt(Instant.now());
            }
        });
    }

    /**
     * Returns executions stuck in RUNNING past the lease timeout (crashed worker,
     * killed process) to PENDING so a poller can pick them up again.
     */
    @Transactional
    public int reapStale(long leaseTimeoutSeconds) {
        Instant threshold = Instant.now().minusSeconds(leaseTimeoutSeconds);
        List<PipelineExecution> stale = executions.findByStatusAndUpdatedAtBefore(ExecutionStatus.RUNNING, threshold);
        for (PipelineExecution execution : stale) {
            log.warn("Reaping stale RUNNING execution {} (last update {})", execution.getId(), execution.getUpdatedAt());
            execution.setStatus(ExecutionStatus.PENDING);
            execution.setNextRunAt(Instant.now());
        }
        return stale.size();
    }
}
