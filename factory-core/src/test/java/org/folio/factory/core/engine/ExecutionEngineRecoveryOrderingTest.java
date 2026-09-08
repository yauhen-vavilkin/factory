package org.folio.factory.core.engine;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.RetryPolicy;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T25 S02: the engine closes the recovery loop — the step's durable attempt
 * ordinal (stateManager.nextAttempt) reaches the worker through
 * {@code AgentContext.attempt}, a downstream artifact-store failure
 * propagates WITH the worker's {@code recovery_locator} through the existing
 * step-failure channel, and the attempt-scoped bundle discard runs only after
 * the FULL putMarkdown loop succeeded. Plain JUnit: real ExecutionEngine +
 * real AgentWorkerRegistry, Mockito doubles for storage/audit, in-memory
 * StateManager.
 */
class ExecutionEngineRecoveryOrderingTest {

    private static final String FLOW_ID = "recovery-flow";
    private static final String STEP_ID = "coding";
    private static final String LOCATOR = "/recovery-root/exec-1/coding/attempt-1";
    private static final Map<String, String> OUTPUTS =
            Map.of("report.md", "# report", "patch.diff", "diff --git a/x b/x");
    private static final Map<String, Object> METRICS = Map.of("recovery_locator", LOCATOR);

    /** (a) persistence failure rides the step-failure channel with the locator, never discards. */
    @Test
    void persistenceFailurePropagatesLocatorThroughStepFailureChannelAndNeverDiscards() {
        ArtifactStore artifactStore = mock(ArtifactStore.class);
        when(artifactStore.putMarkdown(any(UUID.class), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("simulated artifact store outage"));
        AuditLog auditLog = mock(AuditLog.class);
        FakeStateManager state = new FakeStateManager(execution());
        StepRecoveryStore recoveryStore = mock(StepRecoveryStore.class);
        ExecutionEngine engine = engine(new CapturingWorker(), state, artifactStore, auditLog, recoveryStore);

        engine.advance(state.execution.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass((Class) Map.class);
        verify(auditLog).record(eq(state.execution.getId()), eq(AuditEventType.STEP_FAILED), eq(STEP_ID),
                detail.capture());
        assertThat(String.valueOf(detail.getValue().get("error")))
                .as("STEP_FAILED audit error must carry the worker-published recovery locator")
                .contains(LOCATOR)
                .contains(STEP_ID);
        assertThat(state.lastRetryError)
                .as("the same message rides into scheduleRetry's persisted errorMessage")
                .contains(LOCATOR);
        verify(recoveryStore, never()).discardAcknowledged(any(UUID.class), anyString(), anyInt());
    }

    /** (b) full success: discard only after the LAST putMarkdown, before STEP_COMPLETED. */
    @Test
    void fullSuccessDiscardsRecoveryBundleOnlyAfterEveryDeclaredOutputPersisted() {
        ArtifactStore artifactStore = mock(ArtifactStore.class);
        AuditLog auditLog = mock(AuditLog.class);
        FakeStateManager state = new FakeStateManager(execution());
        StepRecoveryStore recoveryStore = mock(StepRecoveryStore.class);
        ExecutionEngine engine = engine(new CapturingWorker(), state, artifactStore, auditLog, recoveryStore);

        engine.advance(state.execution.getId());

        InOrder inOrder = inOrder(artifactStore, recoveryStore, auditLog);
        inOrder.verify(artifactStore, times(2))
                .putMarkdown(eq(state.execution.getId()), anyString(), anyString(), eq(STEP_ID));
        inOrder.verify(recoveryStore).discardAcknowledged(state.execution.getId(), STEP_ID, 1);
        inOrder.verify(auditLog).record(eq(state.execution.getId()), eq(AuditEventType.STEP_COMPLETED),
                eq(STEP_ID), eq(METRICS));
        assertThat(state.execution.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    /** (c) attempt = the durable nextAttempt ordinal reaches the worker and the discard with matching scope. */
    @Test
    void attemptIsTheDurableAllocatedOrdinalAndDiscardMatchesTheAcknowledgedAttempt() {
        ArtifactStore artifactStore = mock(ArtifactStore.class);
        AtomicBoolean failFirstAttempt = new AtomicBoolean(true);
        when(artifactStore.putMarkdown(any(UUID.class), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    if (failFirstAttempt.get()) {
                        throw new RuntimeException("simulated artifact store outage");
                    }
                    return null;
                });
        AuditLog auditLog = mock(AuditLog.class);
        FakeStateManager state = new FakeStateManager(execution());
        StepRecoveryStore recoveryStore = mock(StepRecoveryStore.class);
        CapturingWorker worker = new CapturingWorker();
        ExecutionEngine engine = engine(worker, state, artifactStore, auditLog, recoveryStore);

        engine.advance(state.execution.getId());
        state.execution.setStatus(ExecutionStatus.RUNNING);
        failFirstAttempt.set(false);
        engine.advance(state.execution.getId());

        assertThat(worker.attempts)
                .as("worker sees 1-based durable ordinals allocated by stateManager.nextAttempt")
                .containsExactly(1, 2);
        verify(recoveryStore).discardAcknowledged(state.execution.getId(), STEP_ID, 2);
        verify(recoveryStore, never()).discardAcknowledged(state.execution.getId(), STEP_ID, 1);
    }

    private static PipelineExecution execution() {
        PipelineExecution execution = new PipelineExecution(FLOW_ID, "1.0.0", null);
        execution.setStatus(ExecutionStatus.RUNNING);
        return execution;
    }

    private static FlowDescriptor flow() {
        StepDescriptor step = new StepDescriptor(STEP_ID, StepType.AGENT, "stub-worker",
                null, null, List.of(), List.of("report.md", "patch.diff"), Map.of());
        return new FlowDescriptor(FLOW_ID, "Recovery Flow", "1.0.0", List.of(), null, null,
                List.of(step), new RetryPolicy(3, List.of(1L)));
    }

    private static ExecutionEngine engine(AgentWorker worker, StateManager stateManager,
            ArtifactStore artifactStore, AuditLog auditLog, StepRecoveryStore recoveryStore) {
        FlowRegistry flowRegistry = mock(FlowRegistry.class);
        when(flowRegistry.require(FLOW_ID)).thenReturn(flow());
        return new ExecutionEngine(flowRegistry,
                new AgentWorkerRegistry(List.of(worker), flowRegistry),
                stateManager,
                artifactStore,
                auditLog,
                mock(HitlGateOpener.class),
                mock(SubFlowInvoker.class),
                List.of(),
                JsonMapper.builder().build(),
                List.of(recoveryStore));
    }

    private static final class CapturingWorker implements AgentWorker {

        private final List<Integer> attempts = new ArrayList<>();

        @Override
        public String id() {
            return "stub-worker";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            attempts.add(context.attempt());
            return new AgentResult(OUTPUTS, METRICS);
        }
    }

    /** In-memory StateManager: one shared RUNNING execution plus retry bookkeeping. */
    private static final class FakeStateManager extends StateManager {

        private final PipelineExecution execution;
        private final Map<String, Integer> retries = new HashMap<>();
        private final Map<String, Integer> attemptOrdinals = new HashMap<>();
        private String lastRetryError;

        FakeStateManager(PipelineExecution execution) {
            super(null, null, null);
            this.execution = execution;
        }

        @Override
        public PipelineExecution get(UUID executionId) {
            return execution;
        }

        @Override
        public void heartbeat(UUID executionId) {
        }

        @Override
        public int retryCount(UUID executionId, String stepId) {
            return retries.getOrDefault(stepId, 0);
        }

        /**
         * T25 S12: mirrors the real allocator — read→merge+1→return — so the
         * fake state behaves like the persisted attempt_counts ordinal source
         * the engine now consumes.
         */
        @Override
        public int nextAttempt(UUID executionId, String stepId) {
            return attemptOrdinals.merge(stepId, 1, Integer::sum);
        }

        @Override
        public int incrementRetry(UUID executionId, String stepId) {
            return retries.merge(stepId, 1, Integer::sum);
        }

        @Override
        public void scheduleRetry(UUID executionId, long backoffSeconds, String errorMessage) {
            execution.setStatus(ExecutionStatus.PENDING);
            execution.setErrorMessage(errorMessage);
            lastRetryError = errorMessage;
        }

        @Override
        public PipelineExecution transition(UUID executionId, ExecutionStatus newStatus,
                Map<String, ?> auditDetail) {
            execution.setStatus(newStatus);
            return execution;
        }

        @Override
        public boolean advanceStep(UUID executionId, int expectedStepIndex, ExecutionStatus expectedStatus) {
            if (execution.getStatus() != expectedStatus
                    || execution.getCurrentStepIndex() != expectedStepIndex) {
                return false;
            }
            execution.setCurrentStepIndex(expectedStepIndex + 1);
            return true;
        }
    }
}
