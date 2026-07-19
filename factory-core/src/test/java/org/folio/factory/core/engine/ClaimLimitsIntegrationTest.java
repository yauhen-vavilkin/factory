package org.folio.factory.core.engine;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "factory.limits.max-concurrent-executions=2"
})
@Testcontainers
@Transactional
class ClaimLimitsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ExecutionClaimService claimService;

    @Autowired
    StateManager stateManager;

    @Autowired
    PipelineExecutionRepository executions;

    /**
     * The test transaction pins Postgres now() (transaction_timestamp) to the
     * transaction start, so a row created mid-test gets next_run_at after DB now()
     * and would never pass claimRunnable's {@code next_run_at <= now()} gate.
     * Backdating makes the row claimable regardless of clock skew.
     */
    private PipelineExecution createClaimablePending() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", "{}");
        execution.setNextRunAt(Instant.now().minusSeconds(60));
        return executions.saveAndFlush(execution);
    }

    @Test
    void claim_moreRunnableThanFreeSlots_claimsOnlyUpToCap() {
        List<UUID> all = List.of(
                createClaimablePending().getId(),
                createClaimablePending().getId(),
                createClaimablePending().getId());

        List<UUID> claimed = claimService.claim(10);

        assertThat(claimed).hasSize(2);
        claimed.forEach(id ->
                assertThat(stateManager.get(id).getStatus()).isEqualTo(ExecutionStatus.RUNNING));
        List<UUID> unclaimed = all.stream().filter(id -> !claimed.contains(id)).toList();
        assertThat(unclaimed).hasSize(1);
        assertThat(stateManager.get(unclaimed.getFirst()).getStatus()).isEqualTo(ExecutionStatus.PENDING);
    }

    @Test
    void claim_capFullyOccupied_claimsNothing() {
        createClaimablePending();
        createClaimablePending();
        assertThat(claimService.claim(10)).hasSize(2);
        PipelineExecution third = createClaimablePending();

        List<UUID> claimed = claimService.claim(10);

        assertThat(claimed).isEmpty();
        assertThat(stateManager.get(third.getId()).getStatus()).isEqualTo(ExecutionStatus.PENDING);
    }

    @Test
    void claim_slotFreedByTerminalTransition_claimsAgain() {
        createClaimablePending();
        createClaimablePending();
        createClaimablePending();
        List<UUID> firstBatch = claimService.claim(10);
        assertThat(firstBatch).hasSize(2);

        stateManager.transition(firstBatch.getFirst(), ExecutionStatus.COMPLETED, null);
        List<UUID> secondBatch = claimService.claim(10);

        assertThat(secondBatch).hasSize(1);
        assertThat(firstBatch).doesNotContain(secondBatch.getFirst());
        assertThat(stateManager.get(secondBatch.getFirst()).getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    void reclaimOrphanedRunning_returnsAllRunningToPendingImmediately() {
        createClaimablePending();
        createClaimablePending();
        List<UUID> claimed = claimService.claim(10);
        assertThat(claimed).hasSize(2);

        int reclaimed = claimService.reclaimOrphanedRunning();

        assertThat(reclaimed).isEqualTo(2);
        for (UUID id : claimed) {
            PipelineExecution execution = stateManager.get(id);
            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.PENDING);
            assertThat(execution.getNextRunAt()).isBeforeOrEqualTo(Instant.now());
        }
    }
}
