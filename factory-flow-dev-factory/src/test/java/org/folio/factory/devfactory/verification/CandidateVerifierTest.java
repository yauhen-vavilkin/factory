package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.BuildEnvironment;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.Toolchain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.folio.factory.devfactory.candidate.CandidateFreezer.git;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CandidateVerifierTest {
    @TempDir Path root;
    private Candidate candidate;
    private CandidateVerifier verifier;
    private DockerWorkloads docker;
    private DockerWorkloads.StepScope stepScope;
    private final UUID executionId = UUID.randomUUID();
    private DockerWorkloads.Workload workload;
    private Path original;
    private DevFactoryProperties properties;
    private List<String> command = List.of("mvn", "-B", "-ntp", "clean", "test");

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
        properties = new DevFactoryProperties(root.toString(), new TreeMap<>(Map.of("source", repository)));
        var runtime = new DevRuntimeProperties(Map.of("unit", command), null, 60, null);
        docker = mock(DockerWorkloads.class);
        stepScope = mock(DockerWorkloads.StepScope.class);
        when(docker.beginStep(executionId, "verify")).thenReturn(stepScope);
        workload = mock(DockerWorkloads.Workload.class);
        when(workload.name()).thenReturn("fresh-verifier");
        when(stepScope.createSeeded(eq("trusted-java21"), any(), eq(BuildEnvironment.maven("factory-dev-m2-cache")), eq("verification"))).thenAnswer(invocation -> {
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
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(exit, "Pi said PASS"));
        var receipt = verifier.verify(executionId, "verify", candidate);
        assertThat(receipt.exitCode()).isEqualTo(exit);
        assertThat(receipt.result()).isEqualTo(exit == 0 ? "PASS" : "FAIL");
        assertThat(receipt.planId()).isEqualTo("unit");
        assertThat(receipt.image()).isEqualTo("trusted-java21");
        assertThat(receipt.argv()).isEqualTo(command);
        assertThat(receipt.testCount()).isEqualTo(3);
        assertThat(receipt.finishedAt()).isAfterOrEqualTo(receipt.startedAt());
        if (exit == 0) receipt.requireVerified(executionId.toString(), candidate);
        else assertThatThrownBy(() -> receipt.requireVerified(executionId.toString(), candidate)).isInstanceOf(IllegalStateException.class);
        verify(workload).executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null);
        verify(workload).close();
    }

    @Test
    void zeroExitWithoutFreshExecutedTestsIsNotPass() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "Tests are skipped."));
        doNothing().when(workload).export(any());

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.exitCode()).isZero();
        assertThat(receipt.testCount()).isNull();
        assertThat(receipt.failureCount()).isNull();
        assertThat(receipt.errorCount()).isNull();
        assertThat(receipt.result()).isEqualTo("FAIL");
        assertThat(receipt.output()).contains("no fresh executed Surefire tests");
        assertThatThrownBy(() -> receipt.requireVerified(executionId.toString(), candidate))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allSkippedSurefireSuiteIsNotPass() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "BUILD SUCCESS"));
        exportReport("<testsuite tests=\"3\" skipped=\"3\" failures=\"0\" errors=\"0\"></testsuite>");

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.testCount()).isZero();
        assertThat(receipt.result()).isEqualTo("FAIL");
    }

    @Test
    void ignoredSurefireFailureIsNotPassEvenWithZeroExit() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "BUILD SUCCESS"));
        exportReport("<testsuite tests=\"3\" skipped=\"0\" failures=\"1\" errors=\"0\"></testsuite>");

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.testCount()).isEqualTo(3);
        assertThat(receipt.failureCount()).isEqualTo(1);
        assertThat(receipt.result()).isEqualTo("FAIL");
    }

    @Test
    void failingCommandPreservesFreshTestFailuresAndErrors() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1, "BUILD FAILURE"));
        exportReport("<testsuite tests='5' skipped='1' failures='2' errors='1'/>");

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.testCount()).isEqualTo(4);
        assertThat(receipt.failureCount()).isEqualTo(2);
        assertThat(receipt.errorCount()).isEqualTo(1);
        assertThat(receipt.result()).isEqualTo("FAIL");
        assertThatThrownBy(() -> receipt.requireVerified(executionId.toString(), candidate)).isInstanceOf(IllegalStateException.class);
        verify(workload).close();
    }

    @Test
    void buildFailureWithoutReportsHasNoUsableEvidence() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1, "Compilation failed"));
        doNothing().when(workload).export(any());

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.surefireReportCount()).isZero();
        assertThat(receipt.testCount()).isNull();
        assertThat(receipt.failureCount()).isNull();
        assertThat(receipt.errorCount()).isNull();
        assertThat(receipt.result()).isEqualTo("FAIL");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void invalidEvidenceCannotAuthorizePassAndRetainsDiagnostic(int exit) {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(exit, "x".repeat(20000)));
        exportReport("<testsuite tests='3' skipped='0' failures='0' errors='0'>");

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.result()).isEqualTo("FAIL");
        assertThat(receipt.surefireReportCount()).isZero();
        assertThat(receipt.failureCount()).isNull();
        assertThat(receipt.output()).hasSizeLessThanOrEqualTo(16000).contains("Invalid or unsafe Surefire XML");
        assertThatThrownBy(() -> receipt.requireVerified(executionId.toString(), candidate)).isInstanceOf(IllegalStateException.class);
        verify(workload).close();
    }

    @Test
    void runtimeFailureStillClosesVerificationWorkload() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "BUILD SUCCESS"));
        doThrow(new IllegalStateException("Docker export failed")).when(workload).export(any());

        assertThatThrownBy(() -> verifier.verify(executionId, "verify", candidate))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Docker export failed");
        verify(workload).close();
    }

    @Test
    void wrongTreeIsRejectedBeforeDockerRuns() {
        var wrong = new Candidate(candidate.repository(), candidate.baseSha(), "a".repeat(40),
                candidate.patchSha256(), candidate.patch(), candidate.state());
        assertThatThrownBy(() -> verifier.verify(executionId, "verify", wrong)).hasMessageContaining("reconstruction failed");
        verifyNoInteractions(docker);
    }

    @Test
    void receiptCannotAuthorizeAnotherCandidateOrExecution() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "OK"));
        var receipt = verifier.verify(executionId, "verify", candidate);
        var other = new Candidate(candidate.repository(), candidate.baseSha(), "a".repeat(40),
                candidate.patchSha256(), candidate.patch(), candidate.state());
        assertThatThrownBy(() -> receipt.requireVerified(executionId.toString(), other)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> receipt.requireVerified("other-execution", candidate)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2})
    void everyRequiredFailsafeSuiteMustExecute(int executed) {
        String required = "module/target/failsafe-reports/TEST-integration.xml";
        var runtime = new DevRuntimeProperties(Map.of("unit", command), null, 60, null,
                Map.of("unit", List.of(required)));
        verifier = new CandidateVerifier(properties, runtime, new CandidateFreezer(), docker);
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "BUILD SUCCESS"));
        doAnswer(invocation -> {
            Path exported = invocation.getArgument(0);
            Path unit = Files.createDirectories(exported.resolve("target/surefire-reports"));
            Files.writeString(unit.resolve("TEST-unit.xml"), "<testsuite tests='3' skipped='0' failures='0' errors='0'/>");
            if (executed >= 0) {
                Files.createDirectories(exported.resolve(required).getParent());
                Files.writeString(exported.resolve(required), "<testsuite tests='2' skipped='"
                        + (2 - executed) + "' failures='0' errors='0'/>");
            }
            return null;
        }).when(workload).export(any());

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.surefireReportCount()).isEqualTo(1);
        assertThat(receipt.failsafeReportCount()).isEqualTo(executed < 0 ? 0 : 1);
        assertThat(receipt.testCount()).isEqualTo(3 + Math.max(executed, 0));
        assertThat(receipt.result()).isEqualTo(executed > 0 ? "PASS" : "FAIL");
        if (executed > 0) receipt.requireVerified(executionId.toString(), candidate);
        else {
            assertThat(receipt.missingRequiredReports()).containsExactly(required);
            assertThat(receipt.failureKind()).isEqualTo(VerificationFailure.INSUFFICIENT_EVIDENCE);
            assertThat(receipt.failureKind().repairable()).isFalse();
            assertThatThrownBy(() -> receipt.requireVerified(executionId.toString(), candidate)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void assertionDiagnosticAndValidEvidenceIdentifyCandidateFailure() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1, "AssertionFailedError: expected: <1> but was: <2>"));
        exportReport("<testsuite tests='3' skipped='0' failures='1' errors='0'/>");
        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.CANDIDATE);
    }

    @Test
    void dockerFailureIsNeverCandidateFailureEvenWithAssertionFailures() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "AssertionFailedError; Could not find a valid Docker environment"));
        exportReport("<testsuite tests='3' skipped='0' failures='1' errors='0'/>");
        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.ENVIRONMENT);
    }

    @Test
    void timeoutRetainsEvidenceButCannotAuthorizeDeliveryOrRepair() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenThrow(new IllegalStateException("Command exceeded 60 seconds"));
        var receipt = verifier.verify(executionId, "verify", candidate);
        assertThat(receipt.exitCode()).isEqualTo(-1);
        assertThat(receipt.testCount()).isEqualTo(3);
        assertThat(receipt.failureKind()).isEqualTo(VerificationFailure.ENVIRONMENT);
        assertThat(receipt.failureKind().repairable()).isFalse();
        assertThatThrownBy(() -> receipt.requireVerified(executionId.toString(), candidate)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void compilerDiagnosticIsInformationalWithoutInventingTestEvidence() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "[ERROR] COMPILATION ERROR :\n[ERROR] /workspace/src/main/java/Example.java:[12,3] cannot find symbol"));
        doNothing().when(workload).export(any());
        var receipt = verifier.verify(executionId, "verify", candidate);
        assertThat(receipt.testCount()).isNull();
        assertThat(receipt.failureKind()).isEqualTo(VerificationFailure.CANDIDATE_COMPILE);
        assertThat(receipt.failureKind().repairable()).isFalse();
    }

    @Test
    void unrecognizedFailureWithPassingReportsRemainsUnknown() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1, "BUILD FAILURE"));
        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.UNKNOWN);
    }

    @Test
    void assertionTextWithoutEvidenceCannotAuthorizeRepair() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1, "AssertionFailedError"));
        doNothing().when(workload).export(any());
        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void persistedDiagnosticRedactsCredentials() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "Authorization: Bearer secret123 https://user:password@example.org/artifact token=secret456"));
        var receipt = verifier.verify(executionId, "verify", candidate);
        assertThat(receipt.output()).contains("[REDACTED]")
                .doesNotContain("secret123", "secret456", "user:password");
    }

    @Test
    void credentialCrossingDiagnosticTailBoundaryIsRedactedBeforeTruncation() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "x".repeat(4000) + "token=secret123" + "y".repeat(15994)));
        var receipt = verifier.verify(executionId, "verify", candidate);
        assertThat(receipt.output()).hasSizeLessThanOrEqualTo(16000)
                .doesNotContain("secret123", "ecret123", "cret123");
    }

    @Test
    void yarnPlanIsAuthorizedOnlyByAFreshExecutedJestReport() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "Tests: 7 passed"));
        exportJest("passing-with-skips.xml");

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.result()).isEqualTo("PASS");
        assertThat(receipt.toolchain()).isEqualTo(Toolchain.YARN);
        assertThat(receipt.surefireReportCount()).isEqualTo(1);
        assertThat(receipt.failsafeReportCount()).isZero();
        assertThat(receipt.testCount()).isEqualTo(7);
        receipt.requireVerified(executionId.toString(), candidate);
    }

    @Test
    void yarnZeroExitWithoutAReportIsNotPass() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "Done in 3.2s."));
        doNothing().when(workload).export(any());

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.result()).isEqualTo("FAIL");
        assertThat(receipt.testCount()).isNull();
        assertThat(receipt.output()).contains("no fresh executed Jest tests");
        assertThat(receipt.failureKind()).isEqualTo(VerificationFailure.INSUFFICIENT_EVIDENCE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void yarnSuiteThatFailsToLoadFailsWithAReceiptNotAnException(int exit) {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(exit, "Test suite failed to run"));
        exportJest("suite-load-error.xml");

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.result()).isEqualTo("FAIL");
        assertThat(receipt.errorCount()).isEqualTo(1);
        assertThat(receipt.failureCount()).isZero();
        assertThat(receipt.failureKind()).isEqualTo(VerificationFailure.UNKNOWN);
        assertThatThrownBy(() -> receipt.requireVerified(executionId.toString(), candidate)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void yarnAssertionFailureIdentifiesCandidateFailure() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "expect(received).toBe(expected) // Object.is equality\n\nExpected: 2\nReceived: 1"));
        exportJest("failing-test.xml");

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.failureCount()).isEqualTo(1);
        assertThat(receipt.failureKind()).isEqualTo(VerificationFailure.CANDIDATE);
    }

    @Test
    void yarnTimeoutAloneIsNotRepairable() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "thrown: \"Exceeded timeout of 5000 ms for a test.\""));
        exportJest("failing-test.xml");

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.failureKind()).isEqualTo(VerificationFailure.UNKNOWN);
        assertThat(receipt.failureKind().repairable()).isFalse();
    }

    @Test
    void yarnRegistryOutageIsEnvironmentEvenWithFailingTests() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "error An unexpected error occurred: \"https://registry.example/pkg: ETIMEDOUT\".\nExpected: 2\nReceived: 1"));
        exportJest("failing-test.xml");

        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.ENVIRONMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "error An unexpected error occurred: \"https://registry.example/pkg: ESOCKETTIMEDOUT\".",
            "error An unexpected error occurred: \"https://registry.example/pkg: Request failed \\\"503 Service Unavailable\\\"\"."})
    void yarnRegistryTimeoutAndEscaped5xxAreEnvironment(String registryError) {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1, registryError));
        exportJest("failing-test.xml");

        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.ENVIRONMENT);
    }

    @Test
    void yarnRecoveredNetworkRetryDoesNotHideARealAssertionFailure() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "info There appears to be trouble with your network connection. Retrying...\n"
                        + "expect(received).toBe(expected) // Object.is equality\n\nExpected: 2\nReceived: 1"));
        exportJest("failing-test.xml");

        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.CANDIDATE);
    }

    @Test
    void yarnInvalidJestReportCannotAuthorizePass() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "ok"));
        doAnswer(invocation -> {
            Path report = ((Path) invocation.getArgument(0)).resolve("artifacts/jest-junit/junit.xml");
            Files.createDirectories(report.getParent());
            Files.writeString(report, "<!DOCTYPE testsuites SYSTEM 'file:///etc/passwd'><testsuites/>");
            return null;
        }).when(workload).export(any());

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.result()).isEqualTo("FAIL");
        assertThat(receipt.output()).contains("Invalid or unsafe Jest XML");
        assertThat(receipt.failureKind()).isEqualTo(VerificationFailure.INVALID_EVIDENCE);
    }

    @Test
    void aSurefireReportCannotAuthorizeAYarnPlan() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "ok"));

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.result()).isEqualTo("FAIL");
        assertThat(receipt.surefireReportCount()).isZero();
        assertThat(receipt.testCount()).isNull();
    }

    @Test
    void aJestReportCannotAuthorizeAMavenPlan() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "ok"));
        exportJest("passing-with-skips.xml");

        var receipt = verifier.verify(executionId, "verify", candidate);

        assertThat(receipt.result()).isEqualTo("FAIL");
        assertThat(receipt.toolchain()).isEqualTo(Toolchain.MAVEN);
        assertThat(receipt.surefireReportCount()).isZero();
    }

    private static PinnedLockfile pinnedFor(String packageJsonBlob) {
        return PinnedLockfile.of("# yarn lockfile v1\n".getBytes()).forPackageJson(packageJsonBlob);
    }

    @Test
    void pinnedLockfileIsSeededAndTheInstallIsFrozenWhileTheCandidateKeepsPackageJson() {
        useYarn();
        var pinned = pinnedFor("");
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "Tests: 7 passed"));
        exportJest("passing-with-skips.xml");

        var receipt = verifier.verify(executionId, "verify", candidate, Optional.of(pinned));

        var order = inOrder(workload);
        order.verify(workload).seedLockfile(pinned);
        order.verify(workload).executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null);
        assertThat(receipt.result()).isEqualTo("PASS");
        assertThat(receipt.lockfileSha256()).isEqualTo(pinned.sha256());
        assertThat(receipt.argv().get(2)).contains("'--frozen-lockfile'");
    }

    @Test
    void candidateThatChangesPackageJsonKeepsThePinsButMayResolveNewDependencies() {
        var plan = command;
        useYarn();
        var pinned = pinnedFor("c".repeat(40));
        var resolving = new DevRuntimeProperties(Map.of("unit", plan), null, 60, null, Map.of(),
                Map.of("unit", Toolchain.YARN)).verificationCommand("unit", InstallMode.RESOLVE);
        when(workload.executeKeepingTail(resolving, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "Tests: 7 passed"));
        exportJest("passing-with-skips.xml");

        var receipt = verifier.verify(executionId, "verify", candidate, Optional.of(pinned));

        verify(workload).seedLockfile(pinned);
        assertThat(receipt.result()).isEqualTo("PASS");
        assertThat(receipt.argv()).isEqualTo(resolving);
        assertThat(receipt.argv().get(2)).doesNotContain("--frozen-lockfile");
        assertThat(receipt.lockfileSha256()).isEqualTo(pinned.sha256());
    }

    @Test
    void withoutAPinnedLockfileNothingIsSeededAndTheReceiptCarriesNoHash() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "Tests: 7 passed"));
        exportJest("passing-with-skips.xml");

        var receipt = verifier.verify(executionId, "verify", candidate);

        verify(workload, never()).seedLockfile(any());
        assertThat(receipt.lockfileSha256()).isNull();
    }

    private void useYarn() {
        var runtime = new DevRuntimeProperties(Map.of("unit", command), null, 60, null, Map.of(),
                Map.of("unit", Toolchain.YARN));
        command = runtime.verificationCommand("unit");
        verifier = new CandidateVerifier(properties, runtime, new CandidateFreezer(), docker);
        when(stepScope.createSeeded(eq("trusted-java21"), any(), eq(runtime.environment("unit")), eq("verification")))
                .thenReturn(workload);
    }

    private void exportJest(String fixture) {
        doAnswer(invocation -> {
            Path report = ((Path) invocation.getArgument(0)).resolve("artifacts/jest-junit/junit.xml");
            Files.createDirectories(report.getParent());
            try (var in = getClass().getResourceAsStream("/jest-junit/" + fixture)) {
                Files.write(report, in.readAllBytes());
            }
            return null;
        }).when(workload).export(any());
    }

    private void exportReport(String xml) {
        doAnswer(invocation -> {
            Path exported = invocation.getArgument(0);
            Path reports = Files.createDirectories(exported.resolve("module/target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-example.xml"), xml);
            return null;
        }).when(workload).export(any());
    }

    @Test
    void yarnPlanRemovesAStaleReportBeforeRunningAndMavenPlansDoNot() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "ok"));
        exportJest("passing-with-skips.xml");

        verifier.verify(executionId, "verify", candidate);

        var order = inOrder(workload);
        order.verify(workload).execute(List.of("rm", "-rf", "artifacts/jest-junit"), 60);
        order.verify(workload).executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null);

        reset(workload);
        when(workload.name()).thenReturn("fresh-verifier");
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(0, "ok"));
        exportReport("<testsuite tests='3' skipped='0' failures='0' errors='0'/>");
        new CandidateVerifier(properties, new DevRuntimeProperties(Map.of("unit", command), null, 60, null),
                new CandidateFreezer(), docker).verify(executionId, "verify", candidate);
        verify(workload, never()).execute(eq(List.of("rm", "-rf", "artifacts/jest-junit")), anyInt());
    }

    @Test
    void yarnNetworkTextInTestOutputDoesNotHideARealAssertionFailure() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "console.error\n    Error: connection refused while calling the api\n"
                        + "expect(received).toBe(expected) // Object.is equality\n\nExpected: 2\nReceived: 1"));
        exportJest("failing-test.xml");

        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.CANDIDATE);
    }

    @Test
    void yarnInfrastructureFailuresAreStillEnvironmentAnywhereInTheOutput() {
        useYarn();
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "Expected: 2\nReceived: 1\nno space left on device"));
        exportJest("failing-test.xml");

        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.ENVIRONMENT);
    }

    @Test
    void mavenNetworkTextAnywhereInTheOutputIsStillEnvironment() {
        when(workload.executeKeepingTail(command, 60, Processes.OUTPUT_LIMIT, null)).thenReturn(new Processes.Result(1,
                "AssertionFailedError: expected: <1> but was: <2>\nConnection refused"));
        exportReport("<testsuite tests='3' skipped='0' failures='1' errors='0'/>");

        assertThat(verifier.verify(executionId, "verify", candidate).failureKind()).isEqualTo(VerificationFailure.ENVIRONMENT);
    }
}
