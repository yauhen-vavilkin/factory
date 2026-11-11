package org.folio.factory.core;

import lombok.RequiredArgsConstructor;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.engine.ExecutionClaimService;
import org.folio.factory.core.engine.ExecutionEngine;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.StateManager;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Test support: stands in for the poller (disabled in core tests) by claiming and advancing
 * executions synchronously.
 *
 * <p>Every wait is condition-based rather than a fixed number of rounds: a freshly (re)scheduled
 * row can be briefly unclaimable while its next_run_at, set from this JVM's clock, is ahead of the
 * database clock that claimRunnable compares against. Waiting for the target state, as the real
 * poller would, absorbs that skew.</p>
 */
@Component
@RequiredArgsConstructor
public class EngineDriver {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL = Duration.ofMillis(50);
    private static final int BATCH = 10;

    private final ExecutionEngine engine;
    private final ExecutionClaimService claimService;
    private final StateManager stateManager;
    private final HitlReviewRepository reviews;

    /** Claims and advances every runnable execution until this one reaches {@code expected}. */
    public void driveUntil(UUID executionId, ExecutionStatus expected) {
        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            claimService.claim(BATCH).forEach(engine::advance);
            assertThat(stateManager.get(executionId).getStatus()).isEqualTo(expected);
        });
    }

    /** Claims and advances every runnable execution until this one reaches a terminal status. */
    public void driveUntilTerminal(UUID executionId) {
        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            claimService.claim(BATCH).forEach(engine::advance);
            assertThat(stateManager.get(executionId).getStatus().isTerminal()).isTrue();
        });
    }

    /** Claims, without advancing, until this execution is RUNNING. */
    public void claimUntilRunning(UUID executionId) {
        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            claimService.claim(BATCH);
            assertThat(stateManager.get(executionId).getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        });
    }

    public List<HitlReview> pendingReviews(UUID executionId) {
        return reviews.findByExecutionIdOrderByCreatedAtAsc(executionId).stream()
                .filter(review -> review.getStatus() == HitlReviewStatus.PENDING)
                .toList();
    }

    public HitlReview pendingReview(UUID executionId) {
        return pendingReviews(executionId).stream().findFirst().orElseThrow();
    }
}
