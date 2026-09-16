package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.Processes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.folio.factory.devfactory.candidate.CandidateFreezer.git;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CandidateVerifierTest {
    @TempDir Path root;
    private Candidate candidate;
    private CandidateVerifier verifier;
    private DockerWorkloads docker;
    private DockerWorkloads.Workload workload;
    private Path original;
    private final List<String> command = List.of("mvn", "-B", "-ntp", "clean", "test");

    @BeforeEach
    void prepare() throws Exception {
        original = Files.createDirectories(root.resolve("owner/source.git"));
        git(original, "init", "--quiet");
        Files.writeString(original.resolve("code.txt"), "base\n");
        git(original, "add", ".");
        git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "base");
        String base = git(original, "rev-parse", "HEAD").strip();
        Files.writeString(original.resolve("code.txt"), "changed\nPASS\n");
        git(original, "add", ".");
        String patch = git(original, "diff", "--cached", "--binary", "--full-index", base);
        candidate = new Candidate("source", base, git(original, "write-tree").strip(),
                Candidate.sha256(patch), patch, "CANDIDATE_UNVERIFIED");
        var repository = new DevFactoryProperties.Repository("owner/source", "master", "trusted-java21",
                "unit", List.of("TASK"), List.of());
        var properties = new DevFactoryProperties(root.toString(), new TreeMap<>(Map.of("source", repository)));
        var runtime = new DevRuntimeProperties(Map.of("unit", command), null, 60);
        docker = mock(DockerWorkloads.class);
        workload = mock(DockerWorkloads.Workload.class);
        when(workload.name()).thenReturn("fresh-verifier");
        when(docker.create(eq("trusted-java21"), any())).thenAnswer(invocation -> {
            Path fresh = invocation.getArgument(1);
            assertThat(fresh).isNotEqualTo(original);
            assertThat(git(fresh, "write-tree").strip()).isEqualTo(candidate.treeSha());
            assertThat(Files.readString(fresh.resolve("code.txt"))).isEqualTo("changed\nPASS\n");
            assertThat(fresh.resolve("target")).doesNotExist();
            return workload;
        });
        exportReport("<testsuite name=\"example\" tests=\"3\" skipped=\"0\" failures=\"0\" errors=\"0\"></testsuite>");
        verifier = new CandidateVerifier(properties, runtime, new CandidateFreezer(), docker);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 17})
    void actualExitControlsAcceptanceOfFreshExactCandidate(int exit) {
        when(workload.execute(command, 60)).thenReturn(new Processes.Result(exit, "Pi said PASS"));
        var receipt = verifier.verify("execution", candidate);
        assertThat(receipt.exitCode()).isEqualTo(exit);
        assertThat(receipt.result()).isEqualTo(exit == 0 ? "PASS" : "FAIL");
        assertThat(receipt.planId()).isEqualTo("unit");
        assertThat(receipt.image()).isEqualTo("trusted-java21");
        assertThat(receipt.argv()).isEqualTo(command);
        assertThat(receipt.testCount()).isEqualTo(exit == 0 ? 3 : 0);
        assertThat(receipt.finishedAt()).isAfterOrEqualTo(receipt.startedAt());
        if (exit == 0) receipt.requireVerified("execution", candidate);
        else assertThatThrownBy(() -> receipt.requireVerified("execution", candidate)).isInstanceOf(IllegalStateException.class);
        verify(workload).execute(command, 60);
        verify(workload).close();
    }

    @Test
    void zeroExitWithoutFreshExecutedTestsIsNotPass() {
        when(workload.execute(command, 60)).thenReturn(new Processes.Result(0, "Tests are skipped."));
        doNothing().when(workload).export(any());

        var receipt = verifier.verify("execution", candidate);

        assertThat(receipt.exitCode()).isZero();
        assertThat(receipt.testCount()).isZero();
        assertThat(receipt.result()).isEqualTo("FAIL");
        assertThat(receipt.output()).contains("no fresh executed Surefire tests");
        assertThatThrownBy(() -> receipt.requireVerified("execution", candidate))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allSkippedSurefireSuiteIsNotPass() {
        when(workload.execute(command, 60)).thenReturn(new Processes.Result(0, "BUILD SUCCESS"));
        exportReport("<testsuite tests=\"3\" skipped=\"3\" failures=\"0\" errors=\"0\"></testsuite>");

        var receipt = verifier.verify("execution", candidate);

        assertThat(receipt.testCount()).isZero();
        assertThat(receipt.result()).isEqualTo("FAIL");
    }

    @Test
    void ignoredSurefireFailureIsNotPassEvenWithZeroExit() {
        when(workload.execute(command, 60)).thenReturn(new Processes.Result(0, "BUILD SUCCESS"));
        exportReport("<testsuite tests=\"3\" skipped=\"0\" failures=\"1\" errors=\"0\"></testsuite>");

        var receipt = verifier.verify("execution", candidate);

        assertThat(receipt.testCount()).isEqualTo(3);
        assertThat(receipt.failureCount()).isEqualTo(1);
        assertThat(receipt.result()).isEqualTo("FAIL");
    }

    @Test
    void wrongTreeIsRejectedBeforeDockerRuns() {
        var wrong = new Candidate(candidate.repository(), candidate.baseSha(), "a".repeat(40),
                candidate.patchSha256(), candidate.patch(), candidate.state());
        assertThatThrownBy(() -> verifier.verify("execution", wrong)).hasMessageContaining("reconstruction failed");
        verifyNoInteractions(docker);
    }

    @Test
    void receiptCannotAuthorizeAnotherCandidateOrExecution() {
        when(workload.execute(command, 60)).thenReturn(new Processes.Result(0, "OK"));
        var receipt = verifier.verify("execution", candidate);
        var other = new Candidate(candidate.repository(), candidate.baseSha(), "a".repeat(40),
                candidate.patchSha256(), candidate.patch(), candidate.state());
        assertThatThrownBy(() -> receipt.requireVerified("execution", other)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> receipt.requireVerified("other-execution", candidate)).isInstanceOf(IllegalStateException.class);
    }

    private void exportReport(String xml) {
        doAnswer(invocation -> {
            Path exported = invocation.getArgument(0);
            Path reports = Files.createDirectories(exported.resolve("module/target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-example.xml"), xml);
            return null;
        }).when(workload).export(any());
    }
}
