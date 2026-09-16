package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DockerWorkloadsTest {
    @TempDir Path source;
    @TempDir Path exported;
    @Test @EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
    void privateWorkloadRequiresStopBeforeExportAndIsRemoved() throws Exception {
        Files.writeString(source.resolve("source.txt"), "source");
        String name;
        try (var workload = new DockerWorkloads().create("maven:3.9-eclipse-temurin-21", source)) {
            name = workload.name();
            assertThatThrownBy(() -> workload.export(exported)).hasMessageContaining("Stop");
            assertThat(workload.execute(List.of("sh", "-c", "test ! -e /var/run/docker.sock && test -z \"$GH_TOKEN$GITHUB_TOKEN$JIRA_API_TOKEN\" && test -f /workspace/source.txt"), 30).exitCode()).isZero();
            workload.stop();
            workload.export(exported);
            assertThat(Files.readString(exported.resolve("source.txt"))).isEqualTo("source");
        }
        assertThat(Processes.run(null, List.of("docker", "inspect", name), 30).exitCode()).isNotZero();
    }
}
