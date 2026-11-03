package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class DockerWorkloadsTest {
    private final UUID executionId = UUID.randomUUID();
    private static final String STEP = "implement";
    private DockerWorkloads.StepScope scope() { return new DockerWorkloads().beginStep(executionId, STEP); }

    @Test
    void workloadsUseHostResourcesAndKeepIsolation() {
        for (var cache : DockerWorkloads.CacheMode.values()) {
            var command = DockerWorkloads.createCommand("build", "workload", "501:20", BuildEnvironment.maven("cache"), cache,
                    executionId, STEP, "coding");
            assertThat(command).doesNotContain("--cpus", "--memory")
                    .containsSubsequence("--pids-limit", "512", "--cap-drop", "ALL", "--security-opt", "no-new-privileges")
                    .containsSubsequence("--user", "501:20")
                    .containsSubsequence("--label", "factory.dev-owned=true",
                            "--label", "factory.dev-execution=" + executionId,
                            "--label", "factory.dev-step=" + STEP,
                            "--label", "factory.dev-role=coding");
        }
    }
    private static final List<String> OFFLINE_REUSE = List.of("mvn", "-o", "-B", "-ntp",
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
            "-Dartifact=org.apache.commons:commons-lang3:3.17.0");
    private final String fixtureVolume = "factory-workloads-test-" + UUID.randomUUID();

    @org.junit.jupiter.api.AfterEach
    void removeFixtureVolume() {
        if ("true".equals(System.getenv("FACTORY_DOCKER_TEST")))
            Processes.run(null, List.of("docker", "volume", "rm", fixtureVolume), 30);
    }

    @TempDir Path source;
    @TempDir Path exported;
    @Test
    void trustedBaselineGetsWritablePersistentMavenRepository() {
        var command = DockerWorkloads.createCommand("build", "baseline", "501:20",
                BuildEnvironment.maven("factory-dev-m2-cache"), DockerWorkloads.CacheMode.TRUSTED_WRITABLE,
                executionId, STEP, "baseline");

        assertThat(command).containsSubsequence("--mount",
                "type=volume,source=factory-dev-m2-cache,target=/tmp/factory-home/.m2/repository");
        assertThat(command).contains("MAVEN_OPTS=-Dmaven.repo.local=/tmp/factory-home/.m2/repository");
        assertThat(command).noneMatch(argument -> argument.contains("readonly"));
    }

    @Test
    void untrustedWorkloadGetsReadOnlySeedAndPrivateWritableRepository() {
        var command = DockerWorkloads.createCommand("build", "pi", "501:20",
                BuildEnvironment.maven("factory-dev-m2-cache"), DockerWorkloads.CacheMode.READ_ONLY_SEED,
                executionId, STEP, "coding");

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
        try (var scope = scope(); var workload = scope.createSeeded("maven:3.9-eclipse-temurin-21",
                source, BuildEnvironment.maven(fixtureVolume), "coding")) {
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
    void replayRemovesOnlyItsOwnStaleWorkloadBeforeReplacement() throws Exception {
        Files.writeString(source.resolve("source.txt"), "source");
        String image = "maven:3.9-eclipse-temurin-21";
        String old = "factory-dev-" + UUID.randomUUID();
        String other = "factory-dev-" + UUID.randomUUID();
        UUID otherExecution = UUID.randomUUID();
        for (var entry : List.of(new Object[] {old, executionId}, new Object[] {other, otherExecution})) {
            Processes.run(null, List.of("docker", "create", "--name", (String) entry[0],
                    "--label", "factory.dev-owned=true", "--label", "factory.dev-execution=" + entry[1],
                    "--label", "factory.dev-step=" + STEP, "--entrypoint", "/bin/sh", image,
                    "-c", "sleep infinity"), 30).requireSuccess();
        }
        try {
            assertThat(Processes.run(null, List.of("docker", "start", old), 30).exitCode()).isZero();
            try (var scope = scope()) {
                assertThat(Processes.run(null, List.of("docker", "inspect", old), 30).exitCode()).isNotZero();
                assertThat(Processes.run(null, List.of("docker", "inspect", other), 30).exitCode()).isZero();
                try (var replacement = scope.createSeeded(image, source, BuildEnvironment.maven(fixtureVolume), "coding")) {
                    assertThat(Processes.run(null, List.of("docker", "inspect", "-f",
                            "{{index .Config.Labels \"factory.dev-execution\"}}", replacement.name()), 30)
                            .output().strip()).isEqualTo(executionId.toString());
                }
            }
        } finally {
            Processes.run(null, List.of("docker", "rm", "--force", old), 30);
            Processes.run(null, List.of("docker", "rm", "--force", other), 30);
        }
    }

    @Test @EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
    void liveStepInSameProcessCannotBeReconciledByDuplicate() {
        var docker = new DockerWorkloads();
        try (var scope = docker.beginStep(executionId, STEP)) {
            assertThatThrownBy(() -> docker.beginStep(executionId, STEP))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already active");
        }
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
        try (var scope = scope()) {
            try (var baseline = scope.createTrusted(
                    "maven:3.9-eclipse-temurin-21", source, BuildEnvironment.maven(volume))) {
                assertThat(baseline.execute(List.of("mvn", "-B", "-ntp", "-X", "validate"), 30).output())
                        .contains("Using local repository at /tmp/factory-home/.m2/repository");
                assertThat(baseline.execute(List.of("mvn", "-B", "-ntp",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                        "-Dartifact=org.apache.commons:commons-lang3:3.17.0"), 120).exitCode()).isZero();
                assertThat(baseline.execute(List.of("sh", "-c",
                        "printf cached > $HOME/.m2/repository/cached.txt"), 30).exitCode()).isZero();
            }
            try (var repeatedBaseline = scope.createTrusted(
                    "maven:3.9-eclipse-temurin-21", source, BuildEnvironment.maven(volume))) {
                assertThat(repeatedBaseline.execute(List.of("sh", "-c",
                        "test \"$(cat $HOME/.m2/repository/cached.txt)\" = cached"), 30).exitCode()).isZero();
                assertThat(repeatedBaseline.execute(OFFLINE_REUSE, 120).exitCode()).isZero();
            }
            try (var seeded = scope.createSeeded(
                    "maven:3.9-eclipse-temurin-21", source, BuildEnvironment.maven(volume), "coding")) {
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

    @Test @EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
    void trustedBaselineRepairsNestedCacheOwnershipWithoutLosingSeedIsolation() throws Exception {
        Files.writeString(source.resolve("source.txt"), "source");
        Files.writeString(source.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>cache-repair</artifactId><version>1</version>
                </project>
                """);
        String volume = "factory-dev-cache-test-" + java.util.UUID.randomUUID();
        String image = "maven:3.9-eclipse-temurin-21";
        try (var scope = scope()) {
            assertThat(Processes.run(null, List.of("docker", "run", "--rm", "--user", "0:0",
                    "--mount", "type=volume,source=" + volume + ",target=/cache",
                    "--entrypoint", "sh", image, "-c",
                    "mkdir -p /cache/org/example/dependency/1.0 "
                            + "&& printf original > /cache/org/example/dependency/1.0/artifact.pom "
                            + "&& chmod 0000 /cache/org /cache/org/example /cache/org/example/dependency "
                            + "/cache/org/example/dependency/1.0 "
                            + "&& chmod 0000 /cache/org/example/dependency/1.0/artifact.pom"), 60)
                    .exitCode()).isZero();

            try (var baseline = scope.createTrusted(image, source, BuildEnvironment.maven(volume))) {
                assertThat(baseline.execute(List.of("sh", "-c",
                        "test \"$(cat $HOME/.m2/repository/org/example/dependency/1.0/artifact.pom)\" = original "
                                + "&& printf updated > $HOME/.m2/repository/org/example/dependency/1.0/artifact.pom "
                                + "&& printf nested > $HOME/.m2/repository/org/example/dependency/1.0/new.pom"), 30)
                        .exitCode()).isZero();
                assertThat(baseline.execute(List.of("mvn", "-B", "-ntp",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                        "-Dartifact=org.apache.commons:commons-lang3:3.17.0"), 120).exitCode()).isZero();
            }
            try (var repeated = scope.createTrusted(image, source, BuildEnvironment.maven(volume))) {
                assertThat(repeated.execute(List.of("sh", "-c",
                        "test \"$(cat $HOME/.m2/repository/org/example/dependency/1.0/artifact.pom)\" = updated "
                                + "&& test \"$(cat $HOME/.m2/repository/org/example/dependency/1.0/new.pom)\" = nested"), 30)
                        .exitCode()).isZero();
                assertThat(repeated.execute(OFFLINE_REUSE, 120).exitCode()).isZero();
            }
            try (var seeded = scope.createSeeded(image, source, BuildEnvironment.maven(volume), "coding")) {
                assertThat(seeded.execute(List.of("sh", "-c",
                        "test \"$(cat $HOME/.m2/repository/org/example/dependency/1.0/artifact.pom)\" = updated "
                                + "&& printf private > $HOME/.m2/repository/org/example/dependency/1.0/new.pom "
                                + "&& ! touch /tmp/factory-m2-seed/org/example/dependency/1.0/forbidden"), 30)
                        .exitCode()).isZero();
                assertThat(seeded.execute(OFFLINE_REUSE, 120).exitCode()).isZero();
            }
            assertThat(Processes.run(null, List.of("docker", "run", "--rm", "--mount",
                    "type=volume,source=" + volume + ",target=/cache,readonly",
                    "--entrypoint", "sh", image, "-c",
                    "test \"$(cat /cache/org/example/dependency/1.0/new.pom)\" = nested "
                            + "&& test ! -e /cache/org/example/dependency/1.0/forbidden"), 60).exitCode()).isZero();
        } finally {
            Processes.run(null, List.of("docker", "volume", "rm", volume), 30);
        }
    }

    private static final String REGISTRY = "https://repository.folio.org/repository/npm-folioci/";
    private static final BuildEnvironment YARN = new BuildEnvironment(Toolchain.YARN, "yarn-cache", REGISTRY);

    @Test
    void yarnBaselineGetsWritablePersistentCacheTrustedEnvironmentAndRegistry() {
        var command = DockerWorkloads.createCommand("node", "baseline", "501:20", YARN,
                DockerWorkloads.CacheMode.TRUSTED_WRITABLE, executionId, STEP, "baseline");

        assertThat(command).containsSubsequence("--mount",
                "type=volume,source=yarn-cache,target=/tmp/factory-home/.yarn-cache");
        assertThat(command).contains("HOME=/tmp/factory-home", "YARN_CACHE_FOLDER=/tmp/factory-home/.yarn-cache",
                "CI=true", "JEST_JUNIT_OUTPUT_DIR=artifacts/jest-junit", "JEST_JUNIT_REPORT_TEST_SUITE_ERRORS=true");
        assertThat(command).noneMatch(argument -> argument.startsWith("MAVEN_OPTS") || argument.contains("readonly"));
        assertThat(command.get(command.size() - 1)).contains("@folio:registry", REGISTRY,
                "/tmp/factory-home/.yarnrc", "exec sleep infinity");
    }

    @Test
    void yarnUntrustedWorkloadGetsReadOnlySeedAndPrivateWritableCache() {
        var command = DockerWorkloads.createCommand("node", "coding", "501:20", YARN,
                DockerWorkloads.CacheMode.READ_ONLY_SEED, executionId, STEP, "coding");

        assertThat(command).containsSubsequence("--mount",
                "type=volume,source=yarn-cache,target=/tmp/factory-yarn-seed,readonly");
        assertThat(command).contains("YARN_CACHE_FOLDER=/tmp/factory-home/.yarn-cache", "CI=true");
        assertThat(command).noneMatch(argument -> argument.contains("target=/tmp/factory-home/.yarn-cache"));
        assertThat(command.get(command.size() - 1)).contains("mkdir -p /tmp/factory-home/.yarn-cache")
                .doesNotContain("cp -a");
    }

    @Test
    void mavenWorkloadsNeverReceiveYarnEnvironment() {
        for (var mode : DockerWorkloads.CacheMode.values()) {
            var command = DockerWorkloads.createCommand("build", "workload", "501:20",
                    BuildEnvironment.maven("cache"), mode, executionId, STEP, "coding");
            assertThat(command).noneMatch(argument -> argument.startsWith("YARN_") || argument.startsWith("JEST_")
                    || argument.equals("CI=true") || argument.contains(".yarnrc"));
        }
    }

    @Test
    void buildEnvironmentsRejectValuesThatCouldInjectShellCommands() {
        assertThatThrownBy(() -> new BuildEnvironment(Toolchain.YARN, "cache", "https://npm.example/'; rm -rf /; '"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildEnvironment(Toolchain.YARN, "cache", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildEnvironment(Toolchain.YARN, "cache", "ftp://npm.example/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildEnvironment(Toolchain.MAVEN, "bad/name", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(BuildEnvironment.maven("m2").npmRegistry()).isNull();
    }

    @Test @EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
    void yarnTrustedCachePersistsButSeededWorkloadCannotModifyIt() throws Exception {
        Files.writeString(source.resolve("package.json"), "{\"name\":\"fixture\",\"version\":\"1.0.0\"}");
        String volume = "factory-dev-yarn-test-" + java.util.UUID.randomUUID();
        var environment = new BuildEnvironment(Toolchain.YARN, volume, REGISTRY);
        String image = "node:22-bookworm";
        try (var scope = scope()) {
            try (var baseline = scope.createTrusted(image, source, environment)) {
                assertThat(baseline.execute(List.of("sh", "-c",
                        "test \"$YARN_CACHE_FOLDER\" = /tmp/factory-home/.yarn-cache && test \"$CI\" = true "
                                + "&& test \"$JEST_JUNIT_REPORT_TEST_SUITE_ERRORS\" = true "
                                + "&& grep -q '@folio:registry' $HOME/.yarnrc && grep -q npm-folioci $HOME/.yarnrc "
                                + "&& test \"$(yarn config get @folio:registry)\" = " + REGISTRY
                                + " && mkdir -p $YARN_CACHE_FOLDER/v6 && printf cached > $YARN_CACHE_FOLDER/v6/cached.txt"), 60).exitCode()).isZero();
            }
            try (var repeated = scope.createTrusted(image, source, environment)) {
                assertThat(repeated.execute(List.of("sh", "-c",
                        "test \"$(cat $YARN_CACHE_FOLDER/v6/cached.txt)\" = cached"), 30).exitCode()).isZero();
            }
            try (var seeded = scope.createSeeded(image, source, environment, "coding")) {
                // createSeeded returns only after the private copy is complete.
                assertThat(seeded.execute(List.of("sh", "-c",
                        "test -f $YARN_CACHE_FOLDER/v6/cached.txt "
                                + "&& printf private > $YARN_CACHE_FOLDER/private.txt "
                                + "&& ! touch /tmp/factory-yarn-seed/forbidden"), 30).exitCode()).isZero();
            }
            var persistent = Processes.run(null, List.of("docker", "run", "--rm", "--mount",
                    "type=volume,source=" + volume + ",target=/cache,readonly",
                    "--entrypoint", "sh", image, "-c",
                    "test -f /cache/v6/cached.txt && test ! -e /cache/private.txt && test ! -e /cache/forbidden"), 60);
            assertThat(persistent.exitCode()).isZero();
        } finally {
            Processes.run(null, List.of("docker", "volume", "rm", volume), 30);
        }
    }

    @Test
    void seedFailureNamesTheLimitTheCacheSizeAndTheSetting() {
        String gb = DockerWorkloads.seedFailure(120, "factory-dev-yarn-cache", 3_565_158L, "Command exceeded 120 seconds");
        assertThat(gb).contains("'factory-dev-yarn-cache'", "(3.4 GB)", "limit 120 s", "Command exceeded 120 seconds",
                "factory.dev-factory.runtime.seed-timeout-seconds", "FACTORY_DEV_FACTORY_SEED_TIMEOUT_SECONDS");
        assertThat(DockerWorkloads.seedFailure(300, "v", 512 * 1024L, "x")).contains("(512 MB)");
        assertThat(DockerWorkloads.seedFailure(300, "v", -1, "x")).contains("(unknown size)");
        assertThat(DockerWorkloads.seedFailure(300, "v", 1, "y".repeat(5000)).length()).isLessThan(700);
    }

    @Test
    void duOutputWithATabBetweenSizeAndPathIsParsed() {
        assertThat(DockerWorkloads.parseDuKilobytes("3145736\t/tmp/factory-yarn-seed\n")).isEqualTo(3145736L);
        assertThat(DockerWorkloads.parseDuKilobytes("12 /tmp/x")).isEqualTo(12L);
    }

    @Test
    void seedTimeoutMustBePositive() {
        assertThatThrownBy(() -> new DockerWorkloads(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test @EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
    void aSeedCopyThatExceedsTheConfiguredTimeoutFailsWithTheCacheSizeAndLeavesNoContainer() throws Exception {
        String volume = "factory-dev-seed-timeout-test-" + java.util.UUID.randomUUID();
        var environment = new BuildEnvironment(Toolchain.YARN, volume, REGISTRY);
        try {
            assertThat(Processes.run(null, List.of("docker", "run", "--rm", "--mount",
                    "type=volume,source=" + volume + ",target=/c", "--entrypoint", "sh", "alpine:3.21", "-c",
                    "mkdir -p /c/v6 && dd if=/dev/zero of=/c/v6/big bs=1M count=3072 2>/dev/null"), 300).exitCode()).isZero();

            assertThatThrownBy(() -> {
                try (var scope = new DockerWorkloads(1).beginStep(executionId, STEP)) {
                    scope.createSeeded("alpine:3.21", source, environment, "coding");
                }
            }).isInstanceOf(DockerWorkloads.CacheSeedException.class)
                    .hasMessageContaining("limit 1 s").hasMessageContaining("GB)")
                    .hasMessageContaining(volume);

            assertThat(Processes.run(null, List.of("docker", "ps", "-aq", "--filter",
                    "label=factory.dev-execution=" + executionId), 30).requireSuccess().output()).isBlank();
        } finally {
            Processes.run(null, List.of("docker", "volume", "rm", volume), 60);
        }
    }

    @Test @EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
    void yarnWorkloadParksInstalledDependenciesSoExportHasSourcesAndReportsOnly() throws Exception {
        Files.writeString(source.resolve("src.txt"), "source");
        String volume = "factory-dev-yarn-test-" + java.util.UUID.randomUUID();
        var environment = new BuildEnvironment(Toolchain.YARN, volume, REGISTRY);
        try (var scope = scope(); var workload = scope.createSeeded("node:22-bookworm", source, environment, "verification")) {
            assertThat(workload.execute(List.of("sh", "-c",
                    "mkdir -p node_modules/pkg artifacts/jest-junit && printf dependency > node_modules/pkg/index.js "
                            + "&& printf changed > src.txt && printf report > artifacts/jest-junit/junit.xml"), 30)
                    .exitCode()).isZero();
            workload.stop();
            workload.export(exported);
        } finally {
            Processes.run(null, List.of("docker", "volume", "rm", volume), 30);
        }
        assertThat(Files.readString(exported.resolve("src.txt"))).isEqualTo("changed");
        assertThat(Files.readString(exported.resolve("artifacts/jest-junit/junit.xml"))).isEqualTo("report");
        assertThat(exported.resolve("node_modules")).doesNotExist();
    }

    private static int sh(String script, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("sh", "-c", script, "--"));
        command.addAll(List.of(arguments));
        try {
            return new ProcessBuilder(command).redirectErrorStream(true).start().waitFor();
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "sh is not available");
            return -1;
        }
    }

    @Test
    void yarnSeedCopiesTheVersionDirectoryAndLeavesTheLockAndTempEntriesBehind(@TempDir Path root) throws Exception {
        Path seed = root.resolve("seed"), cache = root.resolve("cache");
        Files.createDirectories(seed.resolve("v6/npm-a-1.0.0-abc-integrity"));
        Files.writeString(seed.resolve("v6/npm-a-1.0.0-abc-integrity/index.js"), "x");
        Files.createDirectories(seed.resolve(".yarn-mutex.lock"));
        Files.createDirectories(seed.resolve(".tmp/pkg"));
        Files.writeString(seed.resolve(".yarn-mutex"), "");

        int exit = sh(DockerWorkloads.seedScript(Toolchain.YARN, seed.toString(), cache.toString()));

        assertThat(exit).isZero();
        assertThat(cache.resolve("v6/npm-a-1.0.0-abc-integrity/index.js")).hasContent("x");
        assertThat(cache.resolve(".yarn-mutex.lock")).doesNotExist();
        assertThat(cache.resolve(".yarn-mutex")).doesNotExist();
        assertThat(cache.resolve(".tmp")).doesNotExist();
    }

    @Test
    void yarnSeedOfAnEmptyCacheSucceedsAndMavenStillCopiesEverything(@TempDir Path root) throws Exception {
        Path empty = Files.createDirectories(root.resolve("empty")), emptyCache = root.resolve("empty-cache");
        Path seed = root.resolve("m2"), cache = root.resolve("m2-cache");
        Files.createDirectories(seed.resolve("org/example"));
        Files.writeString(seed.resolve("org/example/a.jar"), "jar");

        assertThat(sh(DockerWorkloads.seedScript(Toolchain.YARN, empty.toString(), emptyCache.toString()))).isZero();
        assertThat(emptyCache).isDirectory();
        assertThat(sh(DockerWorkloads.seedScript(Toolchain.MAVEN, seed.toString(), cache.toString()))).isZero();
        assertThat(cache.resolve("org/example/a.jar")).hasContent("jar");
    }

    @Test
    void yarnWritableCacheWalksOnlyTheVersionDirectoryAndMavenKeepsTheRecursiveWalk(@TempDir Path root) throws Exception {
        Path cache = Files.createDirectories(root.resolve("cache"));
        Files.createDirectories(cache.resolve("v6/npm-a-1.0.0-abc-integrity"));
        Path lock = Files.createDirectories(cache.resolve(".yarn-mutex.lock"));
        try {
            Files.setPosixFilePermissions(lock, java.util.Set.of());
        } catch (UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "POSIX permissions are not available");
        }
        String owner = String.valueOf(Files.getAttribute(cache, "unix:uid")) + ":" + Files.getAttribute(cache, "unix:gid");
        try {
            // An unreadable root entry stands for one that vanishes mid-walk: the root is never recursed into.
            assertThat(sh(DockerWorkloads.writableCacheScript(Toolchain.YARN, cache.toString()), owner)).isZero();
            Path fresh = Files.createDirectories(root.resolve("fresh"));
            assertThat(sh(DockerWorkloads.writableCacheScript(Toolchain.YARN, fresh.toString()), owner))
                    .as("a cache without a version directory yet").isZero();
        } finally {
            Files.setPosixFilePermissions(lock, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(DockerWorkloads.writableCacheScript(Toolchain.MAVEN, "/cache"))
                .isEqualTo("chown -R \"$1\" /cache && chmod -R u+rwX /cache");
    }
}
