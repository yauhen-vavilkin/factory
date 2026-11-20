package org.folio.factory.devfactory.runtime;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.tasks.AttemptDeadline;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.tasks.RecordingTaskAttempts;
import org.folio.factory.devfactory.repository.SelectedRepositoryPreflight;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.IntakeResolveWorker;
import org.folio.factory.devfactory.worker.InitialCodingAttempt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;
import org.folio.factory.devfactory.DevFlowSettings;
import org.folio.factory.devfactory.CodingServices;
import org.folio.factory.devfactory.DevFactoryHooks;

class DevelopReadinessTest {
    private static final String MODEL_KEY = "sk-test-0123456789abcdef";

    private static final DevRuntimeProperties MAVEN =
            new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null, null);
    private static final DevRuntimeProperties YARN = new DevRuntimeProperties(
            Map.of("unit", List.of("yarn", "test", "--maxWorkers=2")), null, 60, null, Map.of(),
            Map.of("unit", Toolchain.YARN), null, 0);
    private static final PinnedLockfile LOCKFILE = PinnedLockfile.of(
            "# yarn lockfile v1\n\nleft-pad@^1.3.0:\n  version \"1.3.0\"\n".getBytes());

    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aTransientBaselineFailureIsRetriedBeforeCodingAndACodingFailureIsNotRetried(boolean codingFails) {
        var audit = mock(AuditLog.class);
        var harness = taskHarness(RecordingTaskAttempts.none(), CodingTranscripts.none(), audit);
        when(harness.baseline().executeKeepingTail(anyList(), anyInt(), anyInt(), any()))
                .thenReturn(new Processes.Result(1, "[ERROR] Could not transfer artifact org.example:library:jar:1"
                                + " from/to central: Read timed out"),
                        new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        if (codingFails) {
            when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                    .thenAnswer(invocation -> {
                        Consumer<Map<String, Object>> observer = invocation.getArgument(3);
                        new PiProgress("secret", observer).accept("""
                                {"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"private secret"}],"usage":{"input":10,"cacheRead":2,"cacheWrite":1,"output":3,"cost":{"total":0.125}}}}
                                """);
                        throw new AgentExecutionException("Provider failed");
                    });
        } else {
            when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                    .thenReturn(CodingOutcome.completed("Done", Map.of("inputTokens", 10L, "cacheReadTokens", 2L,
                            "cacheWriteTokens", 1L, "outputTokens", 3L, "costUsd", new BigDecimal("0.125"))));
            when(harness.freezer().freeze(eq("repo"), anyString(), anyString(), any(), anyBoolean(), any()))
                    .thenReturn(new Candidate("repo", "a".repeat(40),
                            "b".repeat(40), Candidate.sha256("patch"), "patch", "CANDIDATE_UNVERIFIED"));
        }
        var worker = harness.worker();
        var context = harness.context();

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("coding has not started");
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        var result = worker.execute(context);

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(codingFails ? "DEVELOPMENT_FAILED" : "CANDIDATE_UNVERIFIED");
        assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_PASSED");
        verify(harness.coding(), times(1)).code(any(), any(CodingRequest.class), anyInt(), any(), any());
        if (codingFails) {
            // The usage of a failed coding call is still on record.
            Map<String, Object> expectedUsageEvent = Map.of("activity", "coding_usage", "inputTokens", 10L,
                    "cacheReadTokens", 2L, "cacheWriteTokens", 1L, "outputTokens", 3L,
                    "costUsd", new BigDecimal("0.125"));
            verify(audit).record(
                    eq(context.executionId()), eq(AuditEventType.RUNTIME_PROGRESS), eq("implement"),
                    argThat(detail -> detail.entrySet().containsAll(expectedUsageEvent.entrySet())
                            && detail.containsKey("attemptId")));
        } else {
            assertThat(result.metrics()).containsEntry("inputTokens", 10L).containsEntry("cacheReadTokens", 2L)
                    .containsEntry("cacheWriteTokens", 1L).containsEntry("outputTokens", 3L);
        }
        verify(harness.baseline(), times(2)).close();
        verify(harness.codingWorkload()).close();
    }

    @Test
    void transientBaselineSignalsEngineRetryAndMakesZeroCodingCalls() {
        verifyFailedBaseline(Toolchain.MAVEN, "[ERROR] Could not transfer artifact net.sf.saxon:Saxon-HE:jar:12.9 "
                + "from/to central (https://repo.maven.apache.org/maven2): "
                + "Premature end of Content-Length delimited message body", true);
    }

    @Test
    void compilationFailureDoesNotRetryOrStartCoding() {
        verifyFailedBaseline(Toolchain.MAVEN, "[ERROR] Compilation failure: cannot find symbol", false);
    }

    private void verifyFailedBaseline(Toolchain toolchain, String failure, boolean retryable) {
        var audit = mock(AuditLog.class);
        var settings = toolchain == Toolchain.YARN ? YARN : MAVEN;
        var harness = taskHarness(RecordingTaskAttempts.none(), CodingTranscripts.none(), audit,
                SelectedRepositoryPreflight.none(), settings);
        var command = settings.verificationCommand("unit");
        var baseline = harness.baseline();
        when(baseline.executeKeepingTail(anyList(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<String> observer = invocation.getArgument(3);
                    observer.accept(toolchain == Toolchain.YARN ? "[2/4] Fetching packages..."
                            : "[INFO] --- compiler:3.16.0:compile (default-compile) @ module ---");
                    return new Processes.Result(1, failure);
                });
        var worker = harness.worker();
        var context = harness.context();
        if (toolchain == Toolchain.YARN) gitWorkspace(true);
        if (retryable) {
            // Repeated worker attempts retain the configured command and do not add a global -U.
            for (int attempt = 0; attempt < 2; attempt++) {
                if (toolchain == Toolchain.YARN) gitWorkspace(true);
                assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class)
                        .hasMessageContaining("Starting build hit a network/download failure")
                        .hasMessageContaining("coding has not started")
                        .hasMessageContaining(toolchain == Toolchain.YARN ? "ETIMEDOUT" : "incomplete download");
            }
            verify(baseline, times(2)).executeKeepingTail(eq(command), eq(60), eq(Processes.OUTPUT_LIMIT), any());
        } else {
            var result = worker.execute(context);
            assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT");
            assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_FAILED", "output");
        }
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        verify(audit, atLeastOnce()).record(eq(context.executionId()),
                eq(AuditEventType.RUNTIME_PROGRESS), eq("implement"), anyMap());
        verify(baseline, times(retryable ? 2 : 1)).close();
    }

    @Test
    void invalidCodingProfileReconcilesOwnedStepButNeverChecksOutOrBuilds() {
        var harness = taskHarness(RecordingTaskAttempts.none());
        doThrow(new IllegalStateException("secret must not persist")).when(harness.coding()).requireConfigured(any());

        var result = harness.attempt().start(harness.context(), request());

        assertThat(result.candidate())
                .contains("BLOCKED_ENVIRONMENT")
                .doesNotContain("secret must not persist");
        var order = inOrder(harness.docker(), harness.coding(), harness.scope());
        order.verify(harness.docker()).beginStep(harness.context().executionId(), "implement");
        order.verify(harness.coding()).requireConfigured(any());
        order.verify(harness.scope()).close();
        verifyNoInteractions(harness.freezer());
        verify(harness.scope(), never()).createTrusted(anyString(), any(), any());
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
    }

    @Test
    void aTaskExecutionClaimsOnePaidStartAndAReplayedStepCannotStartAnother() {
        var taskAttempts = RecordingTaskAttempts.taskRun("repo");
        var harness = taskHarness(taskAttempts);

        var first = harness.worker().execute(harness.context());
        var replay = harness.worker().execute(harness.context());

        assertThat(first.outputs().get(DevelopWorker.CANDIDATE)).contains("NEEDS_DECISION");
        assertThat(taskAttempts.paidStartClaims).isEqualTo(2);
        assertThat(replay.outputs().get(DevelopWorker.CANDIDATE))
                .contains("PAID_START_CONSUMED")
                .contains(TaskStops.REPLAYED_CODING_REASON);
        verify(harness.coding(), times(1)).code(any(), any(), anyInt(), any(), any());
        verify(harness.docker(), times(2)).beginStep(any(), any());
        verify(harness.freezer(), times(2)).checkout(anyString(), anyString());
        verify(harness.freezer(), never()).freeze(anyString(), anyString(), anyString(), any(), eq(false), any());
        assertThat(taskAttempts.stops).singleElement().satisfies(stop -> {
            assertThat(stop).isEqualTo("implement: " + TaskStops.REPLAYED_CODING_REASON);
            assertThat(stop).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
        });
    }

    @Test
    void aTaskBaselineFailureKeepsTheClaimUnconsumedAndTheEngineRetrySucceeds() {
        var taskAttempts = RecordingTaskAttempts.taskRun("repo");
        var harness = taskHarness(taskAttempts);
        when(harness.baseline().executeKeepingTail(anyList(), anyInt(), anyInt(), any()))
                .thenReturn(
                        new Processes.Result(1, "[ERROR] Could not transfer artifact org.example:library:jar:1"
                                + " from/to central: Read timed out"),
                        new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        var worker = harness.worker();
        var context = harness.context();

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Starting build hit a network/download failure")
                .hasMessageContaining("coding has not started");
        assertThat(taskAttempts.paidStartClaims).isZero();
        assertThat(taskAttempts.stops).isEmpty();

        var retried = worker.execute(context);

        assertThat(retried.outputs().get(DevelopWorker.CANDIDATE)).contains("NEEDS_DECISION");
        assertThat(retried.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_PASSED");
        assertThat(taskAttempts.paidStartClaims).isEqualTo(1);
        assertThat(taskAttempts.stops).isEmpty();
        verify(harness.coding(), times(1)).code(any(), any(), anyInt(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedRepositoryStopsBeforeCheckoutForInitialAttemptAndContinuation(boolean continuation) {
        var selectedRepository = mock(SelectedRepositoryPreflight.class);
        var harness = taskHarness(RecordingTaskAttempts.none(), CodingTranscripts.none(), mock(AuditLog.class),
                selectedRepository);
        var context = harness.context();
        var request = request();
        doThrow(new IllegalStateException("credential-secret must not persist"))
                .when(selectedRepository)
                .requireRepositoryReady("repo", request.repository().baseSha());
        var readiness = JsonMapper.builder().build().readTree("{\"state\":\"BASELINE_PASSED\"}");

        var result = continuation
                ? harness.attempt().retry(context, request, readiness, null)
                : harness.attempt().start(context, request);

        assertThat(result.candidate())
                .contains("BLOCKED_ENVIRONMENT")
                .doesNotContain("credential-secret");
        assertThat(result.outcome().failure().message()).doesNotContain("credential-secret");
        if (continuation) {
            assertThat(result.readiness()).contains("BASELINE_PASSED");
        }
        var order = inOrder(harness.docker(), harness.coding(), selectedRepository, harness.scope());
        order.verify(harness.docker()).beginStep(context.executionId(), "implement");
        order.verify(harness.coding()).requireConfigured(any());
        order.verify(selectedRepository).requireRepositoryReady("repo", "a".repeat(40));
        order.verify(harness.scope()).close();
        verifyNoInteractions(harness.freezer());
        verify(harness.scope(), never()).createTrusted(anyString(), any(), any());
        verify(harness.scope(), never()).createSeeded(anyString(), any(), any(), anyString());
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
    }

    @Test
    void anExpiredTaskDeadlinePreventsEveryStageSideEffect() {
        var taskAttempts = RecordingTaskAttempts.taskRun("repo")
                .withDeadline(new AttemptDeadline(Instant.now().minusSeconds(1)));
        var harness = taskHarness(taskAttempts);

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(TaskStops.DEADLINE_STATE)
                .contains(TaskStops.deadlineReason("preparation"));
        verify(harness.freezer(), never()).checkout(anyString(), anyString());
        verify(harness.scope(), never()).createTrusted(anyString(), any(), any());
        verify(harness.scope(), never()).createSeeded(anyString(), any(), any(), anyString());
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        assertThat(taskAttempts.stops).singleElement().satisfies(stop -> {
            assertThat(stop).startsWith("implement: ");
            assertThat(stop).contains(TaskStops.DEADLINE_START);
        });
    }

    @Test
    void aDeadlineExpiringDuringBaselinePreventsCodingWithoutConsumingThePaidStart() {
        var now = new AtomicReference<>(Instant.now());
        var clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        var taskAttempts = RecordingTaskAttempts.taskRun("repo")
                .withDeadline(new AttemptDeadline(now.get().plusSeconds(3)))
                .withClock(clock);
        var harness = taskHarness(taskAttempts);
        when(harness.baseline().executeKeepingTail(anyList(), anyInt(), anyInt(), any())).thenAnswer(
                invocation -> {
                    now.set(now.get().plusSeconds(4));
                    return new Processes.Result(0, "[INFO] BUILD SUCCESS");
                });

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(TaskStops.DEADLINE_STATE)
                .contains(TaskStops.deadlineReason("coding"));
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        assertThat(taskAttempts.paidStartClaims).isZero();
        assertThat(taskAttempts.stops).anyMatch(stop -> stop.contains(TaskStops.deadlineReason("coding")));
    }

    @Test
    void aDeadlineExpiringWhileTheAgentCodesStopsBeforeTheChangesAreSavedInPlainWords() {
        var now = new AtomicReference<>(Instant.now());
        var clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        var taskAttempts = RecordingTaskAttempts.taskRun("repo")
                .withDeadline(new AttemptDeadline(now.get().plusSeconds(3)))
                .withClock(clock);
        var harness = taskHarness(taskAttempts);
        when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any())).thenAnswer(
                invocation -> {
                    now.set(now.get().plusSeconds(4));
                    return CodingOutcome.completed("Done", Map.of());
                });

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(TaskStops.deadlineReason("saving the agent's changes"))
                .doesNotContain("candidate freeze");
        verify(harness.freezer(), never()).freeze(anyString(), anyString(), anyString(), any(), anyBoolean(), any());
        assertThat(taskAttempts.stops).singleElement()
                .isEqualTo("implement: " + TaskStops.deadlineReason("saving the agent's changes"));
    }

    @Test
    void theRemainingBudgetCapsEveryStageTimeoutBelowTheConfiguredPerOperationLimit() {
        var now = Instant.parse("2026-10-23T10:00:00Z");
        var taskAttempts = RecordingTaskAttempts.taskRun("repo")
                .withDeadline(new AttemptDeadline(now.plusSeconds(45)))
                .withClock(Clock.fixed(now, java.time.ZoneOffset.UTC));
        var harness = taskHarness(taskAttempts);

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("NEEDS_DECISION");
        // The configured limit is 60 seconds; 45 are left of the attempt.
        var baselineTimeout = ArgumentCaptor.forClass(Integer.class);
        verify(harness.baseline()).executeKeepingTail(anyList(), baselineTimeout.capture(), anyInt(), any());
        assertThat(baselineTimeout.getValue()).isEqualTo(45);
        var codingTimeout = ArgumentCaptor.forClass(Integer.class);
        verify(harness.coding()).code(any(), any(CodingRequest.class), codingTimeout.capture(), any(), any());
        assertThat(codingTimeout.getValue()).isEqualTo(45);
    }

    @Test
    void eachCodingRunWritesItsTranscriptUnderTheExecutionAndTheRunAttemptId() {
        var opened = new java.util.ArrayList<String>();
        var entries = new java.util.ArrayList<CodingTranscripts.Entry>();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        CodingTranscripts transcripts = (executionId, runId, stepId) -> {
            opened.add(executionId + "/" + runId + "/" + stepId);
            return new CodingTranscripts.Sink() {
                @Override public void add(CodingTranscripts.Entry entry) { entries.add(entry); }
                @Override public void close() { closed.set(true); }
            };
        };
        var harness = taskHarness(RecordingTaskAttempts.taskRun("repo"), transcripts);
        when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any())).thenAnswer(call -> {
            CodingTranscripts.Sink sink = call.getArgument(4);
            sink.add(new CodingTranscripts.Entry("prompt", Instant.now(), Map.of("text", "Implement")));
            assertThat(closed).isFalse();
            return CodingOutcome.failed("TURN_LIMIT", "Claude Code used all 10 turns before it finished the task."
                    + " Retry with more turns or a different effort.", Map.of());
        });

        var result = harness.worker().execute(harness.context());

        String runId = result.metrics().get("attemptId").toString();
        assertThat(opened).containsExactly(harness.context().executionId() + "/" + runId + "/implement");
        assertThat(entries).extracting(CodingTranscripts.Entry::kind).containsExactly("prompt");
        assertThat(closed).isTrue();
        assertThat(result.outputs().get(DevelopWorker.OUTCOME))
                .contains("Claude Code used all 10 turns before it finished the task.");
        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains("Retry with more turns or a different effort.");
    }

    @Test
    void aCommandOverTheOutputLimitStopsTheAttemptWithOnePlainReason() {
        var harness = taskHarness(RecordingTaskAttempts.taskRun("repo"));
        when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of()));
        when(harness.freezer().freeze(anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException(Processes.OUTPUT_LIMIT_EXCEEDED));

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains("DEVELOPMENT_FAILED", "A command printed more output than Factory accepts")
                .doesNotContain("Development stopped", Processes.OUTPUT_LIMIT_EXCEEDED);
    }

    @Test
    void anUnexpectedErrorIsTheWholeReasonWithoutALayerPrefix() {
        var harness = taskHarness(RecordingTaskAttempts.taskRun("repo"));
        when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of()));
        when(harness.freezer().freeze(anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("Candidate contains no changes"));

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains("Candidate contains no changes").doesNotContain("Development stopped");
    }

    @Test
    void aPatchThatAddsAModelsApiKeyIsRefusedWithTheFileAndKeepsTheSpend() {
        var harness = secretCheckingHarness();
        when(harness.freezer().freeze(anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(candidateAdding("src/main/resources/application.yml", "api-key: " + MODEL_KEY));

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains("DEVELOPMENT_FAILED", TaskStops.secretInChangesReason("src/main/resources/application.yml"))
                .doesNotContain(MODEL_KEY, Candidate.UNVERIFIED);
        assertThat(result.metrics()).containsEntry("inputTokens", 10L);
    }

    @Test
    void aPatchThatHoldsOnlyTheShortPublicWordOfALocalProxyIsKept() {
        var harness = secretCheckingHarness();
        when(harness.freezer().freeze(anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(candidateAdding("README.md", "Start the proxy with the token codemie-proxy."));

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains(Candidate.UNVERIFIED);
    }

    /** The agent finishes, and the runtime knows the model key and the public word of a local proxy. */
    private TaskHarness secretCheckingHarness() {
        var harness = taskHarness(RecordingTaskAttempts.taskRun("repo"));
        when(harness.coding().holdsSecret(anyString())).thenAnswer(
                call -> CodingRuntime.holdsAny(call.getArgument(0), List.of(MODEL_KEY, "codemie-proxy")));
        when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of("inputTokens", 10L)));
        return harness;
    }

    private static Candidate candidateAdding(String file, String line) {
        var patch = "diff --git a/" + file + " b/" + file + "\n--- a/" + file + "\n+++ b/" + file
                + "\n@@ -1 +1,2 @@\n existing\n+" + line + "\n";
        return new Candidate("repo", "a".repeat(40), "b".repeat(40), Candidate.sha256(patch), patch,
                Candidate.UNVERIFIED);
    }

    @Test
    void aFailedActivityWriteDuringCodingReachesTheRuntimeAndTheAttemptGoesOn() {
        var audit = mock(AuditLog.class);
        doThrow(new IllegalStateException("database down"))
                .when(audit).record(any(), any(), any(), org.mockito.ArgumentMatchers.<Map<String, ?>>any());
        var harness = taskHarness(RecordingTaskAttempts.taskRun("repo"), CodingTranscripts.none(), audit);
        var reported = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any())).thenAnswer(call -> {
            Consumer<Map<String, Object>> progress = call.getArgument(3);
            // The runtime gets the failure, so it can log it once and note it in the transcript.
            reported.set(catchThrowable(() -> progress.accept(Map.of("activity", "agent_start"))));
            return CodingOutcome.needsDecision(new CodingOutcome.Decision(
                    CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Which limit?", List.of(), "Evidence"), "",
                    Map.of());
        });

        var result = harness.worker().execute(harness.context());

        assertThat(reported.get()).isInstanceOf(IllegalStateException.class).hasMessage("database down");
        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("NEEDS_DECISION");
    }

    @Test
    void aTaskAttemptThatLostItsSlotStopsWithoutAnyStageSideEffect() {
        var taskAttempts = RecordingTaskAttempts.taskRun("repo");
        taskAttempts.ownsSlot = false;
        var harness = taskHarness(taskAttempts);

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(TaskStops.INSPECTION_STATE)
                .contains("no longer owns the installation's active slot");
        verify(harness.freezer(), never()).checkout(anyString(), anyString());
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        assertThat(taskAttempts.stops).anyMatch(stop -> stop.contains("no longer owns"));
    }

    @Test
    void testFailureDoesNotRetryOrStartCoding() {
        verifyFailedBaseline(Toolchain.MAVEN, "[ERROR] There are test failures", false);
    }

    @Test
    void repositoryNotFoundDoesNotRetryOrStartCoding() {
        verifyFailedBaseline(Toolchain.MAVEN, "[ERROR] Could not find artifact org.example:missing:jar:1 in folio-nexus\n"
                + "[ERROR] Could not find artifact org.example:missing:jar:1 in index-data-nexus", false);
    }

    @Test
    void transientYarnInstallSignalsEngineRetryAndMakesZeroCodingCalls() {
        verifyFailedBaseline(Toolchain.YARN, "error An unexpected error occurred: \"https://repository.folio.org/"
                + "repository/npm-folioci/@folio%2fstripes/-/stripes.tgz: ETIMEDOUT\".", true);
    }

    @Test
    void yarnTestFailureDoesNotRetryOrStartCoding() {
        verifyFailedBaseline(Toolchain.YARN, "FAIL src/api.test.js\n  Error: getaddrinfo ENOTFOUND api.example\n"
                + "Tests:       1 failed, 3 passed, 4 total\nerror Command failed with exit code 1.", false);
    }

    @Test
    void yarnPackageNotFoundDoesNotRetryOrStartCoding() {
        verifyFailedBaseline(Toolchain.YARN,
                "error Couldn't find package \"left-pad@^9\" required by \"app\" on the \"npm\" registry.", false);
    }

    @Test
    void yarnLockfileErrorDoesNotRetryOrStartCoding() {
        verifyFailedBaseline(Toolchain.YARN,
                "error Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`.", false);
    }

    @Test
    void yarnCodingContainerIsPreparedBeforeTheAgentAndTheRequestNamesTheVerificationCommand() {
        var harness = yarnHarness(new Processes.Result(0, "Done in 14s."), true);

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("CANDIDATE_UNVERIFIED");
        var request = ArgumentCaptor.forClass(CodingRequest.class);
        var order = inOrder(harness.codingWorkload(), harness.coding());
        order.verify(harness.codingWorkload()).execute(install(), 60, Processes.OUTPUT_LIMIT, null);
        order.verify(harness.coding()).code(eq(harness.codingWorkload()), request.capture(), anyInt(), any(), any());
        assertThat(request.getValue().constraints().verificationCommand()).isEqualTo("yarn test --maxWorkers=2");
        assertThat(request.getValue().constraints().dependenciesInstalled()).isTrue();
    }

    @Test
    void moduleWithoutALockfileIsPinnedByTheStartingBuildAndSeededBeforeTheCodingInstall() {
        var harness = yarnHarness(new Processes.Result(0, "Done in 14s."), false);
        String blob = CandidateFreezer.trackedBlob(workspace, "package.json");

        var result = harness.worker().execute(harness.context());

        var pinned = PinnedLockfile.parse(result.outputs().get(DevelopWorker.LOCKFILE)).orElseThrow();
        assertThat(pinned.sha256()).isEqualTo(LOCKFILE.sha256());
        assertThat(pinned.packageJsonBlob()).isEqualTo(blob).hasSize(40);
        assertThat(result.outputs().get(DevelopWorker.READINESS))
                .contains("\"lockfile\":\"PINNED\"", "\"lockfileSha256\":\"" + LOCKFILE.sha256() + "\"")
                .doesNotContain("--frozen-lockfile'");
        var order = inOrder(harness.codingWorkload(), harness.coding());
        order.verify(harness.codingWorkload()).seedLockfile(pinned);
        order.verify(harness.codingWorkload()).execute(YARN.installCommand("unit", InstallMode.FROZEN), 60,
                Processes.OUTPUT_LIMIT, null);
        order.verify(harness.coding()).code(eq(harness.codingWorkload()), any(CodingRequest.class), anyInt(), any(),
                any());
    }

    @Test
    void moduleThatTracksALockfileIsInstalledFrozenAndPinsNothing() {
        var harness = yarnHarness(new Processes.Result(0, "Done in 14s."), true);

        var result = harness.worker().execute(harness.context());

        assertThat(PinnedLockfile.parse(result.outputs().get(DevelopWorker.LOCKFILE))).isEmpty();
        assertThat(result.outputs().get(DevelopWorker.LOCKFILE)).contains("NOT_NEEDED");
        assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("\"lockfile\":\"TRACKED\"")
                .doesNotContain("lockfileSha256");
        verify(harness.codingWorkload(), never()).seedLockfile(any());
    }

    @Test
    void clarificationRestartSeedsTheLockfileFromTheArtifactWithoutANewStartingBuild() {
        var harness = yarnHarness(new Processes.Result(0, "Done in 14s."), false);
        var pinned = LOCKFILE.forPackageJson(CandidateFreezer.trackedBlob(workspace, "package.json"));

        harness.attempt().retry(harness.context(), request(), JsonMapper.builder().build().readTree("{}"),
                pinned.toArtifact());

        verify(harness.codingWorkload()).seedLockfile(pinned);
        verify(harness.baseline(), never()).executeKeepingTail(any(), anyInt(), anyInt(), any());
    }

    @Test
    void unusableGeneratedLockfileBlocksBeforeModelSpend() {
        var harness = yarnHarness(new Processes.Result(0, "Done in 14s."), false);
        when(harness.baseline().readLockfile())
                .thenThrow(new IllegalStateException("The generated yarn.lock is unusable: Not a usable yarn lockfile"));

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT")
                .contains("could not pin the dependency lockfile");
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
    }

    @Test
    void seedCopyFailureBlocksTheEnvironmentWithoutModelSpendAndKeepsThePin() {
        var harness = yarnHarness(new Processes.Result(0, "Done in 14s."), false);
        var message = DockerWorkloads.seedFailure(300, "factory-dev-yarn-cache", 9L * 1024 * 1024,
                "Command exceeded 300 seconds");
        when(harness.scope().createSeeded(any(), any(), any(), eq("coding")))
                .thenThrow(new DockerWorkloads.CacheSeedException(message, new IllegalStateException("x")));

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains("BLOCKED_ENVIRONMENT", "(9.0 GB)", "limit 300 s");
        assertThat(PinnedLockfile.parse(result.outputs().get(DevelopWorker.LOCKFILE))).isPresent();
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
    }

    @Test
    void failedInstallInTheCodingContainerStopsBeforeAnyModelCall() {
        var harness = yarnHarness(new Processes.Result(1,
                "error Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`."), true);

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT")
                .contains("Workspace dependency install failed before model spend")
                .contains("lockfile needs to be updated");
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        verify(harness.codingWorkload()).close();
    }

    @Test
    void registryTimeoutDuringTheCodingInstallAsksTheEngineToRetryWithoutAModelCall() {
        var harness = yarnHarness(new Processes.Result(1,
                "error An unexpected error occurred: \"https://registry.example/pkg.tgz: ETIMEDOUT\"."), true);

        assertThatThrownBy(() -> harness.worker().execute(harness.context()))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Workspace dependency install hit a network/download failure")
                .hasMessageContaining("coding has not started");
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        verify(harness.codingWorkload()).close();
    }

    /** A yarn task whose starting build passes; the coding container's install returns {@code installResult}. */
    private TaskHarness yarnHarness(Processes.Result installResult, boolean trackedLockfile) {
        gitWorkspace(trackedLockfile);
        var harness = taskHarness(RecordingTaskAttempts.none(), CodingTranscripts.none(), mock(AuditLog.class),
                SelectedRepositoryPreflight.none(), YARN);
        when(harness.baseline().readLockfile())
                .thenReturn(trackedLockfile ? Optional.empty() : Optional.of(LOCKFILE));
        when(harness.codingWorkload().execute(anyList(), anyInt(), anyInt(), any())).thenReturn(installResult);
        when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of()));
        when(harness.freezer().freeze(eq("repo"), anyString(), anyString(), any(), eq(false), eq(Toolchain.YARN)))
                .thenReturn(new Candidate("repo", "a".repeat(40), "b".repeat(40), Candidate.sha256("patch"),
                        "patch", "CANDIDATE_UNVERIFIED"));
        return harness;
    }

    private static List<String> install() {
        return YARN.installCommand("unit");
    }

    /**
     * The base checkout: with a tracked lockfile, just that file; without one, a real Git repository,
     * so the package.json blob is the one a pinned lockfile records. The worker removes it afterwards.
     */
    private void gitWorkspace(boolean trackedLockfile) {
        try {
            Files.createDirectories(workspace);
            if (trackedLockfile) {
                Files.writeString(workspace.resolve("yarn.lock"), "# yarn lockfile v1\n");
                return;
            }
            CandidateFreezer.git(workspace, "init", "--quiet");
            Files.writeString(workspace.resolve("package.json"), "{\"name\":\"fixture\"}");
            CandidateFreezer.git(workspace, "add", "--all");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Shared task harness: docker, freezer and coding stubs allow the attempt to
     * run through baseline into coding; individual tests override specific stubs
     * or the recording task policy state.
     */
    private TaskHarness taskHarness(RecordingTaskAttempts taskAttempts) {
        return taskHarness(taskAttempts, CodingTranscripts.none());
    }

    private TaskHarness taskHarness(RecordingTaskAttempts taskAttempts, CodingTranscripts transcripts) {
        return taskHarness(taskAttempts, transcripts, mock(AuditLog.class));
    }

    private TaskHarness taskHarness(RecordingTaskAttempts taskAttempts, CodingTranscripts transcripts, AuditLog audit) {
        return taskHarness(taskAttempts, transcripts, audit, SelectedRepositoryPreflight.none());
    }

    private TaskHarness taskHarness(RecordingTaskAttempts taskAttempts, CodingTranscripts transcripts, AuditLog audit,
                                    SelectedRepositoryPreflight selectedRepository) {
        return taskHarness(taskAttempts, transcripts, audit, selectedRepository, MAVEN);
    }

    private TaskHarness taskHarness(RecordingTaskAttempts taskAttempts, CodingTranscripts transcripts, AuditLog audit,
                                    SelectedRepositoryPreflight selectedRepository, DevRuntimeProperties settings) {
        var repository = new DevFactoryProperties.Repository(
                "owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of(), null);
        var properties = new DevFactoryProperties(null, new TreeMap<>(Map.of("repo", repository)));
        var docker = mock(DockerWorkloads.class);
        var scope = mock(DockerWorkloads.StepScope.class);
        var baseline = mock(DockerWorkloads.Workload.class);
        var codingWorkload = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        var codec = new FrontmatterCodec();
        var context = context(codec, request());
        when(coding.image()).thenReturn("coding:image");
        when(coding.identity(any())).thenReturn(Map.of("runtime", "stub", "image", "coding:image"));
        when(docker.beginStep(context.executionId(), "implement")).thenReturn(scope);
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(scope.createTrusted(anyString(), any(), any())).thenReturn(baseline);
        when(scope.createSeeded(anyString(), any(), any(), anyString()))
                .thenReturn(codingWorkload);
        when(baseline.executeKeepingTail(anyList(), anyInt(), anyInt(), any()))
                .thenReturn(new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        when(coding.code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.needsDecision(new CodingOutcome.Decision(
                        CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Which limit?", List.of(), "Evidence"), "",
                        Map.of()));
        var attempt = new InitialCodingAttempt(new DevFlowSettings(properties, settings),
                new CodingServices(coding, docker, freezer), codec, audit,
                new DevFactoryHooks(TaskAttemptPolicy.none(), (modelId, model) -> Map.of(), transcripts,
                        selectedRepository), taskAttempts.guard());
        return new TaskHarness(docker, scope, baseline, codingWorkload, freezer, coding, attempt,
                new DevelopWorker(attempt, codec), context, settings);
    }

    private record TaskHarness(
            DockerWorkloads docker,
            DockerWorkloads.StepScope scope,
            DockerWorkloads.Workload baseline,
            DockerWorkloads.Workload codingWorkload,
            CandidateFreezer freezer,
            CodingRuntime coding,
            InitialCodingAttempt attempt,
            DevelopWorker worker,
            AgentContext context,
            DevRuntimeProperties settings) {
    }


    private static AgentContext context(FrontmatterCodec codec, CodingRequest request) {
        String brief = codec.render(
                Map.of(
                        "state", "INTAKE_READY",
                        "repository", Map.of("key", "repo", "base_sha", request.repository().baseSha())),
                "Task");
        var briefArtifact = new ArtifactContent(
                IntakeResolveWorker.TASK_BRIEF, 1, "text/markdown", brief);
        var requestArtifact = new ArtifactContent(
                IntakeResolveWorker.CODING_REQUEST, 1, "application/json",
                JsonMapper.builder().build().writeValueAsString(request));
        Map<String, ArtifactContent> inputs = Map.of(
                IntakeResolveWorker.TASK_BRIEF, briefArtifact,
                IntakeResolveWorker.CODING_REQUEST, requestArtifact);
        return new AgentContext(UUID.randomUUID(), "implement", inputs, null, Map.of(), List.of());
    }

    private static CodingRequest request() {
        return new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("repo", "owner/repo", "master", "a".repeat(40)),
                CodingRequest.Constraints.defaults(), null, List.of());
    }
}
