package org.folio.factory.devfactory.runtime;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.IntakeResolveWorker;
import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevelopReadinessTest {
    @TempDir Path workspace;
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryStartsPiOnlyAfterBuildPassesAndNeverRetriesPiFailure(boolean piFails) {
        var repo = new DevFactoryProperties.Repository("owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties("https://example.org", new TreeMap<>(Map.of("repo", repo)));
        var runtime = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")),
                new DevRuntimeProperties.Coding("pi:image", "provider", "model", null, null, "secret"), 60, null);
        var docker = mock(DockerWorkloads.class);
        var baseline = mock(DockerWorkloads.Workload.class);
        var pi = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(docker.createTrusted("build:image", workspace, "factory-dev-m2-cache")).thenReturn(baseline);
        when(docker.createSeeded("pi:image", workspace, "factory-dev-m2-cache")).thenReturn(pi);
        when(baseline.execute(eq(List.of("mvn", "test")), eq(60), eq(Processes.OUTPUT_LIMIT), any()))
                .thenReturn(new Processes.Result(1, "[ERROR] Could not transfer artifact org.example:library:jar:1 from/to central: Read timed out"),
                        new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        if (piFails) when(coding.code(eq(pi), anyString(), eq(60), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<Map<String, Object>> observer = invocation.getArgument(3);
                    var progress = new PiCodingRuntime.Progress("secret", observer);
                    progress.accept("""
                            {"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"private secret"}],"usage":{"input":10,"cacheRead":2,"cacheWrite":1,"output":3,"cost":{"total":0.125}}}}
                            """);
                    throw new AgentExecutionException("Provider failed");
                });
        else {
            when(coding.code(eq(pi), anyString(), eq(60), any())).thenReturn(new CodingRuntime.Result("Done"));
            when(freezer.freeze(eq("repo"), anyString(), anyString(), any()))
                    .thenReturn(new org.folio.factory.devfactory.candidate.Candidate("repo", "a".repeat(40),
                            "b".repeat(40), org.folio.factory.devfactory.candidate.Candidate.sha256("patch"), "patch", "CANDIDATE_UNVERIFIED"));
        }
        var codec = new FrontmatterCodec();
        var brief = codec.render(Map.of("state", "INTAKE_READY", "repository", Map.of("key", "repo", "base_sha", "a".repeat(40))), "Task");
        var context = new AgentContext(UUID.randomUUID(), "implement", Map.of(IntakeResolveWorker.TASK_BRIEF,
                new ArtifactContent(IntakeResolveWorker.TASK_BRIEF, 1, "text/markdown", brief)), null, Map.of(), List.of());
        var audit = mock(AuditLog.class);
        var worker = new DevelopWorker(properties, runtime, docker, freezer, coding, codec, audit);

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class);
        verifyNoInteractions(coding);
        var result = worker.execute(context);

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(piFails ? "DEVELOPMENT_FAILED" : "CANDIDATE_UNVERIFIED");
        assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_PASSED");
        if (piFails) verify(audit).record(eq(context.executionId()),
                eq(org.folio.factory.core.domain.AuditEventType.RUNTIME_PROGRESS), eq("implement"),
                eq(Map.of("activity", "pi_usage", "promptTokens", 13L, "completionTokens", 3L,
                        "costUsd", new java.math.BigDecimal("0.125"))));
        verify(coding).code(eq(pi), anyString(), eq(60), any());
        verify(docker, times(2)).createTrusted("build:image", workspace, "factory-dev-m2-cache");
        verify(baseline, times(2)).close();
        verify(pi).close();
    }

    @Test void transientBaselineSignalsEngineRetryAndMakesZeroCodingCalls() {
        verifyFailedBaseline("[ERROR] Could not transfer artifact net.sf.saxon:Saxon-HE:jar:12.9 "
                + "from/to central (https://repo.maven.apache.org/maven2): "
                + "Premature end of Content-Length delimited message body", true);
    }

    @Test void compilationFailureDoesNotRetryOrStartPi() {
        verifyFailedBaseline("[ERROR] Compilation failure: cannot find symbol", false);
    }

    @Test void testFailureDoesNotRetryOrStartPi() {
        verifyFailedBaseline("[ERROR] There are test failures", false);
    }

    @Test void repositoryNotFoundDoesNotRetryOrStartPi() {
        verifyFailedBaseline("[ERROR] Could not find artifact org.example:missing:jar:1 in folio-nexus\n"
                + "[ERROR] Could not find artifact org.example:missing:jar:1 in index-data-nexus", false);
    }

    private void verifyFailedBaseline(String failure, boolean retryable) {
        var repo = new DevFactoryProperties.Repository("owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties("https://example.org", new TreeMap<>(Map.of("repo", repo)));
        var runtime = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null);
        var docker = mock(DockerWorkloads.class);
        var workload = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(docker.createTrusted("build:image", workspace, "factory-dev-m2-cache")).thenReturn(workload);
        when(workload.execute(eq(List.of("mvn", "test")), eq(60), eq(Processes.OUTPUT_LIMIT), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<String> observer = invocation.getArgument(3);
                    observer.accept("[INFO] --- compiler:3.16.0:compile (default-compile) @ module ---");
                    return new Processes.Result(1, failure);
                });
        var codec = new FrontmatterCodec();
        var brief = codec.render(Map.of("state", "INTAKE_READY", "repository", Map.of("key", "repo", "base_sha", "a".repeat(40))), "Task");
        var context = new AgentContext(UUID.randomUUID(), "implement", Map.of(IntakeResolveWorker.TASK_BRIEF,
                new ArtifactContent(IntakeResolveWorker.TASK_BRIEF, 1, "text/markdown", brief)), null, Map.of(), List.of());
        var audit = mock(AuditLog.class);
        var worker = new DevelopWorker(properties, runtime, docker, freezer, coding, codec, audit);
        if (retryable) {
            // Repeated worker attempts retain the configured repository and do not add a global -U.
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class)
                        .hasMessageContaining("Starting build hit a network/download failure")
                        .hasMessageContaining("Pi has not started")
                        .hasMessageContaining("Saxon-HE").hasMessageContaining("incomplete download");
            }
            verify(docker, times(2)).createTrusted("build:image", workspace, "factory-dev-m2-cache");
            verify(workload, times(2)).execute(eq(List.of("mvn", "test")), eq(60), eq(Processes.OUTPUT_LIMIT), any());
        } else {
            var result = worker.execute(context);
            assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT");
            assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_FAILED", "output");
        }
        verifyNoInteractions(coding);
        verify(audit, atLeastOnce()).record(eq(context.executionId()),
                eq(org.folio.factory.core.domain.AuditEventType.RUNTIME_PROGRESS), eq("implement"), anyMap());
        verify(workload, times(retryable ? 2 : 1)).close();
    }
}
