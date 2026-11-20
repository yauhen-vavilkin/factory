package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.CodingTranscripts;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.Toolchain;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackCodingAttemptTest {
    private static final String MODEL_KEY = "sk-test-0123456789abcdef";
    private static final String MAVEN = "unit";
    private static final String JEST = "jest";

    @TempDir Path source;
    private final CandidateFreezer freezer = mock(CandidateFreezer.class);
    private final CodingRuntime coding = mock(CodingRuntime.class);
    private final DockerWorkloads docker = mock(DockerWorkloads.class);
    private final DockerWorkloads.Workload workload = mock(DockerWorkloads.Workload.class);
    private final DevRuntimeProperties runtime = new DevRuntimeProperties(
            Map.of(MAVEN, List.of("mvn", "test"), JEST, List.of("yarn", "test", "--maxWorkers=2")), null, 60, null,
            Map.of(), Map.of(JEST, Toolchain.YARN), null, 0);
    private final List<String> install = runtime.installCommand(JEST);
    private final Candidate previous = new Candidate("repo", "a".repeat(40), "b".repeat(40), Candidate.sha256(""), "",
            Candidate.UNVERIFIED);
    private FeedbackWorker.Operation operation;
    private Path checkout;

    @BeforeEach
    void agentFinishesARound() throws Exception {
        var scope = mock(DockerWorkloads.StepScope.class);
        when(docker.beginStep(any(), anyString())).thenReturn(scope);
        when(scope.createSeeded(any(), any(), any(), any())).thenReturn(workload);
        checkout = Files.createTempDirectory(source, "checkout");
        // The attempt removes its checkout when it ends.
        when(freezer.reconstruct(anyString(), any())).thenReturn(checkout);
        when(coding.redact(anyString())).thenAnswer(call -> call.getArgument(0));
        when(coding.holdsSecret(anyString())).thenAnswer(
                call -> CodingRuntime.holdsAny(call.getArgument(0), List.of(MODEL_KEY, "codemie-proxy")));
        when(coding.code(any(), any(), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of("inputTokens", 10L)));
        var state = new FeedbackState();
        state.attemptId = UUID.randomUUID().toString();
        state.pr = new PrFeedbackGateway.PullRequest("fork/repo", 7, "factory/task", "main", "a".repeat(40),
                "https://github.com/fork/repo/pull/7", true);
        operation = new FeedbackWorker.Operation(
                new AgentContext(UUID.randomUUID(), "pr-follow-up", Map.of(), null, Map.of(), List.of()),
                state, new HashMap<>(), "pr-feedback-1", new LinkedHashMap<>());
    }

    @Test
    void editsThatAddAModelsApiKeyAreNotSavedAndTheReasonNamesTheFile() {
        agentLeaves("src/main/resources/application.yml", "api-key: " + MODEL_KEY);

        assertThatThrownBy(() -> runRound(MAVEN, Optional.empty()))
                .hasMessageStartingWith("Latest edits could not be saved; the previous confirmed patch is retained.")
                .hasMessageContaining(TaskStops.secretInChangesCause("src/main/resources/application.yml"))
                .hasMessageNotContaining("Start a new run")
                .hasMessageNotContaining(MODEL_KEY);
        assertThat(operation.outputs()).doesNotContainKey(FeedbackArtifacts.CANDIDATE);
        assertThat(operation.metrics()).containsEntry("inputTokens", 10L);
    }

    @Test
    void editsThatHoldOnlyTheShortPublicWordOfALocalProxyAreSaved() {
        agentLeaves("README.md", "Start the proxy with the token codemie-proxy.");

        runRound(MAVEN, Optional.empty());

        assertThat(operation.outputs()).containsKey(FeedbackArtifacts.CANDIDATE);
    }

    @Test
    void feedbackRoundInstallsDependenciesBeforeTheAgentAndNamesTheVerificationCommand() {
        agentLeaves("src/index.js", "export const x = 1;");
        when(workload.execute(install, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "Done"));

        var outcome = runRound(JEST, Optional.empty());

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        var request = ArgumentCaptor.forClass(CodingRequest.class);
        var order = inOrder(workload, coding);
        order.verify(workload).execute(install, 60, Processes.OUTPUT_LIMIT, null);
        order.verify(coding).code(eq(workload), request.capture(), eq(60), any(), any());
        assertThat(request.getValue().constraints().verificationCommand()).isEqualTo("yarn test --maxWorkers=2");
        assertThat(request.getValue().constraints().dependenciesInstalled()).isTrue();
    }

    @Test
    void roundOnAPrHeadWhosePackageJsonMovedPastThePinInstallsWithoutFrozenLockfile() throws Exception {
        agentLeaves("src/index.js", "export const x = 1;");
        var pin = pinForPackageJsonOf("{\"name\":\"fixture\",\"dependencies\":{\"lodash\":\"^4.17.21\"}}", false);
        var resolving = runtime.installCommand(JEST, InstallMode.RESOLVE);
        when(workload.execute(resolving, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "Done"));

        var outcome = runRound(JEST, Optional.of(pin));

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(resolving).doesNotContain("--frozen-lockfile");
        var order = inOrder(workload, coding);
        order.verify(workload).seedLockfile(pin);
        order.verify(workload).execute(resolving, 60, Processes.OUTPUT_LIMIT, null);
        order.verify(coding).code(eq(workload), any(CodingRequest.class), eq(60), any(), any());
        verify(workload, never()).execute(install, 60, Processes.OUTPUT_LIMIT, null);
    }

    @Test
    void roundOnAPrHeadWithThePinnedPackageJsonStaysFrozen() throws Exception {
        agentLeaves("src/index.js", "export const x = 1;");
        var pin = pinForPackageJsonOf("{\"name\":\"fixture\"}", true);
        when(workload.execute(install, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "Done"));

        runRound(JEST, Optional.of(pin));

        verify(workload).seedLockfile(pin);
        verify(workload).execute(install, 60, Processes.OUTPUT_LIMIT, null);
    }

    @Test
    void failedInstallSkipsTheModelCallButStillSavesTheCurrentWorkspace() {
        agentLeaves("src/index.js", "export const x = 1;");
        when(workload.execute(install, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "error Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`."));

        var outcome = runRound(JEST, Optional.empty());

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.FAILED);
        assertThat(outcome.failure().message()).contains("Dependency install failed before any model call")
                .contains("lockfile needs to be updated");
        verify(coding, never()).code(any(), any(), anyInt(), any(), any());
        verify(workload).stop();
        assertThat(operation.outputs()).containsKey(FeedbackArtifacts.CANDIDATE);
    }

    private void agentLeaves(String file, String line) {
        var patch = "diff --git a/" + file + " b/" + file + "\n--- a/" + file + "\n+++ b/" + file
                + "\n@@ -1 +1,2 @@\n existing\n+" + line + "\n";
        when(freezer.freeze(anyString(), anyString(), anyString(), any(), anyBoolean(), any())).thenReturn(
                new Candidate("repo", "a".repeat(40), "b".repeat(40), Candidate.sha256(patch), patch,
                        Candidate.UNVERIFIED));
    }

    private PinnedLockfile pinForPackageJsonOf(String packageJson, boolean sameAsCheckout) throws Exception {
        CandidateFreezer.git(checkout, "init", "--quiet");
        Files.writeString(checkout.resolve("package.json"), packageJson);
        CandidateFreezer.git(checkout, "add", "package.json");
        String blob = sameAsCheckout ? CandidateFreezer.trackedBlob(checkout, "package.json") : "e".repeat(40);
        return PinnedLockfile.of("# yarn lockfile v1\n".getBytes()).forPackageJson(blob);
    }

    private CodingOutcome runRound(String plan, Optional<PinnedLockfile> lockfile) {
        var repository = new DevFactoryProperties.Repository("upstream/repo", "main", "build:image", plan,
                List.of("TEST"), List.of(), null);
        var repositories = new DevFactoryProperties("https://example.org", new TreeMap<>(Map.of("repo", repository)));
        var attempt = new FeedbackCodingAttempt(repositories, freezer, coding, docker, runtime,
                (modelId, model) -> Map.of(), CodingTranscripts.none());
        var request = new CodingRequest("TEST-1", "Summary", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("repo", "upstream/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults(), null, List.of());
        return attempt.run(operation, request, previous, lockfile, "file:///fork/repo.git", event -> { });
    }
}
