package org.folio.factory.core.engine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Drives the engine: claims runnable executions from the database on a fixed
 * cadence and dispatches them to the worker pool. Restart-safe by construction —
 * all queue state lives in Postgres.
 */
@Component
@ConditionalOnProperty(prefix = "factory.engine", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionPoller {

    private final ExecutionClaimService claimService;
    private final ExecutionEngine engine;
    private final SubFlowInvoker subFlowInvoker;
    private final EngineProperties properties;
    private final AsyncTaskExecutor executor;

    public ExecutionPoller(ExecutionClaimService claimService, ExecutionEngine engine,
                           SubFlowInvoker subFlowInvoker, EngineProperties properties,
                           AsyncTaskExecutor factoryEngineExecutor) {
        this.claimService = claimService;
        this.engine = engine;
        this.subFlowInvoker = subFlowInvoker;
        this.properties = properties;
        this.executor = factoryEngineExecutor;
    }

    @Scheduled(fixedDelayString = "${factory.engine.poll-interval-ms:2000}")
    public void poll() {
        for (UUID executionId : claimService.claim(properties.batchSize())) {
            try {
                executor.execute(() -> engine.advance(executionId));
            } catch (RuntimeException e) {
                // Submission failed (e.g. executor shutting down): give the claim
                // back immediately instead of leaving it RUNNING for the reaper.
                claimService.release(executionId);
            }
        }
    }

    @Scheduled(fixedDelayString = "${factory.engine.reap-interval-ms:30000}")
    public void maintain() {
        claimService.reapStale(properties.leaseTimeoutSeconds());
        subFlowInvoker.escalateParentsOfTerminatedChildren();
        subFlowInvoker.reconcileCompletedChildren();
    }
}
