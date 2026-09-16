package org.folio.factory.devfactory.worker;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.verification.CandidateVerifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class VerifyWorkerTest {
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
}
