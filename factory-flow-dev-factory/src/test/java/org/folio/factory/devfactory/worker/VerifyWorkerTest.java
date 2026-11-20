package org.folio.factory.devfactory.worker;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.Toolchain;
import org.folio.factory.devfactory.tasks.AttemptDeadline;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.tasks.RecordingTaskAttempts;
import org.folio.factory.devfactory.verification.CandidateVerifier;
import org.folio.factory.devfactory.verification.VerificationFailure;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import static org.mockito.ArgumentMatchers.anyInt;
import org.folio.factory.devfactory.DevFlowSettings;

class VerifyWorkerTest {
    private static final DevFactoryProperties REPOSITORIES = new DevFactoryProperties("https://github.com",
            new TreeMap<>(Map.of("source", new DevFactoryProperties.Repository("owner/source", "master", "image",
                    "unit", List.of("TASK"), List.of(), null))));
    private static final String SOURCE = "https://github.com/owner/source.git";
    private static final DevRuntimeProperties RUNTIME = new DevRuntimeProperties(Map.of(), null, 60, null, null);
    private static final ArtifactContent NO_LOCKFILE = new ArtifactContent(DevelopWorker.LOCKFILE, 1,
            "application/json", PinnedLockfile.none("NOT_NEEDED", "No lockfile to pin"));

    @Test
    void aPassingReceiptIsVerifiedWithItsPlanAndNoRepair() {
        var verifier = mock(CandidateVerifier.class);
        var candidate = candidate();
        var receipt = new VerificationReceipt(execution.toString(), candidate.repository(), candidate.baseSha(),
                candidate.treeSha(), candidate.patchSha256(), "unit", "image", List.of("mvn", "test"),
                "workload", Instant.EPOCH, Instant.EPOCH.plusSeconds(10), 0, 1, 3, 0, 0, "PASS", "ok", null,
                0, List.of(), List.of(), null, null);
        when(verifier.verify(execution, "verify", candidate, SOURCE, 60, Optional.empty())).thenReturn(receipt);

        var verification = new VerifyWorker(verifier, new DevFlowSettings(REPOSITORIES, RUNTIME),
                RecordingTaskAttempts.none().guard()).execute(candidateContext());

        assertThat(json.readValue(verification.outputs().get(VerifyWorker.RECEIPT), VerificationReceipt.class))
                .isEqualTo(receipt);
        var result = json.readTree(verification.outputs().get(VerifyWorker.RESULT));
        assertThat(result.path("state").asString()).isEqualTo("VERIFIED");
        assertThat(result.path("verificationPlan").asString()).isEqualTo("unit");
        assertThat(result.path("treeSha").asString()).isEqualTo(candidate.treeSha());
        assertThat(result.has("repair")).isFalse();
        assertThat(result.has("repairReason")).isFalse();
    }

    @Test
    void eligibleCandidateFailureExplicitlyDefersRepair() {
        var verifier = mock(CandidateVerifier.class);
        var candidate = candidate();
        var receipt = new VerificationReceipt(execution.toString(), candidate.repository(), candidate.baseSha(),
                candidate.treeSha(), candidate.patchSha256(), "unit", "image", List.of("mvn", "test"),
                "workload", Instant.EPOCH, Instant.EPOCH.plusSeconds(10), 1, 1, 3, 1, 0, "FAIL",
                "AssertionFailedError: expected: <ready> but was: <failed>", VerificationFailure.CANDIDATE,
                0, List.of("surefire"), List.of(), null, null);
        when(verifier.verify(execution, "verify", candidate, SOURCE, 60, Optional.empty())).thenReturn(receipt);

        var verification = new VerifyWorker(verifier, new DevFlowSettings(REPOSITORIES, RUNTIME),
                RecordingTaskAttempts.none().guard()).execute(candidateContext());

        assertThat(json.readValue(verification.outputs().get(VerifyWorker.RECEIPT), VerificationReceipt.class))
                .isEqualTo(receipt);
        var result = json.readTree(verification.outputs().get(VerifyWorker.RESULT));
        assertThat(result.path("state").asString()).isEqualTo("VERIFICATION_FAILED");
        assertThat(result.path("repair").asString()).isEqualTo("NOT_ATTEMPTED");
        assertThat(result.path("repairReason").asString()).contains("deferred", "single-attempt");
        assertThat(result.path("failureKind").asString()).isEqualTo("CANDIDATE");
        assertThat(result.path("failureCount").asInt()).isEqualTo(1);
        assertThat(result.path("treeSha").asString()).isEqualTo(candidate.treeSha());
    }

