package org.folio.factory.core.engine;
import org.folio.factory.core.agent.*;
import org.folio.factory.core.domain.*;
import org.folio.factory.core.hitl.*;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.repository.*;
import org.folio.factory.core.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
@DirtiesContext
class ResumableIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Autowired ExecutionEngine engine;
    @Autowired ExecutionClaimService claims;
    @Autowired StateManager state;
    @Autowired ArtifactStore artifacts;
    @Autowired AuditLog audit;
    @Autowired HitlReviewRepository reviews;
    @Autowired HitlDecisionService decisions;
    @Autowired FlowRegistry flows;
    @Autowired ResumableStepTransactions tx;
    @Autowired AgentLeaseHeartbeat heartbeat;

    UUID create(String trigger) { return state.createExecution("fake-resumable", "1.0.0", trigger).getId(); }
    void drive(UUID id, ExecutionStatus status) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
            claims.claim(100).forEach(engine::advance);
            assertThat(state.get(id).getStatus()).isEqualTo(status);
        });
    }
    HitlReview pending(UUID id) { return reviews.findByExecutionIdOrderByCreatedAtAsc(id).stream()
            .filter(r -> r.getStatus() == HitlReviewStatus.PENDING).findFirst().orElseThrow(); }

    @Test void twelveRoundsUseSameExecutionAndRejectPreviousReviews() {
        UUID id = create(null);
        for (int n = 1; n <= 12; n++) {
            drive(id, ExecutionStatus.AWAITING_HITL);
            assertThat(state.get(id).getCurrentStepIndex()).isEqualTo(1);
            assertThat(artifacts.getLatest(id, "counter").orElseThrow().getContent()).isEqualTo("" + n * 2);
            var review = pending(id);
            decisions.decide(review.getId(), HitlDecision.APPROVE, "operator", null, null);
            assertThatThrownBy(() -> decisions.decide(review.getId(), HitlDecision.APPROVE, "operator", null, null))
                    .isInstanceOf(IllegalStateException.class);
        }
        drive(id, ExecutionStatus.COMPLETED);
        assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(id)).hasSize(12);
    }
    @Test void genericDecisionsCannotBypassPolicy() {
        UUID id = create("{\"protected\":true}");
        drive(id, ExecutionStatus.AWAITING_HITL);
        var review = pending(id);
        for (var decision : HitlDecision.values())
            assertThatThrownBy(() -> decisions.decide(review.getId(), decision, "operator", null, Map.of("counter", "99")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Typed command");
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);
    }
    @Test void concurrentDecisionsConsumeReviewOnlyOnce() throws Exception {
        UUID id = create(null); drive(id, ExecutionStatus.AWAITING_HITL);
        var review = pending(id);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<Boolean> action = () -> {
                barrier.await();
                try { decisions.decide(review.getId(), HitlDecision.APPROVE, "operator", null, null); return true; }
                catch (RuntimeException e) { return false; }
            };
            var a = executor.submit(action); var b = executor.submit(action);
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(state.get(id).getCurrentStepIndex()).isEqualTo(1);
    }
    @Test void staleStartResultAndFailureWriteNothing() {
        UUID id = create(null); claims.claim(100);
        var execution = state.get(id);
        var flow = flows.require("fake-resumable"); var step = flow.step(1);
        int events = audit.forExecution(id).size();
        long stale = execution.getVersion() - 1;
        assertThatThrownBy(() -> tx.start(id, 0, stale, step, UUID.randomUUID(), Map.of()))
                .isInstanceOf(ResumableStepTransactions.StaleInvocation.class);
        assertThatThrownBy(() -> tx.complete(id, 0, stale, UUID.randomUUID(), flow, step, AgentResult.of("counter", "9")))
                .isInstanceOf(ResumableStepTransactions.StaleInvocation.class);
        assertThatThrownBy(() -> tx.fail(id, 0, stale, UUID.randomUUID(), flow, step, new IllegalStateException("late")))
                .isInstanceOf(ResumableStepTransactions.StaleInvocation.class);
        assertThatThrownBy(() -> tx.progress(id,0,stale,step.stepId(),Map.of("message","late")))
                .isInstanceOf(ResumableStepTransactions.StaleInvocation.class);
        assertThat(audit.forExecution(id)).hasSize(events);
        assertThat(artifacts.allForExecution(id)).isEmpty();
        assertThat(state.retryCount(id, "repeat")).isZero();
    }
    @Test void invalidWaitRollsBackArtifactsAndCompletionAudit() {
        UUID id = create(null); claims.claim(100);
        var e = state.get(id); var flow = flows.require("fake-resumable");
        int events = audit.forExecution(id).size();
        var result = new AgentResult(Map.of("counter", "9"), Map.of(), AgentResult.Disposition.WAIT,
                new AgentResult.WaitRequest(new HitlGateSpec("x", "x", "x", List.of()), "undeclared"));
        assertThatThrownBy(() -> tx.complete(id, 0, e.getVersion(), UUID.randomUUID(), flow, flow.step(1), result))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(artifacts.allForExecution(id)).isEmpty();
        assertThat(audit.forExecution(id)).hasSize(events);
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }
    @Test void reclaimedInvocationDetectsPriorStartFromDurableInputs() {
        UUID id = create(null); claims.claim(100);
        var e = state.get(id); var step = flows.require("fake-resumable").step(1);
        assertThat(tx.start(id, 0, e.getVersion(), step, UUID.randomUUID(), Map.of())).isFalse();
        claims.reclaimOrphanedRunning();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            claims.claim(100); assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        });
        e = state.get(id);
        assertThat(tx.start(id, 0, e.getVersion(), step, UUID.randomUUID(), Map.of())).isTrue();
    }
}
