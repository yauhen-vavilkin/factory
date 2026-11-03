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
                new DevRuntimeProperties.Coding("pi:image", "provider", "model", null, null, "secret", "pi", null, null), 60, null);
        var docker = mock(DockerWorkloads.class);
        var stepScope = mock(DockerWorkloads.StepScope.class);
        when(docker.beginStep(any(), eq("implement"))).thenReturn(stepScope);
        var baseline = mock(DockerWorkloads.Workload.class);
        var pi = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        when(coding.image()).thenReturn("pi:image");
        when(coding.identity()).thenReturn(Map.of("runtime", "pi", "image", "pi:image",
                "provider", "provider", "model", "model"));
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(stepScope.createTrusted("build:image", workspace, BuildEnvironment.maven("factory-dev-m2-cache"))).thenReturn(baseline);
        when(stepScope.createSeeded("pi:image", workspace, BuildEnvironment.maven("factory-dev-m2-cache"), "coding")).thenReturn(pi);
        when(baseline.executeKeepingTail(eq(List.of("mvn", "test")), eq(60), eq(Processes.OUTPUT_LIMIT), any()))
                .thenReturn(new Processes.Result(1, "[ERROR] Could not transfer artifact org.example:library:jar:1 from/to central: Read timed out"),
                        new Processes.Result(0, "[INFO] BUILD SUCCESS"));
        if (piFails) when(coding.code(eq(pi), any(CodingRequest.class), eq(60), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<Map<String, Object>> observer = invocation.getArgument(3);
                    var progress = new PiProgress("secret", observer);
                    progress.accept("""
                            {"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"private secret"}],"usage":{"input":10,"cacheRead":2,"cacheWrite":1,"output":3,"cost":{"total":0.125}}}}
                            """);
                    throw new AgentExecutionException("Provider failed");
                });
        else {
            when(coding.code(eq(pi), any(CodingRequest.class), eq(60), any())).thenReturn(CodingOutcome.completed("Done", Map.of(
                    "inputTokens", 10L, "cacheReadTokens", 2L, "cacheWriteTokens", 1L,
                    "outputTokens", 3L, "costUsd", new java.math.BigDecimal("0.125"))));
            when(freezer.freeze(eq("repo"), anyString(), anyString(), any(), eq(false), eq(org.folio.factory.devfactory.runtime.Toolchain.MAVEN)))
                    .thenReturn(new org.folio.factory.devfactory.candidate.Candidate("repo", "a".repeat(40),
                            "b".repeat(40), org.folio.factory.devfactory.candidate.Candidate.sha256("patch"), "patch", "CANDIDATE_UNVERIFIED"));
        }
        var codec = new FrontmatterCodec();
        var brief = codec.render(Map.of("state", "INTAKE_READY", "repository", Map.of("key", "repo", "base_sha", "a".repeat(40))), "Task");
        var request = request();
        var context = new AgentContext(UUID.randomUUID(), "implement", Map.of(
                IntakeResolveWorker.TASK_BRIEF, new ArtifactContent(IntakeResolveWorker.TASK_BRIEF, 1, "text/markdown", brief),
                IntakeResolveWorker.CODING_REQUEST, new ArtifactContent(IntakeResolveWorker.CODING_REQUEST, 1,
                        "application/json", tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(request))),
                null, Map.of(), List.of());
        var audit = mock(AuditLog.class);
        var worker = new DevelopWorker(new org.folio.factory.devfactory.worker.InitialCodingAttempt(properties, runtime, docker, freezer, coding, codec, audit, model -> Map.of()), codec);

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class);
        verify(coding, never()).code(any(), any(), anyInt(), any());
        verify(coding, never()).requireConfigured();
        var result = worker.execute(context);

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE))
                .contains(piFails ? "DEVELOPMENT_FAILED" : "CANDIDATE_UNVERIFIED");
        assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_PASSED");
        if (piFails) verify(audit).record(eq(context.executionId()),
                eq(org.folio.factory.core.domain.AuditEventType.RUNTIME_PROGRESS), eq("implement"),
                argThat(detail -> detail.entrySet().containsAll(Map.of("activity", "coding_usage", "inputTokens", 10L, "cacheReadTokens", 2L,
                        "cacheWriteTokens", 1L, "outputTokens", 3L,
                        "costUsd", new java.math.BigDecimal("0.125")).entrySet()) && detail.containsKey("attemptId")));
        else assertThat(result.metrics())
                .containsEntry("inputTokens", 10L)
                .containsEntry("cacheReadTokens", 2L)
                .containsEntry("cacheWriteTokens", 1L)
                .containsEntry("outputTokens", 3L)
                .containsEntry("promptTokens", 13L)
                .containsEntry("completionTokens", 3L);
        verify(coding).code(eq(pi), any(CodingRequest.class), eq(60), any());
        verify(stepScope, times(2)).createTrusted("build:image", workspace, BuildEnvironment.maven("factory-dev-m2-cache"));
        verify(baseline, times(2)).close();
        verify(pi).close();
    }

    @Test void transientBaselineSignalsEngineRetryAndMakesZeroCodingCalls() {
        verifyFailedBaseline(Toolchain.MAVEN, "[ERROR] Could not transfer artifact net.sf.saxon:Saxon-HE:jar:12.9 "
                + "from/to central (https://repo.maven.apache.org/maven2): "
                + "Premature end of Content-Length delimited message body", true);
    }

    @Test void compilationFailureDoesNotRetryOrStartPi() {
        verifyFailedBaseline(Toolchain.MAVEN, "[ERROR] Compilation failure: cannot find symbol", false);
    }

    @Test void testFailureDoesNotRetryOrStartPi() {
        verifyFailedBaseline(Toolchain.MAVEN, "[ERROR] There are test failures", false);
    }

    @Test void repositoryNotFoundDoesNotRetryOrStartPi() {
        verifyFailedBaseline(Toolchain.MAVEN, "[ERROR] Could not find artifact org.example:missing:jar:1 in folio-nexus\n"
                + "[ERROR] Could not find artifact org.example:missing:jar:1 in index-data-nexus", false);
    }

    @Test void transientYarnInstallSignalsEngineRetryAndMakesZeroCodingCalls() {
        verifyFailedBaseline(Toolchain.YARN, "error An unexpected error occurred: "
                + "\"https://repository.folio.org/repository/npm-folioci/@folio%2fstripes/-/stripes.tgz: ETIMEDOUT\".", true);
    }

    @Test void yarnTestFailureDoesNotRetryOrStartPi() {
        verifyFailedBaseline(Toolchain.YARN, "FAIL src/api.test.js\n  Error: getaddrinfo ENOTFOUND api.example\n"
                + "Tests:       1 failed, 3 passed, 4 total\nerror Command failed with exit code 1.", false);
    }

    @Test void yarnPackageNotFoundDoesNotRetryOrStartPi() {
        verifyFailedBaseline(Toolchain.YARN, "error Couldn't find package \"left-pad@^9\" required by \"app\" on the \"npm\" registry.", false);
    }

    @Test void yarnLockfileErrorDoesNotRetryOrStartPi() {
        verifyFailedBaseline(Toolchain.YARN, "error Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`.", false);
    }

    private record YarnScenario(DevelopWorker worker, AgentContext context, DockerWorkloads.Workload baseline,
                                DevFactoryProperties properties, DevRuntimeProperties runtime, DockerWorkloads docker,
                                CandidateFreezer freezer,
                                DockerWorkloads.Workload agent, CodingRuntime coding, List<String> install) { }

    private YarnScenario yarnScenario(Processes.Result installResult) {
        return yarnScenario(installResult, true);
    }

    private YarnScenario yarnScenario(Processes.Result installResult, boolean trackedLockfile) {
        gitWorkspace(trackedLockfile);
        var repo = new DevFactoryProperties.Repository("owner/repo", "master", "node:image", "jest", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties("https://example.org", new TreeMap<>(Map.of("repo", repo)));
        var runtime = new DevRuntimeProperties(Map.of("jest", List.of("yarn", "test", "--maxWorkers=2")), null, 60, null,
                Map.of(), Map.of("jest", Toolchain.YARN));
        var environment = runtime.environment("jest");
        var install = runtime.installCommand("jest");
        var docker = mock(DockerWorkloads.class);
        var stepScope = mock(DockerWorkloads.StepScope.class);
        when(docker.beginStep(any(), eq("implement"))).thenReturn(stepScope);
        var baseline = mock(DockerWorkloads.Workload.class);
        var agent = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        when(coding.image()).thenReturn("pi:image");
        when(coding.identity()).thenReturn(Map.of("runtime", "pi", "image", "pi:image", "model", "model"));
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(stepScope.createTrusted("node:image", workspace, environment)).thenReturn(baseline);
        when(stepScope.createSeeded("pi:image", workspace, environment, "coding")).thenReturn(agent);
        var mode = trackedLockfile ? InstallMode.FROZEN : InstallMode.RESOLVE;
        when(baseline.readLockfile()).thenReturn(trackedLockfile ? java.util.Optional.empty() : java.util.Optional.of(LOCKFILE));
        when(baseline.executeKeepingTail(eq(runtime.verificationCommand("jest", mode)), eq(60), eq(Processes.OUTPUT_LIMIT), any()))
                .thenReturn(new Processes.Result(0, "Done in 1s."));
        when(agent.execute(install, 60)).thenReturn(installResult);
        when(coding.code(eq(agent), any(CodingRequest.class), eq(60), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of()));
        when(freezer.freeze(eq("repo"), anyString(), anyString(), any(), eq(false), eq(Toolchain.YARN)))
                .thenReturn(new org.folio.factory.devfactory.candidate.Candidate("repo", "a".repeat(40),
                        "b".repeat(40), org.folio.factory.devfactory.candidate.Candidate.sha256("patch"), "patch", "CANDIDATE_UNVERIFIED"));
        var codec = new FrontmatterCodec();
        var brief = codec.render(Map.of("state", "INTAKE_READY", "repository", Map.of("key", "repo", "base_sha", "a".repeat(40))), "Task");
        var context = new AgentContext(UUID.randomUUID(), "implement", Map.of(
                IntakeResolveWorker.TASK_BRIEF, new ArtifactContent(IntakeResolveWorker.TASK_BRIEF, 1, "text/markdown", brief),
                IntakeResolveWorker.CODING_REQUEST, new ArtifactContent(IntakeResolveWorker.CODING_REQUEST, 1,
                        "application/json", tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(request()))),
                null, Map.of(), List.of());
        var worker = new DevelopWorker(new org.folio.factory.devfactory.worker.InitialCodingAttempt(properties, runtime, docker,
                freezer, coding, codec, mock(AuditLog.class), model -> Map.of()), codec);
        return new YarnScenario(worker, context, baseline, properties, runtime, docker, freezer, agent, coding, install);
    }

    @Test void yarnCodingContainerIsPreparedBeforeTheAgentAndTheRequestNamesTheVerificationCommand() {
        var s = yarnScenario(new Processes.Result(0, "Done in 14s."));

        var result = s.worker().execute(s.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("CANDIDATE_UNVERIFIED");
        var request = org.mockito.ArgumentCaptor.forClass(CodingRequest.class);
        var order = inOrder(s.agent(), s.coding());
        order.verify(s.agent()).execute(s.install(), 60);
        order.verify(s.coding()).code(eq(s.agent()), request.capture(), eq(60), any());
        assertThat(request.getValue().constraints().verificationCommand()).isEqualTo("yarn test --maxWorkers=2");
        assertThat(request.getValue().constraints().dependenciesInstalled()).isTrue();
    }

    private static final PinnedLockfile LOCKFILE = PinnedLockfile.of("# yarn lockfile v1\n\nleft-pad@^1.3.0:\n  version \"1.3.0\"\n"
            .getBytes());

    /**
     * The base checkout: with a tracked lockfile, just that file; without one, a real Git repository,
     * so the package.json blob is the one a pinned lockfile records. The worker removes it afterwards.
     */
    private void gitWorkspace(boolean trackedLockfile) {
        try {
            java.nio.file.Files.createDirectories(workspace);
            if (trackedLockfile) {
                java.nio.file.Files.writeString(workspace.resolve("yarn.lock"), "# yarn lockfile v1\n");
                return;
            }
            CandidateFreezer.git(workspace, "init", "--quiet");
            java.nio.file.Files.writeString(workspace.resolve("package.json"), "{\"name\":\"fixture\"}");
            CandidateFreezer.git(workspace, "add", "--all");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test void moduleWithoutALockfileIsPinnedByTheStartingBuildAndSeededBeforeTheCodingInstall() {
        var s = yarnScenario(new Processes.Result(0, "Done in 14s."), false);
        String blob = CandidateFreezer.trackedBlob(workspace, "package.json");

        var result = s.worker().execute(s.context());

        var pinned = PinnedLockfile.parse(result.outputs().get(DevelopWorker.LOCKFILE)).orElseThrow();
        assertThat(pinned.sha256()).isEqualTo(LOCKFILE.sha256());
        assertThat(pinned.packageJsonBlob()).isEqualTo(blob).hasSize(40);
        assertThat(result.outputs().get(DevelopWorker.READINESS))
                .contains("\"lockfile\":\"PINNED\"", "\"lockfileSha256\":\"" + LOCKFILE.sha256() + "\"")
                .doesNotContain("--frozen-lockfile'");
        var order = inOrder(s.agent(), s.coding());
        order.verify(s.agent()).seedLockfile(pinned);
        order.verify(s.agent()).execute(s.install(), 60);
        order.verify(s.coding()).code(eq(s.agent()), any(CodingRequest.class), eq(60), any());
    }

    @Test void moduleThatTracksALockfileIsInstalledFrozenAndPinsNothing() {
        var s = yarnScenario(new Processes.Result(0, "Done in 14s."));

        var result = s.worker().execute(s.context());

        assertThat(PinnedLockfile.parse(result.outputs().get(DevelopWorker.LOCKFILE))).isEmpty();
        assertThat(result.outputs().get(DevelopWorker.LOCKFILE)).contains("NOT_NEEDED");
        assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("\"lockfile\":\"TRACKED\"")
                .doesNotContain("lockfileSha256");
        verify(s.agent(), never()).seedLockfile(any());
    }

    @Test void clarificationRestartSeedsTheLockfileFromTheArtifactWithoutANewStartingBuild() {
        var s = yarnScenario(new Processes.Result(0, "Done in 14s."), false);
        var attempt = new org.folio.factory.devfactory.worker.InitialCodingAttempt(
                s.properties(), s.runtime(), s.docker(), s.freezer(), s.coding(), new FrontmatterCodec(),
                mock(AuditLog.class), model -> Map.of());
        var pinned = LOCKFILE.forPackageJson(CandidateFreezer.trackedBlob(workspace, "package.json"));

        attempt.retry(s.context(), request(), tools.jackson.databind.json.JsonMapper.builder().build().readTree("{}"),
                pinned.toArtifact());

        verify(s.agent()).seedLockfile(pinned);
        verify(s.baseline(), never()).executeKeepingTail(any(), anyInt(), anyInt(), any());
    }

    @Test void unusableGeneratedLockfileBlocksBeforeModelSpend() {
        var s = yarnScenario(new Processes.Result(0, "Done in 14s."), false);
        when(s.baseline().readLockfile()).thenThrow(new IllegalStateException("The generated yarn.lock is unusable: Not a usable yarn lockfile"));

        var result = s.worker().execute(s.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT")
                .contains("could not pin the dependency lockfile");
        verify(s.coding(), never()).code(any(), any(), anyInt(), any());
    }

    @Test void seedCopyFailureBlocksTheEnvironmentWithoutModelSpendAndKeepsThePin() {
        var s = yarnScenario(new Processes.Result(0, "Done in 14s."), false);
        var message = DockerWorkloads.seedFailure(300, "factory-dev-yarn-cache", 9L * 1024 * 1024, "Command exceeded 300 seconds");
        var scope = s.docker().beginStep(UUID.randomUUID(), "implement");
        when(scope.createSeeded(any(), any(), any(), eq("coding")))
                .thenThrow(new DockerWorkloads.CacheSeedException(message, new IllegalStateException("x")));

        var result = s.worker().execute(s.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT", "(9.0 GB)", "limit 300 s");
        assertThat(PinnedLockfile.parse(result.outputs().get(DevelopWorker.LOCKFILE))).isPresent();
        verify(s.coding(), never()).code(any(), any(), anyInt(), any());
    }

    @Test void failedInstallInTheCodingContainerStopsBeforeAnyModelCall() {
        var s = yarnScenario(new Processes.Result(1, "error Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`."));

        var result = s.worker().execute(s.context());

        assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT")
                .contains("Workspace dependency install failed before model spend").contains("lockfile needs to be updated");
        verify(s.coding(), never()).code(any(), any(), anyInt(), any());
        verify(s.agent()).close();
    }

    @Test void registryTimeoutDuringTheCodingInstallAsksTheEngineToRetryWithoutAModelCall() {
        var s = yarnScenario(new Processes.Result(1,
                "error An unexpected error occurred: \"https://registry.example/pkg.tgz: ETIMEDOUT\"."));

        assertThatThrownBy(() -> s.worker().execute(s.context())).isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Workspace dependency install hit a network/download failure")
                .hasMessageContaining("coding has not started");
        verify(s.coding(), never()).code(any(), any(), anyInt(), any());
        verify(s.agent()).close();
    }

    private void verifyFailedBaseline(Toolchain toolchain, String failure, boolean retryable) {
        var repo = new DevFactoryProperties.Repository("owner/repo", "master", "build:image", "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties("https://example.org", new TreeMap<>(Map.of("repo", repo)));
        var plan = toolchain == Toolchain.YARN ? List.of("yarn", "test") : List.of("mvn", "test");
        var runtime = new DevRuntimeProperties(Map.of("unit", plan), null, 60, null, Map.of(),
                toolchain == Toolchain.YARN ? Map.of("unit", Toolchain.YARN) : Map.<String, Toolchain>of());
        var environment = runtime.environment("unit");
        if (toolchain == Toolchain.YARN) gitWorkspace(true);
        var command = runtime.verificationCommand("unit");
        var docker = mock(DockerWorkloads.class);
        var stepScope = mock(DockerWorkloads.StepScope.class);
        when(docker.beginStep(any(), eq("implement"))).thenReturn(stepScope);
        var workload = mock(DockerWorkloads.Workload.class);
        var freezer = mock(CandidateFreezer.class);
        var coding = mock(CodingRuntime.class);
        when(freezer.checkout(anyString(), anyString())).thenReturn(workspace);
        when(stepScope.createTrusted("build:image", workspace, environment)).thenReturn(workload);
        when(workload.executeKeepingTail(eq(command), eq(60), eq(Processes.OUTPUT_LIMIT), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<String> observer = invocation.getArgument(3);
                    observer.accept(toolchain == Toolchain.YARN ? "[2/4] Fetching packages..." : "[INFO] --- compiler:3.16.0:compile (default-compile) @ module ---");
                    return new Processes.Result(1, failure);
                });
        var codec = new FrontmatterCodec();
        var brief = codec.render(Map.of("state", "INTAKE_READY", "repository", Map.of("key", "repo", "base_sha", "a".repeat(40))), "Task");
        var context = new AgentContext(UUID.randomUUID(), "implement", Map.of(
                IntakeResolveWorker.TASK_BRIEF, new ArtifactContent(IntakeResolveWorker.TASK_BRIEF, 1, "text/markdown", brief),
                IntakeResolveWorker.CODING_REQUEST, new ArtifactContent(IntakeResolveWorker.CODING_REQUEST, 1,
                        "application/json", tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(request()))),
                null, Map.of(), List.of());
        var audit = mock(AuditLog.class);
        var worker = new DevelopWorker(new org.folio.factory.devfactory.worker.InitialCodingAttempt(properties, runtime, docker, freezer, coding, codec, audit, model -> Map.of()), codec);
        if (retryable) {
            // Repeated worker attempts retain the configured repository and do not add a global -U.
            for (int attempt = 0; attempt < 2; attempt++) {
                if (toolchain == Toolchain.YARN) gitWorkspace(true);
                assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(AgentExecutionException.class)
                        .hasMessageContaining("Starting build hit a network/download failure")
                        .hasMessageContaining("coding has not started")
                        .hasMessageContaining(toolchain == Toolchain.YARN ? "ETIMEDOUT" : "Saxon-HE");
            }
            verify(stepScope, times(2)).createTrusted("build:image", workspace, environment);
            verify(workload, times(2)).executeKeepingTail(eq(command), eq(60), eq(Processes.OUTPUT_LIMIT), any());
        } else {
            var result = worker.execute(context);
            assertThat(result.outputs().get(DevelopWorker.CANDIDATE)).contains("BLOCKED_ENVIRONMENT");
            assertThat(result.outputs().get(DevelopWorker.READINESS)).contains("BASELINE_FAILED", "output");
        }
        verify(coding, never()).code(any(), any(), anyInt(), any());
        verify(coding, never()).requireConfigured();
        verify(audit, atLeastOnce()).record(eq(context.executionId()),
                eq(org.folio.factory.core.domain.AuditEventType.RUNTIME_PROGRESS), eq("implement"), anyMap());
        verify(workload, times(retryable ? 2 : 1)).close();
    }

    private static CodingRequest request() {
        return new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("repo", "owner/repo", "master", "a".repeat(40)),
                CodingRequest.Constraints.defaults());
    }
}