    @Test
    void unavailableEvidenceRemainsNullInPersistedReceiptAndResult() {
        var verifier = mock(CandidateVerifier.class);
        var json = JsonMapper.builder().build();
        var execution = UUID.randomUUID();
        var candidate = new Candidate("source", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt(execution.toString(), candidate.repository(), candidate.baseSha(),
                candidate.treeSha(), candidate.patchSha256(), "unit", "image", List.of("mvn", "test"),
                "workload", Instant.EPOCH, Instant.EPOCH, 1, 0, null, null, null, "FAIL", "No usable test evidence",
                        null, 0, List.of(), List.of(), null, null);
        when(verifier.verify(execution, "verify", candidate, SOURCE, 60, Optional.empty())).thenReturn(receipt);
        var input = new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json", json.writeValueAsString(candidate));
        var context = new AgentContext(execution, "verify", Map.of(DevelopWorker.LOCKFILE, NO_LOCKFILE, DevelopWorker.CANDIDATE, input),
                null, Map.of(), List.of());

        var result = new VerifyWorker(verifier, new DevFlowSettings(REPOSITORIES, RUNTIME), RecordingTaskAttempts.none().guard()).execute(context);

        assertThat(json.readValue(result.outputs().get(VerifyWorker.RECEIPT), VerificationReceipt.class)).isEqualTo(receipt);
        var outcome = json.readTree(result.outputs().get(VerifyWorker.RESULT));
        assertThat(outcome.path("state").asString()).isEqualTo("VERIFICATION_FAILED");
        assertThat(outcome.path("failureKind").asString()).isEqualTo("UNKNOWN");
        assertThat(outcome.path("repair").asString()).isEqualTo("NOT_ATTEMPTED");
        assertThat(outcome.path("repairReason").asString()).contains("not eligible");
        for (String count : List.of("testCount", "failureCount", "errorCount")) {
            assertThat(outcome.path(count).isNull()).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"DEVELOPMENT_FAILED, provider unavailable", "REPOSITORY_MISMATCH, Repository target remains unresolved"})
    void aCandidatePlaceholderIsReportedWithoutStartingVerification(String state, String reason) {
        CandidateVerifier verifier = mock(CandidateVerifier.class);
        var candidate = new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json",
                json.writeValueAsString(Map.of("state", state, "reason", reason)));
        var context = new AgentContext(UUID.randomUUID(), "verify", Map.of(DevelopWorker.CANDIDATE, candidate),
                null, Map.of(), List.of());

        var result = new VerifyWorker(verifier, new DevFlowSettings(REPOSITORIES, RUNTIME), RecordingTaskAttempts.none().guard()).execute(context);

        assertThat(result.outputs().get(VerifyWorker.RECEIPT)).contains("NOT_RUN");
        assertThat(result.outputs().get(VerifyWorker.RESULT)).contains(state, reason);
        verifyNoInteractions(verifier);
    }

    @Test
    void aTaskAttemptThatLostItsSlotCannotVerifyAndStopsForInspection() {
        var taskAttempts = RecordingTaskAttempts.taskRun("source");
        taskAttempts.ownsSlot = false;
        var verifier = mock(CandidateVerifier.class);

        var result = new VerifyWorker(verifier, new DevFlowSettings(REPOSITORIES, RUNTIME), taskAttempts.guard()).execute(candidateContext());

        var outcome = json.readTree(result.outputs().get(VerifyWorker.RESULT));
        assertThat(outcome.path("state").asString()).isEqualTo(TaskStops.INSPECTION_STATE);
        assertThat(outcome.path("reason").asString()).contains("no longer owns");
        assertThat(json.readTree(result.outputs().get(VerifyWorker.RECEIPT)).path("state").asString())
                .isEqualTo("NOT_RUN");
        verifyNoInteractions(verifier);
        assertThat(taskAttempts.stops).singleElement().asString().startsWith("verify: ");
    }

