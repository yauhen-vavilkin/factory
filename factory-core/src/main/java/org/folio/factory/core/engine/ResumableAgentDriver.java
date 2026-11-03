package org.folio.factory.core.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.metrics.EngineMetrics;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/** Executes a single durable phase; the existing poller owns further scheduling. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResumableAgentDriver {
    private final AgentWorkerRegistry workers;
    private final AgentLeaseHeartbeat heartbeat;
    private final ResumableStepTransactions transactions;
    private final EngineMetrics metrics;

    public boolean run(PipelineExecution execution, FlowDescriptor flow, StepDescriptor step,
                       Supplier<AgentContext> contextBuilder) {
        long started = System.nanoTime();
        var invocation = UUID.randomUUID();
        AgentLeaseHeartbeat.Lease lease = null;
        boolean success = false;
        try {
            lease = heartbeat.start(execution);
            var context = invocationContext(execution, step, contextBuilder.get(), invocation, lease);
            AgentResult result;
            try (var closeable = lease) {
                result = workers.require(step.workerId()).execute(context);
            }
            boolean advance = transactions.complete(execution.getId(), execution.getCurrentStepIndex(),
                    lease.version(), invocation, flow, step, result);
            metrics.recordLlmTokens(step.workerId(), result.metrics());
            success = true;
            return advance;
        } catch (Exception error) {
            recordFailure(new Invocation(execution, flow, step, invocation), lease, error);
            return false;
        } finally {
            metrics.recordAgentStep(step.workerId(), success, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    /** Starts or replays the durable invocation and gives the worker its ownership and progress callbacks. */
    private AgentContext invocationContext(PipelineExecution execution, StepDescriptor step, AgentContext base,
                                           UUID invocation, AgentLeaseHeartbeat.Lease lease) {
        boolean replay = lease.checked(version -> transactions.start(execution.getId(),
                execution.getCurrentStepIndex(), version, step, invocation, base.inputs()));
        return new AgentContext(base.executionId(), base.stepId(), base.inputs(), base.triggerPayload(),
                base.config(), base.expectedOutputs(), new AgentContext.Invocation(invocation, replay,
                () -> lease.checked(version -> {
                    transactions.check(execution.getId(), execution.getCurrentStepIndex(), version);
                    return null;
                }), detail -> lease.checked(version -> {
                    transactions.progress(execution.getId(), execution.getCurrentStepIndex(), version,
                            step.stepId(), detail);
                    return null;
                })));
    }

    private void recordFailure(Invocation invocation, AgentLeaseHeartbeat.Lease lease, Exception error) {
        if (lease == null) {
            return;
        }
        var execution = invocation.execution();
        try {
            lease.close();
            transactions.fail(execution.getId(), execution.getCurrentStepIndex(), lease.version(),
                    invocation.id(), invocation.flow(), invocation.step(), error);
        } catch (Exception lost) {
            log.warn("Resumable invocation {} stopped without changing successor state", invocation.id());
        }
    }

    private record Invocation(PipelineExecution execution, FlowDescriptor flow, StepDescriptor step, UUID id) {
    }
}
