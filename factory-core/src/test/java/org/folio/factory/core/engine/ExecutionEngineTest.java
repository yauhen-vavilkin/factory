package org.folio.factory.core.engine;

import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.metrics.EngineMetrics;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-of-chain completion semantics: the parent hand-off must only happen when
 * the COMPLETED transition actually took effect — a guard-refused transition
 * (concurrent cancel) must not resume a waiting parent on a dead child.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionEngineTest {

    private static final String FLOW_ID = "flow-under-test";
    private static final FlowDescriptor EMPTY_FLOW =
            new FlowDescriptor(FLOW_ID, FLOW_ID, "1.0.0", null, null, null, List.of(), null);

    @Mock
    FlowRegistry flowRegistry;

    @Mock
    AgentWorkerRegistry workerRegistry;

    @Mock
    StateManager stateManager;

    @Mock
    ArtifactStore artifactStore;

    @Mock
    AuditLog auditLog;

    @Mock
    HitlGateOpener hitlGateOpener;

    @Mock
    SubFlowInvoker subFlowInvoker;

    @Mock
    JsonMapper jsonMapper;

    @Mock
    EngineMetrics engineMetrics;

    @Mock
    AgentLeaseHeartbeat leaseHeartbeat;

    ExecutionEngine engine;
    AgentLeaseHeartbeat realHeartbeat;

    final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        engine = new ExecutionEngine(flowRegistry, workerRegistry, stateManager, artifactStore,
                auditLog, hitlGateOpener, subFlowInvoker, List.of(), jsonMapper, engineMetrics, leaseHeartbeat);
    }

    @AfterEach
    void shutdownHeartbeat() {
        if (realHeartbeat != null) {
            realHeartbeat.shutdown();
        }
    }

    private AgentWorker prepareAgent(PipelineExecution running) {
        return prepareAgent(running, List.of("result.md"));
    }

    private AgentWorker prepareAgent(PipelineExecution running, List<String> outputs) {
        realHeartbeat = new AgentLeaseHeartbeat(stateManager,
                new EngineProperties(null, null, null, 1, 1L, null, null));
        engine = new ExecutionEngine(flowRegistry, workerRegistry, stateManager, artifactStore,
                auditLog, hitlGateOpener, subFlowInvoker, List.of(), jsonMapper, engineMetrics, realHeartbeat);
        StepDescriptor step = new StepDescriptor("agent", StepType.AGENT, "worker", null, null,
                List.of(), outputs, null);
        when(stateManager.get(executionId)).thenReturn(running);
        when(flowRegistry.require(FLOW_ID)).thenReturn(new FlowDescriptor(FLOW_ID, FLOW_ID, "1.0.0",
                null, null, null, List.of(step), null));
        AgentWorker worker = mock(AgentWorker.class);
        when(workerRegistry.require("worker")).thenReturn(worker);
        return worker;
    }

    @Test
    void renewsThroughoutLongWorkerAndStopsAfterCompletion() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        AgentWorker worker = prepareAgent(running);
        AtomicInteger renewals = new AtomicInteger();
        when(stateManager.heartbeat(eq(running.getId()), eq(0), anyLong())).thenAnswer(call -> {
            assertThat(call.<Long>getArgument(2)).isEqualTo((long) renewals.getAndIncrement());
            return true;
        });
        when(worker.execute(any())).thenAnswer(call -> {
            // Four periodic renewals keep a worker alive longer than its one-second lease.
            await().atMost(Duration.ofSeconds(5)).until(() -> renewals.get() >= 5);
            return AgentResult.of("result.md", "done");
        });
        when(stateManager.advanceStep(eq(running.getId()), eq(0), eq(ExecutionStatus.RUNNING), anyLong())).thenAnswer(call -> {
            running.setStatus(ExecutionStatus.COMPLETED);
            return true;
        });

        engine.advance(executionId);

        int finalCount = renewals.get();
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2))
                .until(() -> renewals.get() == finalCount);
        verify(artifactStore).putMarkdown(running.getId(), "result.md", "done", "agent");
        verify(engineMetrics).recordAgentStep(eq("worker"), eq(true), any());
    }

    @Test
    void workerFailureStopsHeartbeatAndPreservesRetryHandling() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        AgentWorker worker = prepareAgent(running);
        when(stateManager.heartbeat(eq(running.getId()), eq(0), anyLong())).thenReturn(true);
        when(worker.execute(any())).thenThrow(new AgentExecutionException("worker failed"));

        engine.advance(executionId);

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(stateManager, times(2)).heartbeat(eq(running.getId()), eq(0), anyLong()));
        verify(stateManager).incrementRetry(running.getId(), "agent");
        verify(engineMetrics).recordAgentStep(eq("worker"), eq(false), any());
    }

    @Test
    void ownershipChangeDiscardsOutputsAndDoesNotRetry() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        AgentWorker worker = prepareAgent(running);
        when(stateManager.heartbeat(eq(running.getId()), eq(0), anyLong())).thenReturn(true, false);
        when(worker.execute(any())).thenReturn(AgentResult.of("result.md", "stale"));

        engine.advance(executionId);

        verifyNoInteractions(artifactStore);
        verify(stateManager, never()).incrementRetry(any(), any());
        verify(stateManager, never()).advanceStep(any(), anyInt(), any(), anyLong());
        verify(auditLog, never()).record(any(), eq(AuditEventType.STEP_COMPLETED), any(), any());
        verify(engineMetrics).recordAgentStep(eq("worker"), eq(false), any());
    }

    @Test
    void periodicRenewalFailureDiscardsWorkerFailureWithoutChangingNewOwner() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        AgentWorker worker = prepareAgent(running);
        AtomicInteger renewals = new AtomicInteger();
        when(stateManager.heartbeat(eq(running.getId()), eq(0), anyLong())).thenAnswer(call -> {
            if (renewals.incrementAndGet() > 1) {
                throw new IllegalStateException("database unavailable");
            }
            return true;
        });
        when(worker.execute(any())).thenAnswer(call -> {
            await().atMost(Duration.ofSeconds(3)).until(() -> renewals.get() > 1);
            throw new AgentExecutionException("worker failed too");
        });

        engine.advance(executionId);

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2))
                .until(() -> renewals.get() == 2);
        verify(stateManager, never()).incrementRetry(any(), any());
        verifyNoInteractions(artifactStore);
        verify(engineMetrics).recordAgentStep(eq("worker"), eq(false), any());
    }

    @Test
    void refusedAdvanceDoesNotRecordSuccessfulStep() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        AgentWorker worker = prepareAgent(running);
        when(stateManager.heartbeat(eq(running.getId()), eq(0), anyLong())).thenReturn(true);
        when(worker.execute(any())).thenReturn(AgentResult.of("result.md", "done"));

        engine.advance(executionId);

        verify(auditLog, never()).record(any(), eq(AuditEventType.STEP_COMPLETED), any(), any());
        verify(engineMetrics).recordAgentStep(eq("worker"), eq(false), any());
    }

    @Test
    void duplicateLocalDriverCannotEnterLiveWorkerOrMutateItsRetry() throws Exception {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        AgentWorker worker = prepareAgent(running);
        when(stateManager.heartbeat(eq(running.getId()), eq(0), anyLong())).thenReturn(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(worker.execute(any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return AgentResult.of("result.md", "done");
        });
        Thread first = Thread.ofVirtual().start(() -> engine.advance(executionId));
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            engine.advance(executionId);
            verify(worker, times(1)).execute(any());
            verify(auditLog, times(1)).record(eq(running.getId()), eq(AuditEventType.STEP_STARTED),
                    eq("agent"), any());
            verify(stateManager, never()).incrementRetry(any(), any());
            verify(auditLog, never()).record(any(), eq(AuditEventType.ESCALATED), any(), any());
            verifyNoInteractions(artifactStore);
        } finally {
            release.countDown();
            first.join(3000);
        }
        assertThat(first.isAlive()).isFalse();
    }

    @Test
    void conditionalReviewGateKeepsItsCompletedAuditWhenItParksTheStep() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        AgentWorker worker = prepareAgent(running, List.of());
        PipelineExecution parked = executionIn(ExecutionStatus.AWAITING_HITL);
        when(stateManager.get(running.getId())).thenReturn(parked);
        when(stateManager.heartbeat(eq(running.getId()), eq(0), anyLong())).thenReturn(true, false);
        when(worker.execute(any())).thenReturn(new AgentResult(java.util.Map.of(), java.util.Map.of("reviewOpened", true)));

        engine.advance(executionId);

        verify(auditLog).record(eq(running.getId()), eq(AuditEventType.STEP_COMPLETED), eq("agent"), any());
        verify(stateManager, never()).incrementRetry(any(), any());
        verify(engineMetrics).recordAgentStep(eq("worker"), eq(true), any());
    }

    private PipelineExecution executionIn(ExecutionStatus status) {
        PipelineExecution execution = new PipelineExecution(FLOW_ID, "1.0.0", null);
        execution.setStatus(status);
        return execution;
    }

    @Test
    void endOfChainCompletionResumesWaitingParent() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        when(stateManager.get(executionId)).thenReturn(running);
        when(flowRegistry.require(FLOW_ID)).thenReturn(EMPTY_FLOW);
        when(stateManager.transition(running.getId(), ExecutionStatus.COMPLETED, null))
                .thenReturn(executionIn(ExecutionStatus.COMPLETED));

        engine.advance(executionId);

        verify(subFlowInvoker).onChildCompleted(running.getId());
    }

    @Test
    void guardRefusedCompletionDoesNotResumeWaitingParent() {
        PipelineExecution running = executionIn(ExecutionStatus.RUNNING);
        when(stateManager.get(executionId)).thenReturn(running);
        when(flowRegistry.require(FLOW_ID)).thenReturn(EMPTY_FLOW);
        // The run was cancelled between the loop-top status read and the terminal
        // transition: the guard leaves it CANCELLED.
        when(stateManager.transition(running.getId(), ExecutionStatus.COMPLETED, null))
                .thenReturn(executionIn(ExecutionStatus.CANCELLED));

        engine.advance(executionId);

        verifyNoInteractions(subFlowInvoker);
    }
}