    @Test
    void anExpiredTaskDeadlineCannotVerifyAndStopsWithTheDeadlineState() {
        var taskAttempts = RecordingTaskAttempts.taskRun("source")
                .withDeadline(new AttemptDeadline(Instant.now().minusSeconds(1)));
        var verifier = mock(CandidateVerifier.class);

        var result = new VerifyWorker(verifier, new DevFlowSettings(REPOSITORIES, RUNTIME), taskAttempts.guard()).execute(candidateContext());

        var outcome = json.readTree(result.outputs().get(VerifyWorker.RESULT));
        assertThat(outcome.path("state").asString()).isEqualTo(TaskStops.DEADLINE_STATE);
        assertThat(outcome.path("reason").asString()).isEqualTo(TaskStops.deadlineReason("verification"));
        verifyNoInteractions(verifier);
    }

    @Test
    void aCurrentTaskAttemptVerifiesUnderItsRemainingBudget() {
        var taskAttempts = RecordingTaskAttempts.taskRun("source")
                .withDeadline(new AttemptDeadline(Instant.now().plusSeconds(45)));
        var verifier = mock(CandidateVerifier.class);
        when(verifier.verify(eq(executionId()), eq("verify"), any(Candidate.class), eq(SOURCE), anyInt(), any()))
                .thenReturn(failedReceipt());

        var result = new VerifyWorker(verifier, new DevFlowSettings(REPOSITORIES, RUNTIME), taskAttempts.guard()).execute(candidateContext());

        assertThat(json.readTree(result.outputs().get(VerifyWorker.RESULT)).path("state").asString())
                .isEqualTo("VERIFICATION_FAILED");
        var timeout = ArgumentCaptor.forClass(Integer.class);
        verify(verifier).verify(eq(executionId()), eq("verify"), any(Candidate.class), eq(SOURCE),
                timeout.capture(), any());
        assertThat(timeout.getValue()).isPositive().isLessThan(45);
    }

    @Test
    void pinnedLockfileReachesTheVerifierAndItsHashIsInTheReceipt() {
        var verifier = mock(CandidateVerifier.class);
        var candidate = candidate();
        var pinned = PinnedLockfile.of("# yarn lockfile v1\n".getBytes()).forPackageJson("c".repeat(40));
        var receipt = new VerificationReceipt(execution.toString(), candidate.repository(), candidate.baseSha(),
                candidate.treeSha(), candidate.patchSha256(), "unit", "image", List.of("yarn", "test"),
                "workload", Instant.EPOCH, Instant.EPOCH, 0, 1, 3, 0, 0, "PASS", "ok", VerificationFailure.NONE,
                0, List.of(), List.of(), Toolchain.YARN, pinned.sha256());
        when(verifier.verify(execution, "verify", candidate, SOURCE, 60, Optional.of(pinned))).thenReturn(receipt);
        var context = new AgentContext(execution, "verify", Map.of(
                DevelopWorker.LOCKFILE, new ArtifactContent(DevelopWorker.LOCKFILE, 1, "application/json",
                        pinned.toArtifact()),
                DevelopWorker.CANDIDATE, new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json",
                        json.writeValueAsString(candidate))), null, Map.of(), List.of());

        var result = new VerifyWorker(verifier, new DevFlowSettings(REPOSITORIES, RUNTIME),
                RecordingTaskAttempts.none().guard()).execute(context);

        assertThat(json.readTree(result.outputs().get(VerifyWorker.RECEIPT)).path("lockfileSha256").asString())
                .isEqualTo(pinned.sha256());
        assertThat(json.readTree(result.outputs().get(VerifyWorker.RESULT)).path("state").asString())
                .isEqualTo("VERIFIED");
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID execution = UUID.randomUUID();

    private UUID executionId() {
        return execution;
    }

    private VerificationReceipt failedReceipt() {
        var candidate = candidate();
        return new VerificationReceipt(execution.toString(), candidate.repository(), candidate.baseSha(),
                candidate.treeSha(), candidate.patchSha256(), "unit", "image", List.of("mvn", "test"),
                "workload", Instant.EPOCH, Instant.EPOCH.plusSeconds(10), 1, 1, 3, 1, 0, "FAIL",
                "AssertionFailedError: expected", VerificationFailure.CANDIDATE,
                0, List.of(), List.of(), null, null);
    }

    private Candidate candidate() {
        return new Candidate("source", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
    }

    private AgentContext candidateContext() {
        return new AgentContext(execution, "verify", Map.of(DevelopWorker.LOCKFILE, NO_LOCKFILE,
                DevelopWorker.CANDIDATE, new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json",
                        json.writeValueAsString(candidate()))), null, Map.of(), List.of());
    }
}
