package org.folio.factory.core.engine;

import jakarta.annotation.PreDestroy;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.service.StateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keeps blocking agent calls alive without allocating a thread per execution. */
@Component
public class AgentLeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(AgentLeaseHeartbeat.class);

    private final StateManager stateManager;
    private final ScheduledThreadPoolExecutor scheduler;
    private final long intervalMillis;

    public AgentLeaseHeartbeat(StateManager stateManager, EngineProperties properties) {
        this.stateManager = stateManager;
        intervalMillis = Math.min(30_000L, TimeUnit.SECONDS.toMillis(properties.leaseTimeoutSeconds()) / 3);
        scheduler = new ScheduledThreadPoolExecutor(properties.workerThreads(),
                Thread.ofPlatform().daemon().name("factory-lease-", 0).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public Lease start(PipelineExecution execution) {
        Lease lease = new Lease(execution);
        lease.renew();
        lease.requireHealthy();
        lease.future = scheduler.scheduleWithFixedDelay(lease::renew,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return lease;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public final class Lease implements AutoCloseable {
        private final UUID executionId;
        private final int stepIndex;
        private long version;
        private ScheduledFuture<?> future;
        private boolean closed;
        private LeaseLostException failure;

        private Lease(PipelineExecution execution) {
            executionId = execution.getId();
            stepIndex = execution.getCurrentStepIndex();
            version = execution.getVersion();
        }

        private synchronized void renew() {
            if (closed || failure != null) {
                return;
            }
            try {
                if (!stateManager.heartbeat(executionId, stepIndex, version)) {
                    throw new IllegalStateException("Execution status, step, or lease version changed");
                }
                version++;
            } catch (Exception e) {
                failure = new LeaseLostException("Could not renew lease for execution " + executionId
                        + " at step " + stepIndex, e);
                log.error("{}; the worker result will be discarded", failure.getMessage(), e);
            }
        }

        private void requireHealthy() {
            if (failure != null) {
                throw failure;
            }
        }

        public synchronized long version() {
            return version;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                future.cancel(false);
                // Joining through the monitor prevents a renewal racing completion.
                // One final guarded renewal catches changes since the last tick.
                renew();
                closed = true;
            }
            requireHealthy();
        }
    }

    static final class LeaseLostException extends RuntimeException {
        private LeaseLostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
