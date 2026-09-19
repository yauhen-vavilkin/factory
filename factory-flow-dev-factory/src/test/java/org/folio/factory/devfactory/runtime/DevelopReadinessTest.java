package org.folio.factory.devfactory.runtime;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.IntakeResolveWorker;
import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.Test;
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
    @Test void failedBaselineMakesZeroCodingCalls() {
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
                    return new Processes.Result(1, "[ERROR] Could not transfer artifact net.sf.saxon:Saxon-HE:jar:12.9 "
                            + "from/to central (https://repo.maven.apache.org/maven2): "
                            + "Premature end of Content-Length delimited message body");
                });
        var codec = new FrontmatterCodec();
        var brief = codec.render(Map.of("state", "INTAKE_READY", "repository", Map.of("key", "repo", "base_sha", "a".repeat(40))), "Task");
        var context = new AgentContext(UUID.randomUUID(), "implement", Map.of(IntakeResolveWorker.TASK_BRIEF,
                new ArtifactContent(IntakeResolveWorker.TASK_BRIEF, 1, "text/markdown", brief)), null, Map.of(), List.of());
        var audit = mock(AuditLog.class);
        var result = new DevelopWorker(properties, runtime, docker, freezer, coding, codec, audit).execute(context);
        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains("BLOCKED_ENVIRONMENT", "Dependency resolution failed", "Saxon-HE", "incomplete download");
        assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_FAILED", "Saxon-HE", "output");
        verifyNoInteractions(coding);
        verify(audit, atLeastOnce()).record(eq(context.executionId()),
                eq(org.folio.factory.core.domain.AuditEventType.RUNTIME_PROGRESS), eq("implement"), anyMap());
        verify(workload).close();
    }
}
