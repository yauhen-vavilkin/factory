package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DockerWorkloadsTest {
    @Test
    void workloadsUseHostResourcesAndKeepIsolation() {
        for (var cache : DockerWorkloads.MavenCache.values()) {
            var command = DockerWorkloads.createCommand("build", "workload", "501:20", cache, "cache");
            assertThat(command).doesNotContain("--cpus", "--memory")
                    .containsSubsequence("--pids-limit", "512", "--cap-drop", "ALL", "--security-opt", "no-new-privileges")
                    .containsSubsequence("--user", "501:20");
        }
    }
    private static final List<String> OFFLINE_REUSE = List.of("mvn", "-o", "-B", "-ntp",
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
            "-Dartifact=org.apache.commons:commons-lang3:3.17.0");
    @TempDir Path source;
    @TempDir Path exported;
    @Test
    void trustedBaselineGetsWritablePersistentMavenRepository() {
        var command = DockerWorkloads.createCommand("build", "baseline", "501:20",
                DockerWorkloads.MavenCache.TRUSTED_WRITABLE, "factory-dev-m2-cache");

        assertThat(command).containsSubsequence("--mount",
                "type=volume,source=factory-dev-m2-cache,target=/tmp/factory-home/.m2/repository");
        assertThat(command).contains("MAVEN_OPTS=-Dmaven.repo.local=/tmp/factory-home/.m2/repository");
        assertThat(command).noneMatch(argument -> argument.contains("readonly"));
    }

    @Test
    void untrustedWorkloadGetsReadOnlySeedAndPrivateWritableRepository() {
        var command = DockerWorkloads.createCommand("build", "pi", "501:20",
                DockerWorkloads.MavenCache.READ_ONLY_SEED, "factory-dev-m2-cache");

        assertThat(command).containsSubsequence("--mount",
                "type=volume,source=factory-dev-m2-cache,target=/tmp/factory-m2-seed,readonly");
        assertThat(command).contains("MAVEN_OPTS=-Dmaven.repo.local=/tmp/factory-home/.m2/repository");
        assertThat(command.get(command.size() - 1))
                .contains("/tmp/factory-home")
                .doesNotContain("cp -a");
        assertThat(command).noneMatch(argument -> argument.contains("target=/tmp/factory-home/.m2/repository,readonly"));
    }

    @Test @EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
    void privateWorkloadRequiresStopBeforeExportAndIsRemoved() throws Exception {
        Files.writeString(source.resolve("source.txt"), "source");
        String name;
        try (var workload = new DockerWorkloads().create("maven:3.9-eclipse-temurin-21", source)) {
            name = workload.name();
            assertThatThrownBy(() -> workload.export(exported)).hasMessageContaining("Stop");
            assertThat(workload.execute(List.of("sh", "-c", "test ! -e /var/run/docker.sock && test -z \"$GH_TOKEN$GITHUB_TOKEN$JIRA_API_TOKEN\" && test -f /workspace/source.txt && printf changed > /workspace/source.txt && mkdir target && printf generated > new-source.txt"), 30).exitCode()).isZero();
            workload.stop();
            workload.export(exported);
            assertThat(Files.readString(exported.resolve("source.txt"))).isEqualTo("changed");
            assertThat(Files.readString(exported.resolve("new-source.txt"))).isEqualTo("generated");
        }
        assertThat(Processes.run(null, List.of("docker", "inspect", name), 30).exitCode()).isNotZero();
    }

    @Test @EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
    void trustedCachePersistsButSeededWorkloadCannotModifyIt() throws Exception {
        Files.writeString(source.resolve("source.txt"), "source");
        Files.writeString(source.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>cache-path</artifactId><version>1</version>
                </project>
                """);
        String volume = "factory-dev-cache-test-" + java.util.UUID.randomUUID();
        try {
            try (var baseline = new DockerWorkloads().createTrusted(
                    "maven:3.9-eclipse-temurin-21", source, volume)) {
                assertThat(baseline.execute(List.of("mvn", "-B", "-ntp", "-X", "validate"), 30).output())
                        .contains("Using local repository at /tmp/factory-home/.m2/repository");
                assertThat(baseline.execute(List.of("mvn", "-B", "-ntp",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                        "-Dartifact=org.apache.commons:commons-lang3:3.17.0"), 120).exitCode()).isZero();
                assertThat(baseline.execute(List.of("sh", "-c",
                        "printf cached > $HOME/.m2/repository/cached.txt"), 30).exitCode()).isZero();
            }
            try (var repeatedBaseline = new DockerWorkloads().createTrusted(
                    "maven:3.9-eclipse-temurin-21", source, volume)) {
                assertThat(repeatedBaseline.execute(List.of("sh", "-c",
                        "test \"$(cat $HOME/.m2/repository/cached.txt)\" = cached"), 30).exitCode()).isZero();
                assertThat(repeatedBaseline.execute(OFFLINE_REUSE, 120).exitCode()).isZero();
            }
            try (var seeded = new DockerWorkloads().createSeeded(
                    "maven:3.9-eclipse-temurin-21", source, volume)) {
                // createSeeded returns only after the private copy is complete.
                assertThat(seeded.execute(List.of("mvn", "-B", "-ntp", "-X", "validate"), 30).output())
                        .contains("Using local repository at /tmp/factory-home/.m2/repository");
                assertThat(seeded.execute(OFFLINE_REUSE, 120).exitCode()).isZero();
                assertThat(seeded.execute(List.of("sh", "-c",
                        "test -f $HOME/.m2/repository/cached.txt "
                                + "&& printf private > $HOME/.m2/repository/private.txt "
                                + "&& ! touch /tmp/factory-m2-seed/pi-write"), 30).exitCode()).isZero();
            }
            var persistent = Processes.run(null, List.of("docker", "run", "--rm", "--mount",
                    "type=volume,source=" + volume + ",target=/cache,readonly",
                    "--entrypoint", "sh", "maven:3.9-eclipse-temurin-21", "-c",
                    "test -f /cache/cached.txt && test ! -e /cache/private.txt && test ! -e /cache/pi-write"), 60);
            assertThat(persistent.exitCode()).isZero();
        } finally {
            Processes.run(null, List.of("docker", "volume", "rm", volume), 30);
        }
    }
}
