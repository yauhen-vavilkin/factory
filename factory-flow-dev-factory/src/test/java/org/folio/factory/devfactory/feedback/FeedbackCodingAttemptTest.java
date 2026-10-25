package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.Toolchain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FeedbackCodingAttemptTest {
    @TempDir Path source;

    private final DockerWorkloads.Workload workload = mock(DockerWorkloads.Workload.class);
    private final CodingRuntime coding = mock(CodingRuntime.class);
    private final CandidateFreezer freezer = mock(CandidateFreezer.class);
    private final DevRuntimeProperties runtime = new DevRuntimeProperties(
            Map.of("jest", List.of("yarn", "test", "--maxWorkers=2")), null, 60, null, Map.of(),
            Map.of("jest", Toolchain.YARN));
    private final List<String> install = runtime.installCommand("jest");
    private final Candidate candidate = new Candidate("repo", "a".repeat(40), "b".repeat(40),
            Candidate.sha256("patch"), "patch", "CANDIDATE_UNVERIFIED");
    private final Map<String, String> outputs = new HashMap<>();

    private CodingOutcome run() {
        return run(Optional.empty());
    }

    private CodingOutcome run(Optional<PinnedLockfile> pinned) {
        var repo = new DevFactoryProperties.Repository("owner/repo", "master", "node:image", "jest", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties("https://example.org", new TreeMap<>(Map.of("repo", repo)));
        var docker = mock(DockerWorkloads.class);
        var scope = mock(DockerWorkloads.StepScope.class);
        when(docker.beginStep(any(), eq("round-1"))).thenReturn(scope);
        when(scope.createSeeded("pi:image", source, runtime.environment("jest"), "coding")).thenReturn(workload);
        when(coding.image()).thenReturn("pi:image");
        when(coding.identity()).thenReturn(Map.of("runtime", "pi", "image", "pi:image", "model", "model"));
        when(freezer.reconstruct(anyString(), any())).thenReturn(source);
        when(freezer.freeze(eq("repo"), anyString(), anyString(), any(), eq(true), eq(Toolchain.YARN))).thenReturn(candidate);
        var state = new FeedbackState();
        state.pr = new PrFeedbackGateway.PullRequest("fork/repo", 1, "factory/x", "master", "c".repeat(40), "https://x", true);
        var request = new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(), List.of(),
                new CodingRequest.RepositoryTarget("repo", "owner/repo", "master", "a".repeat(40)),
                CodingRequest.Constraints.defaults());
        var attempt = new FeedbackCodingAttempt(properties, freezer, coding, docker, runtime, model -> Map.of());
        var context = new AgentContext(UUID.randomUUID(), "round-1", Map.of(), null, Map.of(), List.of());
        return attempt.run(context, state, request, candidate, pinned, "https://example.org/owner/repo.git", "round-1",
                outputs, new HashMap<>(), event -> { });
    }

    @Test
    void feedbackRoundInstallsDependenciesBeforeTheAgentAndNamesTheVerificationCommand() {
        when(workload.execute(install, 60)).thenReturn(new Processes.Result(0, "Done in 14s."));
        when(coding.code(eq(workload), any(CodingRequest.class), eq(60), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of()));

        var outcome = run();

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        var request = ArgumentCaptor.forClass(CodingRequest.class);
        var order = inOrder(workload, coding);
        order.verify(workload).execute(install, 60);
        order.verify(coding).code(eq(workload), request.capture(), eq(60), any());
        assertThat(request.getValue().constraints().verificationCommand()).isEqualTo("yarn test --maxWorkers=2");
        assertThat(request.getValue().constraints().dependenciesInstalled()).isTrue();
    }

    private PinnedLockfile pinForPackageJsonOf(String packageJson, boolean sameAsCheckout) throws Exception {
        CandidateFreezer.git(source, "init", "--quiet");
        java.nio.file.Files.writeString(source.resolve("package.json"), packageJson);
        CandidateFreezer.git(source, "add", "package.json");
        String blob = sameAsCheckout ? CandidateFreezer.trackedBlob(source, "package.json") : "e".repeat(40);
        return PinnedLockfile.of("# yarn lockfile v1\n".getBytes()).forPackageJson(blob);
    }

    @Test
    void roundOnAPrHeadWhosePackageJsonMovedPastThePinInstallsWithoutFrozenLockfile() throws Exception {
        var pin = pinForPackageJsonOf("{\"name\":\"fixture\",\"dependencies\":{\"lodash\":\"^4.17.21\"}}", false);
        var resolving = runtime.installCommand("jest", InstallMode.RESOLVE);
        when(workload.execute(resolving, 60)).thenReturn(new Processes.Result(0, "Done in 14s."));
        when(coding.code(eq(workload), any(CodingRequest.class), eq(60), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of()));

        var outcome = run(Optional.of(pin));

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(resolving).doesNotContain("--frozen-lockfile");
        var order = inOrder(workload, coding);
        order.verify(workload).seedLockfile(pin);
        order.verify(workload).execute(resolving, 60);
        order.verify(coding).code(eq(workload), any(CodingRequest.class), eq(60), any());
        verify(workload, never()).execute(install, 60);
    }

    @Test
    void roundOnAPrHeadWithThePinnedPackageJsonStaysFrozen() throws Exception {
        var pin = pinForPackageJsonOf("{\"name\":\"fixture\"}", true);
        when(workload.execute(install, 60)).thenReturn(new Processes.Result(0, "Done in 14s."));
        when(coding.code(eq(workload), any(CodingRequest.class), eq(60), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of()));

        run(Optional.of(pin));

        verify(workload).seedLockfile(pin);
        verify(workload).execute(install, 60);
    }

    @Test
    void failedInstallSkipsTheModelCallButStillSavesTheCurrentWorkspace() {
        when(workload.execute(install, 60)).thenReturn(new Processes.Result(1,
                "error Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`."));

        var outcome = run();

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.FAILED);
        assertThat(outcome.failure().message()).contains("Dependency install failed before any model call")
                .contains("lockfile needs to be updated");
        verify(coding, never()).code(any(), any(), anyInt(), any());
        verify(workload).stop();
        assertThat(outputs).containsKey(FeedbackArtifacts.CANDIDATE);
    }
}
