package org.folio.factory.devfactory.worker;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.pilot.AttemptDeadline;
import org.folio.factory.devfactory.pilot.PilotStops;
import org.folio.factory.devfactory.pilot.RecordingPilotAttempts;
import org.folio.factory.devfactory.verification.CandidateVerifier;
import org.folio.factory.devfactory.verification.VerificationFailure;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerifyWorkerTest {
    @Test
    void eligibleCandidateFailureExplicitlyDefersRepairAndCannotReachDelivery() {
        var verifier = mock(CandidateVerifier.class);
        var delivery = mock(CandidateDelivery.class);
        var github = mock(GitHubConnector.class);
        var json = JsonMapper.builder().build();
        var execution = UUID.randomUUID();
        var candidate = new Candidate("source", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt(execution.toString(), candidate.repository(), candidate.baseSha(),
                candidate.treeSha(), candidate.patchSha256(), "unit", "image", List.of("mvn", "test"),
                "workload", Instant.EPOCH, Instant.EPOCH.plusSeconds(10), 1, 1, 3, 1, 0, "FAIL",
                "AssertionFailedError: expected: <ready> but was: <failed>", VerificationFailure.CANDIDATE,
                0, List.of("target/surefire-reports/TEST-Example.xml"), List.of());
        when(verifier.verify(execution, "verify", candidate, java.util.Optional.empty())).thenReturn(receipt);
        var context = new AgentContext(execution, "verify", Map.of(DevelopWorker.CANDIDATE,
                new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json", json.writeValueAsString(candidate))),
                null, Map.of(), List.of());

        var verification = new VerifyWorker(verifier).execute(context);

        assertThat(receipt.failureKind().repairable()).isTrue();
        assertThat(json.readValue(verification.outputs().get(VerifyWorker.RECEIPT), VerificationReceipt.class))
                .isEqualTo(receipt);
        var result = json.readTree(verification.outputs().get(VerifyWorker.RESULT));
        assertThat(result.path("state").asString()).isEqualTo("VERIFICATION_FAILED");
        assertThat(result.path("repair").asString()).isEqualTo("NOT_ATTEMPTED");
        assertThat(result.path("repairReason").asString()).contains("deferred", "single-attempt");
        assertThat(result.path("failureKind").asString()).isEqualTo("CANDIDATE");
        assertThat(result.path("failureCount").asInt()).isEqualTo(1);
        assertThat(result.path("treeSha").asString()).isEqualTo(candidate.treeSha());

        var publishContext = new AgentContext(execution, "publish", Map.of(VerifyWorker.RESULT,
                new ArtifactContent(VerifyWorker.RESULT, 1, "application/json",
                        verification.outputs().get(VerifyWorker.RESULT))), null, Map.of(), List.of());
        var published = new DeliveryWorker(null, null, null, delivery, null, null, github, null, null).execute(publishContext);

        assertThat(json.readTree(published.outputs().get(DeliveryWorker.DELIVERY)).path("state").asString())
                .isEqualTo("NOT_RUN");
        assertThat(published.outputs().get(VerifyWorker.RESULT))
                .isEqualTo(verification.outputs().get(VerifyWorker.RESULT));
        verifyNoInteractions(delivery, github);
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
                "workload", Instant.EPOCH, Instant.EPOCH, 1, 0, null, null, null, "FAIL", "No usable test evidence");
        when(verifier.verify(execution, "verify", candidate, java.util.Optional.empty())).thenReturn(receipt);
        var input = new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json", json.writeValueAsString(candidate));
        var context = new AgentContext(execution, "verify", Map.of(DevelopWorker.CANDIDATE, input),
                null, Map.of(), List.of());

        var result = new VerifyWorker(verifier).execute(context);

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

    @Test
    void developmentBlockerIsReportedWithoutStartingVerification() {
        CandidateVerifier verifier = mock(CandidateVerifier.class);
        var candidate = new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json",
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"provider unavailable\"}");
        var context = new AgentContext(UUID.randomUUID(), "verify", Map.of(DevelopWorker.CANDIDATE, candidate),
                null, Map.of(), List.of());

        var result = new VerifyWorker(verifier).execute(context);

        assertThat(result.outputs().get(VerifyWorker.RECEIPT)).contains("NOT_RUN");
        assertThat(result.outputs().get(VerifyWorker.RESULT)).contains("DEVELOPMENT_FAILED", "provider unavailable");
        verifyNoInteractions(verifier);
    }

    @Test
    void repositoryMismatchPlaceholderCannotBeVerified() {
        CandidateVerifier verifier = mock(CandidateVerifier.class);
        var candidate = new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json",
                "{\"state\":\"REPOSITORY_MISMATCH\",\"reason\":\"Repository target remains unresolved\"}");
        var context = new AgentContext(UUID.randomUUID(), "verify", Map.of(DevelopWorker.CANDIDATE, candidate),
                null, Map.of(), List.of());

        var result = new VerifyWorker(verifier).execute(context);

        assertThat(result.outputs().get(VerifyWorker.RECEIPT)).contains("NOT_RUN");
        assertThat(result.outputs().get(VerifyWorker.RESULT))
                .contains("REPOSITORY_MISMATCH", "Repository target remains unresolved");
        verifyNoInteractions(verifier);
    }

    @Test
    void aPilotAttemptThatLostItsSlotCannotVerifyAndStopsForInspection() {
        var pilots = RecordingPilotAttempts.pilot("source");
        pilots.ownsSlot = false;
        var verifier = mock(CandidateVerifier.class);

        var result = new VerifyWorker(verifier, pilots).execute(candidateContext());

        var outcome = json.readTree(result.outputs().get(VerifyWorker.RESULT));
        assertThat(outcome.path("state").asString()).isEqualTo(PilotStops.INSPECTION_STATE);
        assertThat(outcome.path("reason").asString()).contains("no longer owns");
        assertThat(json.readTree(result.outputs().get(VerifyWorker.RECEIPT)).path("state").asString())
                .isEqualTo("NOT_RUN");
        verifyNoInteractions(verifier);
        assertThat(pilots.stops).singleElement().asString().startsWith("verify: ");
    }

    @Test
    void anExpiredPilotDeadlineCannotVerifyAndStopsWithTheDeadlineState() {
        var pilots = RecordingPilotAttempts.pilot("source")
                .withDeadline(new AttemptDeadline(Instant.now().minusSeconds(1)));
        var verifier = mock(CandidateVerifier.class);

        var result = new VerifyWorker(verifier, pilots).execute(candidateContext());

        var outcome = json.readTree(result.outputs().get(VerifyWorker.RESULT));
        assertThat(outcome.path("state").asString()).isEqualTo(PilotStops.DEADLINE_STATE);
        assertThat(outcome.path("reason").asString()).contains("deadline expired before verification");
        verifyNoInteractions(verifier);
    }

    @Test
    void aCurrentPilotAttemptVerifiesUnderItsRemainingBudget() {
        var pilots = RecordingPilotAttempts.pilot("source")
                .withDeadline(new AttemptDeadline(Instant.now().plusSeconds(45)));
        var verifier = mock(CandidateVerifier.class);
        when(verifier.verify(eq(executionId()), eq("verify"), any(Candidate.class), any(Optional.class)))
                .thenReturn(failedReceipt());

        var result = new VerifyWorker(verifier, pilots).execute(candidateContext());

        assertThat(json.readTree(result.outputs().get(VerifyWorker.RESULT)).path("state").asString())
                .isEqualTo("VERIFICATION_FAILED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Optional<AttemptDeadline>> deadline =
                (ArgumentCaptor<Optional<AttemptDeadline>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Optional.class);
        verify(verifier).verify(eq(executionId()), eq("verify"), any(Candidate.class), deadline.capture());
        assertThat(deadline.getValue()).isPresent();
        assertThat(deadline.getValue().get().remaining(java.time.Clock.systemUTC()).toSeconds())
                .isLessThan(45);
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
                0, List.of(), List.of());
    }

    private Candidate candidate() {
        return new Candidate("source", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
    }

    private AgentContext candidateContext() {
        return new AgentContext(execution, "verify", Map.of(DevelopWorker.CANDIDATE,
                new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json",
                        json.writeValueAsString(candidate()))), null, Map.of(), List.of());
    }
}
