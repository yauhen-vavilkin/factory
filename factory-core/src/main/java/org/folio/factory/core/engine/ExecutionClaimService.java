package org.folio.factory.core.engine;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.limits.LimitsProperties;
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
    private final LimitsProperties limits;

    public ExecutionClaimService(PipelineExecutionRepository executions, LimitsProperties limits) {
        this.executions = executions;
        this.limits = limits;
    }

    /**
     * Claims up to {@code limit} runnable executions and flips them to RUNNING in
     * one transaction. Concurrent pollers skip each other's locked rows. When a
     * concurrency cap is configured, the batch is further capped to the number of
     * free slots (max - currently running).
     */
    @Transactional
    public List<UUID> claim(int limit) {
        int effectiveLimit = limit;
        if (limits.concurrencyCapped()) {
            long running = executions.countByStatus(ExecutionStatus.RUNNING);
            long slots = (long) limits.maxConcurrentExecutions() - running;
            if (slots <= 0) {
                return List.of();
            }
            effectiveLimit = (int) Math.min(limit, slots);
        }
        List<PipelineExecution> claimed = executions.claimRunnable(effectiveLimit);
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
     * Requeues every RUNNING execution back to PENDING. Called once at startup (before
     * the first claim) under the single-instance assumption that no execution can
     * legitimately be RUNNING for a freshly started process — so any RUNNING row is an
     * orphan from a prior crash. Unlike {@link #reapStale}, this ignores the lease age,
     * so it recovers freshly-crashed rows immediately rather than waiting out the lease.
     * MUST be disabled ({@code factory.engine.reclaim-running-on-startup=false}) in a
     * multi-instance deployment, where a peer's live work is also RUNNING.
     */
    @Transactional
    public int reclaimOrphanedRunning() {
        List<PipelineExecution> running = executions.findByStatus(ExecutionStatus.RUNNING);
        for (PipelineExecution execution : running) {
            execution.setStatus(ExecutionStatus.PENDING);
            execution.setNextRunAt(Instant.now());
        }
        return running.size();
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
