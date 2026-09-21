package org.folio.factory.devfactory.worker;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.verification.CandidateVerifier;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerifyWorkerTest {
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
        when(verifier.verify(execution.toString(), candidate)).thenReturn(receipt);
        var input = new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json", json.writeValueAsString(candidate));
        var context = new AgentContext(execution, "verify", Map.of(DevelopWorker.CANDIDATE, input),
                null, Map.of(), List.of());

        var result = new VerifyWorker(verifier).execute(context);

        assertThat(json.readValue(result.outputs().get(VerifyWorker.RECEIPT), VerificationReceipt.class)).isEqualTo(receipt);
        var outcome = json.readTree(result.outputs().get(VerifyWorker.RESULT));
        assertThat(outcome.path("state").asString()).isEqualTo("VERIFICATION_FAILED");
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
}
