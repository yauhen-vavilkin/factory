package org.folio.factory.core.engine;

import org.folio.factory.core.agent.*;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.metrics.EngineMetrics;
import org.folio.factory.core.registry.model.*;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/** Executes a single durable phase; the existing poller owns further scheduling. */
@Component
public class ResumableAgentDriver {
    private static final Logger log = LoggerFactory.getLogger(ResumableAgentDriver.class);
    private final AgentWorkerRegistry workers;
    private final AgentLeaseHeartbeat heartbeat;
    private final ResumableStepTransactions transactions;
    private final EngineMetrics metrics;
    public ResumableAgentDriver(AgentWorkerRegistry workers, AgentLeaseHeartbeat heartbeat,
                               ResumableStepTransactions transactions, EngineMetrics metrics) {
        this.workers = workers; this.heartbeat = heartbeat; this.transactions = transactions; this.metrics = metrics;
    }
    public boolean run(PipelineExecution execution, FlowDescriptor flow, StepDescriptor step,
                       Supplier<AgentContext> contextBuilder) {
        long started = System.nanoTime();
        UUID invocation = UUID.randomUUID();
        AgentLeaseHeartbeat.Lease lease = null;
        boolean success = false;
        try {
            lease = heartbeat.start(execution);
            var ownedLease = lease;
            var base = contextBuilder.get();
            boolean replay = lease.checked(version -> transactions.start(execution.getId(),
                    execution.getCurrentStepIndex(), version, step, invocation, base.inputs()));
            var context = new AgentContext(base.executionId(), base.stepId(), base.inputs(), base.triggerPayload(),
                    base.config(), base.expectedOutputs(), new AgentContext.Invocation(invocation, replay,
                    () -> ownedLease.checked(version -> {
                        transactions.check(execution.getId(), execution.getCurrentStepIndex(), version);
                        return null;
                    }), detail -> ownedLease.checked(version -> {
                        transactions.progress(execution.getId(), execution.getCurrentStepIndex(), version, step.stepId(), detail);
                        return null;
                    })));
            AgentResult result;
            try (var closeable = lease) { result = workers.require(step.workerId()).execute(context); }
            boolean advance = transactions.complete(execution.getId(), execution.getCurrentStepIndex(),
                    lease.version(), invocation, flow, step, result);
            metrics.recordLlmTokens(step.workerId(), result.metrics());
            success = true;
            return advance;
        } catch (Exception error) {
            if (lease != null) {
                try {
                    lease.close();
                    transactions.fail(execution.getId(), execution.getCurrentStepIndex(), lease.version(),
                            invocation, flow, step, error);
                } catch (Exception lost) {
                    log.warn("Resumable invocation {} stopped without changing successor state", invocation);
                }
            }
            return false;
        } finally {
            metrics.recordAgentStep(step.workerId(), success, Duration.ofNanos(System.nanoTime() - started));
        }
    }
}
