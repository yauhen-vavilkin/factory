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
import org.folio.factory.devfactory.pilot.AttemptDeadline;
import org.folio.factory.devfactory.pilot.PilotStops;
import org.folio.factory.devfactory.pilot.RecordingPilotAttempts;
import org.folio.factory.devfactory.repository.SelectedRepositoryPreflight;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.IntakeResolveWorker;
import org.folio.factory.devfactory.worker.InitialCodingAttempt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

class DevelopReadinessTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryStartsPiOnlyAfterBuildPassesAndNeverRetriesPiFailure(boolean piFails) {
        var repo = new DevFactoryProperties.Repository("owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties("https://example.org", new TreeMap<>(Map.of("repo", repo)));
        var runtime = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")),
                new DevRuntimeProperties.Coding("pi:image", "provider", "model", null, null, "secret", "pi", null, null), 60, null);
        var docker = mock(DockerWorkloads.class);
        var stepScope = mock(DockerWorkloads.StepScope.class);
        when(docker.beginStep(any(), eq("implement"))).thenReturn(stepScope);
        var baseline = mock(DockerWorkloads.Workload.class);
        var pi = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        var selectedRepository = mock(SelectedRepositoryPreflight.class);
        when(coding.image()).thenReturn("pi:image");
        when(coding.identity()).thenReturn(Map.of("runtime", "pi", "image", "pi:image",
                "provider", "provider", "model", "model"));
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(stepScope.createTrusted("build:image", workspace, "factory-dev-m2-cache")).thenReturn(baseline);
        when(stepScope.createSeeded("pi:image", workspace, "factory-dev-m2-cache", "coding")).thenReturn(pi);
        when(baseline.execute(eq(List.of("mvn", "test")), eq(60), eq(Processes.OUTPUT_LIMIT), any()))
                .thenReturn(new Processes.Result(1, "[ERROR] Could not transfer artifact org.example:library:jar:1 from/to central: Read timed out"),
                        new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        if (piFails) {
            when(coding.code(eq(pi), any(CodingRequest.class), eq(60), any(), any()))
                    .thenAnswer(invocation -> {
                        Consumer<Map<String, Object>> observer = invocation.getArgument(3);
                        var progress = new PiProgress("secret", observer);
                        progress.accept("""
                                {"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"private secret"}],"usage":{"input":10,"cacheRead":2,"cacheWrite":1,"output":3,"cost":{"total":0.125}}}}
                                """);
                        throw new AgentExecutionException("Provider failed");
                    });
        } else {
            Map<String, Object> usage = Map.of(
                    "inputTokens", 10L,
                    "cacheReadTokens", 2L,
                    "cacheWriteTokens", 1L,
                    "outputTokens", 3L,
                    "costUsd", new BigDecimal("0.125"));
            when(coding.code(eq(pi), any(CodingRequest.class), eq(60), any(), any()))
                    .thenReturn(CodingOutcome.completed("Done", usage));
            when(freezer.freeze(eq("repo"), anyString(), anyString(), any()))
                    .thenReturn(new Candidate("repo", "a".repeat(40),
                            "b".repeat(40), Candidate.sha256("patch"), "patch", "CANDIDATE_UNVERIFIED"));
        }
        var codec = new FrontmatterCodec();
        var request = request();
        var context = context(codec, request);
        var audit = mock(AuditLog.class);
        var attempt = new InitialCodingAttempt(
                properties, runtime, docker, freezer, coding, codec, audit, model -> Map.of(),
                selectedRepository);
        var worker = new DevelopWorker(attempt, codec);

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class);
        verify(coding, never()).code(any(), any(), anyInt(), any(), any());
        verify(coding).requireConfigured();
        var result = worker.execute(context);

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(piFails ? "DEVELOPMENT_FAILED" : "CANDIDATE_UNVERIFIED");
        assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_PASSED");
        if (piFails) {
            Map<String, Object> expectedUsageEvent = Map.of(
                    "activity", "coding_usage",
                    "inputTokens", 10L,
                    "cacheReadTokens", 2L,
                    "cacheWriteTokens", 1L,
                    "outputTokens", 3L,
                    "costUsd", new BigDecimal("0.125"));
            verify(audit).record(
                    eq(context.executionId()), eq(AuditEventType.RUNTIME_PROGRESS), eq("implement"),
                    argThat(detail -> detail.entrySet().containsAll(expectedUsageEvent.entrySet())
                            && detail.containsKey("attemptId")));
        } else {
            assertThat(result.metrics())
                    .containsEntry("inputTokens", 10L)
                    .containsEntry("cacheReadTokens", 2L)
                    .containsEntry("cacheWriteTokens", 1L)
                    .containsEntry("outputTokens", 3L)
                    .containsEntry("promptTokens", 13L)
                    .containsEntry("completionTokens", 3L);
        }
        verify(coding).code(eq(pi), any(CodingRequest.class), eq(60), any(), any());
        verify(selectedRepository, times(2)).requireRepositoryReady("repo", "a".repeat(40));
        var order = inOrder(docker, coding, selectedRepository, freezer);
        order.verify(docker).beginStep(context.executionId(), "implement");
        order.verify(coding).requireConfigured();
        order.verify(selectedRepository).requireRepositoryReady("repo", request.repository().baseSha());
        order.verify(freezer).checkout("https://example.org/owner/repo.git", request.repository().baseSha());
        verify(stepScope, times(2)).createTrusted("build:image", workspace, "factory-dev-m2-cache");
        verify(baseline, times(2)).close();
        verify(pi).close();
    }

    @Test
    void transientBaselineSignalsEngineRetryAndMakesZeroCodingCalls() {
        verifyFailedBaseline("[ERROR] Could not transfer artifact net.sf.saxon:Saxon-HE:jar:12.9 "
                + "from/to central (https://repo.maven.apache.org/maven2): "
                + "Premature end of Content-Length delimited message body", true);
    }

    @Test
    void compilationFailureDoesNotRetryOrStartPi() {
        verifyFailedBaseline("[ERROR] Compilation failure: cannot find symbol", false);
    }

    @Test
    void testFailureDoesNotRetryOrStartPi() {
        verifyFailedBaseline("[ERROR] There are test failures", false);
    }

    @Test
    void repositoryNotFoundDoesNotRetryOrStartPi() {
        verifyFailedBaseline("[ERROR] Could not find artifact org.example:missing:jar:1 in folio-nexus\n"
                + "[ERROR] Could not find artifact org.example:missing:jar:1 in index-data-nexus", false);
    }

    private void verifyFailedBaseline(String failure, boolean retryable) {
        var repo = new DevFactoryProperties.Repository("owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties("https://example.org", new TreeMap<>(Map.of("repo", repo)));
        var runtime = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null);
        var docker = mock(DockerWorkloads.class);
        var stepScope = mock(DockerWorkloads.StepScope.class);
        when(docker.beginStep(any(), eq("implement"))).thenReturn(stepScope);
        var workload = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(stepScope.createTrusted("build:image", workspace, "factory-dev-m2-cache")).thenReturn(workload);
        when(workload.execute(eq(List.of("mvn", "test")), eq(60), eq(Processes.OUTPUT_LIMIT), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<String> observer = invocation.getArgument(3);
                    observer.accept("[INFO] --- compiler:3.16.0:compile (default-compile) @ module ---");
                    return new Processes.Result(1, failure);
                });
        var codec = new FrontmatterCodec();
        var context = context(codec, request());
        var audit = mock(AuditLog.class);
        var initialAttempt = new InitialCodingAttempt(
                properties, runtime, docker, freezer, coding, codec, audit, model -> Map.of());
        var worker = new DevelopWorker(initialAttempt, codec);
        if (retryable) {
            // Repeated worker attempts retain the configured repository and do not add a global -U.
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class)
                        .hasMessageContaining("Starting build hit a network/download failure")
                        .hasMessageContaining("coding has not started")
                        .hasMessageContaining("Saxon-HE").hasMessageContaining("incomplete download");
            }
            verify(stepScope, times(2)).createTrusted("build:image", workspace, "factory-dev-m2-cache");
            verify(workload, times(2)).execute(eq(List.of("mvn", "test")), eq(60), eq(Processes.OUTPUT_LIMIT), any());
        } else {
            var result = worker.execute(context);
            assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT");
            assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_FAILED", "output");
        }
        verify(coding, never()).code(any(), any(), anyInt(), any(), any());
        verify(coding, times(retryable ? 2 : 1)).requireConfigured();
        verify(audit, atLeastOnce()).record(eq(context.executionId()),
                eq(AuditEventType.RUNTIME_PROGRESS), eq("implement"), anyMap());
        verify(workload, times(retryable ? 2 : 1)).close();
    }

    @Test
    void invalidCodingProfileReconcilesOwnedStepButNeverChecksOutOrBuilds() {
        var repository = new DevFactoryProperties.Repository(
                "owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties(null, new TreeMap<>(Map.of("repo", repository)));
        var settings = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null);
        var docker = mock(DockerWorkloads.class);
        var scope = mock(DockerWorkloads.StepScope.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        var context = new AgentContext(
                UUID.randomUUID(), "implement", Map.of(), null, Map.of(), List.of());
        when(docker.beginStep(context.executionId(), "implement")).thenReturn(scope);
        doThrow(new IllegalStateException("secret must not persist")).when(coding).requireConfigured();
        var attempt = new InitialCodingAttempt(
                properties, settings, docker, freezer, coding, new FrontmatterCodec(),
                mock(AuditLog.class), model -> Map.of());

        var result = attempt.start(context, request());

        assertThat(result.candidate())
                .contains("BLOCKED_ENVIRONMENT")
                .doesNotContain("secret must not persist");
        var order = inOrder(docker, coding, scope);
        order.verify(docker).beginStep(context.executionId(), "implement");
        order.verify(coding).requireConfigured();
        order.verify(scope).close();
        verifyNoInteractions(freezer);
        verify(scope, never()).createTrusted(anyString(), any(), anyString());
        verify(coding, never()).code(any(), any(), anyInt(), any(), any());
    }

    @Test
    void aPilotExecutionClaimsOnePaidStartAndAReplayedStepCannotStartAnother() {
        var repository = new DevFactoryProperties.Repository(
                "owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties(null, new TreeMap<>(Map.of("repo", repository)));
        var settings = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null);
        var docker = mock(DockerWorkloads.class);
        var scope = mock(DockerWorkloads.StepScope.class);
        var workload = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        var pilots = RecordingPilotAttempts.pilot("repo");
        var codec = new FrontmatterCodec();
        var request = request();
        var context = context(codec, request);
        when(coding.image()).thenReturn("coding:image");
        when(coding.identity()).thenReturn(Map.of("runtime", "stub", "image", "coding:image"));
        when(docker.beginStep(context.executionId(), "implement")).thenReturn(scope);
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(scope.createTrusted(anyString(), any(), anyString())).thenReturn(workload);
        when(scope.createSeeded(anyString(), any(), anyString(), anyString())).thenReturn(workload);
        when(workload.execute(anyList(), anyInt(), anyInt(), any()))
                .thenReturn(new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        when(coding.code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.needsDecision(new CodingOutcome.Decision(
                        CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Which limit?", List.of(), "Evidence"),
                        Map.of()));
        var attempt = new InitialCodingAttempt(
                properties, settings, docker, freezer, coding, codec,
                mock(AuditLog.class), model -> Map.of(), SelectedRepositoryPreflight.none(), pilots);
        var worker = new DevelopWorker(attempt, codec);

        var first = worker.execute(context);
        var replay = worker.execute(context);

        assertThat(first.outputs().get(DevelopWorker.CANDIDATE)).contains("NEEDS_DECISION");
        assertThat(pilots.paidStartClaims).isEqualTo(2);
        assertThat(replay.outputs().get(DevelopWorker.CANDIDATE))
                .contains("PAID_START_CONSUMED")
                .contains("cannot start a second initial coding invocation");
        verify(coding, times(1)).code(any(), any(), anyInt(), any(), any());
        verify(docker, times(2)).beginStep(any(), any());
        verify(freezer, times(2)).checkout(anyString(), anyString());
        verify(freezer, never()).freeze(anyString(), anyString(), anyString(), any());
        assertThat(pilots.stops).singleElement().satisfies(stop -> {
            assertThat(stop).startsWith("implement: ");
            assertThat(stop).contains("pilot attempt");
        });
    }

    @Test
    void aPilotBaselineFailureKeepsTheClaimUnconsumedAndTheEngineRetrySucceeds() {
        var repository = new DevFactoryProperties.Repository(
                "owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties(null, new TreeMap<>(Map.of("repo", repository)));
        var settings = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null);
        var docker = mock(DockerWorkloads.class);
        var scope = mock(DockerWorkloads.StepScope.class);
        var baseline = mock(DockerWorkloads.Workload.class);
        var codingWorkload = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        var pilots = RecordingPilotAttempts.pilot("repo");
        var codec = new FrontmatterCodec();
        var request = request();
        var context = context(codec, request);
        when(coding.image()).thenReturn("coding:image");
        when(coding.identity()).thenReturn(Map.of("runtime", "stub", "image", "coding:image"));
        when(docker.beginStep(context.executionId(), "implement")).thenReturn(scope);
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(scope.createTrusted(anyString(), any(), anyString())).thenReturn(baseline);
        when(scope.createSeeded(anyString(), any(), anyString(), anyString())).thenReturn(codingWorkload);
        when(baseline.execute(anyList(), anyInt(), anyInt(), any()))
                .thenReturn(
                        new Processes.Result(1, "[ERROR] Could not transfer artifact org.example:library:jar:1"
                                + " from/to central: Read timed out"),
                        new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        when(coding.code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.needsDecision(new CodingOutcome.Decision(
                        CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Which limit?", List.of(), "Evidence"),
                        Map.of()));
        var attempt = new InitialCodingAttempt(
                properties, settings, docker, freezer, coding, codec,
                mock(AuditLog.class), model -> Map.of(), SelectedRepositoryPreflight.none(), pilots);
        var worker = new DevelopWorker(attempt, codec);

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Starting build hit a network/download failure")
                .hasMessageContaining("coding has not started");
        assertThat(pilots.paidStartClaims).isZero();
        assertThat(pilots.stops).isEmpty();

        var retried = worker.execute(context);

        assertThat(retried.outputs().get(DevelopWorker.CANDIDATE)).contains("NEEDS_DECISION");
        assertThat(retried.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_PASSED");
        assertThat(pilots.paidStartClaims).isEqualTo(1);
        assertThat(pilots.stops).isEmpty();
        verify(coding, times(1)).code(any(), any(), anyInt(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedRepositoryStopsBeforeCheckoutForInitialAttemptAndContinuation(boolean continuation) {
        var repository = new DevFactoryProperties.Repository(
                "owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties(null, new TreeMap<>(Map.of("repo", repository)));
        var settings = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null);
        var docker = mock(DockerWorkloads.class);
        var scope = mock(DockerWorkloads.StepScope.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        var selectedRepository = mock(SelectedRepositoryPreflight.class);
        var context = new AgentContext(
                UUID.randomUUID(), "implement", Map.of(), null, Map.of(), List.of());
        var request = request();
        when(docker.beginStep(context.executionId(), "implement")).thenReturn(scope);
        doThrow(new IllegalStateException("credential-secret must not persist"))
                .when(selectedRepository)
                .requireRepositoryReady("repo", request.repository().baseSha());
        var attempt = new InitialCodingAttempt(
                properties, settings, docker, freezer, coding, new FrontmatterCodec(),
                mock(AuditLog.class), model -> Map.of(), selectedRepository);
        var readiness = JsonMapper.builder().build().readTree("{\"state\":\"BASELINE_PASSED\"}");

        InitialCodingAttempt.Attempt result;
        if (continuation) {
            result = attempt.retry(context, request, readiness);
        } else {
            result = attempt.start(context, request);
        }

        assertThat(result.candidate())
                .contains("BLOCKED_ENVIRONMENT")
                .doesNotContain("credential-secret");
        assertThat(result.outcome().failure().message()).doesNotContain("credential-secret");
        if (continuation) {
            assertThat(result.readiness()).contains("BASELINE_PASSED");
        }
        var order = inOrder(docker, coding, selectedRepository, scope);
        order.verify(docker).beginStep(context.executionId(), "implement");
        order.verify(coding).requireConfigured();
        order.verify(selectedRepository).requireRepositoryReady("repo", "a".repeat(40));
        order.verify(scope).close();
        verifyNoInteractions(freezer);
        verify(scope, never()).createTrusted(anyString(), any(), anyString());
        verify(scope, never()).createSeeded(anyString(), any(), anyString(), anyString());
        verify(coding, never()).code(any(), any(), anyInt());
        verify(coding, never()).code(any(), any(), anyInt(), any(), any());
    }

    @Test
    void anExpiredPilotDeadlinePreventsEveryStageSideEffect() {
        var pilots = RecordingPilotAttempts.pilot("repo")
                .withDeadline(new AttemptDeadline(Instant.now().minusSeconds(1)));
        var harness = pilotHarness(pilots);

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(PilotStops.DEADLINE_STATE)
                .contains("expired before preparation");
        verify(harness.freezer(), never()).checkout(anyString(), anyString());
        verify(harness.scope(), never()).createTrusted(anyString(), any(), anyString());
        verify(harness.scope(), never()).createSeeded(anyString(), any(), anyString(), anyString());
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        assertThat(pilots.stops).singleElement().satisfies(stop -> {
            assertThat(stop).startsWith("implement: ");
            assertThat(stop).contains("deadline expired");
        });
    }

    @Test
    void aDeadlineExpiringDuringBaselinePreventsCodingWithoutConsumingThePaidStart() {
        var pilots = RecordingPilotAttempts.pilot("repo")
                .withDeadline(new AttemptDeadline(Instant.now().plusSeconds(3)));
        var harness = pilotHarness(pilots);
        when(harness.baseline().execute(anyList(), anyInt(), anyInt(), any())).thenAnswer(
                invocation -> {
                    Thread.sleep(4000);
                    return new Processes.Result(0, "[INFO] BUILD SUCCESS");
                });

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(PilotStops.DEADLINE_STATE)
                .contains("expired before coding");
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        assertThat(pilots.paidStartClaims).isZero();
        assertThat(pilots.stops).anyMatch(stop -> stop.contains("deadline expired before coding"));
    }

    @Test
    void theRemainingBudgetCapsEveryStageTimeoutBelowTheConfiguredPerOperationLimit() {
        var pilots = RecordingPilotAttempts.pilot("repo")
                .withDeadline(new AttemptDeadline(Instant.now().plusSeconds(45)));
        var harness = pilotHarness(pilots);

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("NEEDS_DECISION");
        var baselineTimeout = ArgumentCaptor.forClass(Integer.class);
        verify(harness.baseline()).execute(anyList(), baselineTimeout.capture(), anyInt(), any());
        assertThat(baselineTimeout.getValue()).isStrictlyBetween(40, 45);
        var codingTimeout = ArgumentCaptor.forClass(Integer.class);
        verify(harness.coding()).code(any(), any(CodingRequest.class), codingTimeout.capture(), any(), any());
        assertThat(codingTimeout.getValue()).isStrictlyBetween(40, 45);
    }

    @Test
    void aLegacyAttemptWithoutPersistedDeadlineRunsUnboundedWithoutGuardStops() {
        // RecordingPilotAttempts without a deadline mirrors pre-S05 rows: no
        // deadline_at is persisted, so the whole-attempt budget never applies.
        var pilots = RecordingPilotAttempts.pilot("repo");
        var harness = pilotHarness(pilots);

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("NEEDS_DECISION");
        verify(harness.baseline()).execute(anyList(), anyInt(), anyInt(), any());
        verify(harness.coding()).code(any(), any(CodingRequest.class), anyInt(), any(), any());
        assertThat(pilots.paidStartClaims).isEqualTo(1);
        assertThat(pilots.stops).isEmpty();
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
        var harness = pilotHarness(RecordingPilotAttempts.pilot("repo"), transcripts);
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
    void aFailedActivityWriteDuringCodingReachesTheRuntimeAndTheAttemptGoesOn() {
        var audit = mock(AuditLog.class);
        doThrow(new IllegalStateException("database down"))
                .when(audit).record(any(), any(), any(), org.mockito.ArgumentMatchers.<Map<String, ?>>any());
        var harness = pilotHarness(RecordingPilotAttempts.pilot("repo"), CodingTranscripts.none(), audit);
        var reported = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        when(harness.coding().code(any(), any(CodingRequest.class), anyInt(), any(), any())).thenAnswer(call -> {
            Consumer<Map<String, Object>> progress = call.getArgument(3);
            // The runtime gets the failure, so it can log it once and note it in the transcript.
            reported.set(catchThrowable(() -> progress.accept(Map.of("activity", "agent_start"))));
            return CodingOutcome.needsDecision(new CodingOutcome.Decision(
                    CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Which limit?", List.of(), "Evidence"),
                    Map.of());
        });

        var result = harness.worker().execute(harness.context());

        assertThat(reported.get()).isInstanceOf(IllegalStateException.class).hasMessage("database down");
        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("NEEDS_DECISION");
    }

    @Test
    void aPilotAttemptThatLostItsSlotStopsWithoutAnyStageSideEffect() {
        var pilots = RecordingPilotAttempts.pilot("repo");
        pilots.ownsSlot = false;
        var harness = pilotHarness(pilots);

        var result = harness.worker().execute(harness.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(PilotStops.INSPECTION_STATE)
                .contains("no longer owns the installation's active slot");
        verify(harness.freezer(), never()).checkout(anyString(), anyString());
        verify(harness.coding(), never()).code(any(), any(), anyInt(), any(), any());
        assertThat(pilots.stops).anyMatch(stop -> stop.contains("no longer owns"));
    }

    /**
     * Shared pilot harness: docker, freezer and coding stubs allow the attempt to
     * run through baseline into coding; individual tests override specific stubs
     * or the recording pilot policy state.
     */
    private PilotHarness pilotHarness(RecordingPilotAttempts pilots) {
        return pilotHarness(pilots, CodingTranscripts.none());
    }

    private PilotHarness pilotHarness(RecordingPilotAttempts pilots, CodingTranscripts transcripts) {
        return pilotHarness(pilots, transcripts, mock(AuditLog.class));
    }

    private PilotHarness pilotHarness(RecordingPilotAttempts pilots, CodingTranscripts transcripts, AuditLog audit) {
        var repository = new DevFactoryProperties.Repository(
                "owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties(null, new TreeMap<>(Map.of("repo", repository)));
        var settings = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null);
        var docker = mock(DockerWorkloads.class);
        var scope = mock(DockerWorkloads.StepScope.class);
        var baseline = mock(DockerWorkloads.Workload.class);
        var codingWorkload = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        var codec = new FrontmatterCodec();
        var request = request();
        var context = context(codec, request);
        when(coding.image()).thenReturn("coding:image");
        when(coding.identity()).thenReturn(Map.of("runtime", "stub", "image", "coding:image"));
        when(docker.beginStep(context.executionId(), "implement")).thenReturn(scope);
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(scope.createTrusted(anyString(), any(), anyString())).thenReturn(baseline);
        when(scope.createSeeded(anyString(), any(), anyString(), anyString())).thenReturn(codingWorkload);
        when(baseline.execute(anyList(), anyInt(), anyInt(), any()))
                .thenReturn(new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        when(coding.code(any(), any(CodingRequest.class), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.needsDecision(new CodingOutcome.Decision(
                        CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Which limit?", List.of(), "Evidence"),
                        Map.of()));
        var attempt = new InitialCodingAttempt(
                properties, settings, docker, freezer, coding, codec,
                audit, model -> Map.of(), SelectedRepositoryPreflight.none(), pilots, transcripts);
        return new PilotHarness(docker, scope, baseline, codingWorkload, freezer, coding,
                new DevelopWorker(attempt, codec), context);
    }

    private record PilotHarness(
            DockerWorkloads docker,
            DockerWorkloads.StepScope scope,
            DockerWorkloads.Workload baseline,
            DockerWorkloads.Workload codingWorkload,
            CandidateFreezer freezer,
            CodingRuntime coding,
            DevelopWorker worker,
            AgentContext context) {
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
                CodingRequest.Constraints.defaults());
    }
}
