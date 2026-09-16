package org.folio.factory.devfactory.runtime;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.IntakeResolveWorker;
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
        var runtime = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60);
        var docker = mock(DockerWorkloads.class);
        var workload = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(docker.create("build:image", workspace)).thenReturn(workload);
        when(workload.execute(List.of("mvn", "test"), 60)).thenReturn(new Processes.Result(1, "No Docker environment available"));
        var codec = new FrontmatterCodec();
        var brief = codec.render(Map.of("state", "INTAKE_READY", "repository", Map.of("key", "repo", "base_sha", "a".repeat(40))), "Task");
        var context = new AgentContext(UUID.randomUUID(), "develop", Map.of(IntakeResolveWorker.TASK_BRIEF,
                new ArtifactContent(IntakeResolveWorker.TASK_BRIEF, 1, "text/markdown", brief)), null, Map.of(), List.of());
        var result = new DevelopWorker(properties, runtime, docker, freezer, coding, codec).execute(context);
        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT");
        verifyNoInteractions(coding);
        verify(workload).close();
    }
}
