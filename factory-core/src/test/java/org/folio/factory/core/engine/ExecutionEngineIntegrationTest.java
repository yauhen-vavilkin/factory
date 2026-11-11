package org.folio.factory.core.engine;

import org.folio.factory.core.EngineDriver;
import org.folio.factory.core.FakeRejectingPostProcessor;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
@DirtiesContext
class ExecutionEngineIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    EngineDriver driver;

    @Autowired
    ExecutionEngine engine;

    @Autowired
    ExecutionClaimService claimService;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    AuditLog auditLog;

    @Autowired
    HitlReviewRepository reviews;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void runsTwoStepFlowToCompletionThroughArtifacts() {
        PipelineExecution execution = stateManager.createExecution(
                "fake-simple", "1.0.0", "{\"issueKey\":\"ERM-1\"}");
        driver.driveUntil(execution.getId(), ExecutionStatus.COMPLETED);

        PipelineExecution finished = stateManager.get(execution.getId());
        assertThat(finished.getCompletedAt()).isNotNull();

        var first = artifactStore.getLatest(execution.getId(), "first.md").orElseThrow();
        var second = artifactStore.getLatest(execution.getId(), "second.md").orElseThrow();
        assertThat(first.getContent()).contains("$trigger").contains("ERM-1");
        // Only a step that declares $trigger gets the payload: FakeEchoWorker puts it first.
        assertThat(second.getContent()).startsWith("echo[second.md] <- first.md:");
        assertThat(first.getCreatedBy()).isEqualTo("first");

        var eventTypes = auditLog.forExecution(execution.getId()).stream()
                .map(e -> e.getEventType()).toList();
        assertThat(eventTypes).containsSubsequence(
                AuditEventType.EXECUTION_STARTED,
                AuditEventType.STEP_STARTED,
                AuditEventType.ARTIFACT_WRITTEN,
                AuditEventType.STEP_COMPLETED,
                AuditEventType.STEP_STARTED,
                AuditEventType.ARTIFACT_WRITTEN,
                AuditEventType.STEP_COMPLETED,
                AuditEventType.EXECUTION_COMPLETED);
    }

    @Test
    void undeclaredOutputIsRejectedAndNothingPersisted() {
        PipelineExecution execution = stateManager.createExecution("fake-rogue", "1.0.0", null);
        driver.driveUntil(execution.getId(), ExecutionStatus.FAILED_ESCALATED);

        assertThat(artifactStore.getLatest(execution.getId(), "rogue.md")).isEmpty();
        assertThat(artifactStore.getLatest(execution.getId(), "expected.md")).isEmpty();

        var failure = auditLog.forExecution(execution.getId()).stream()
                .filter(e -> e.getEventType() == AuditEventType.STEP_FAILED)
                .findFirst().orElseThrow();
        assertThat(failure.getDetail()).contains("undeclared").contains("rogue.md");
    }

    @Test
    void exhaustedRetryBudgetEscalatesToHumanReview() {
        PipelineExecution execution = stateManager.createExecution("fake-failing", "1.0.0", null);
        driver.driveUntil(execution.getId(), ExecutionStatus.FAILED_ESCALATED);

        assertThat(stateManager.retryCount(execution.getId(), "doomed")).isEqualTo(2);

        var eventTypes = auditLog.forExecution(execution.getId()).stream()
                .map(e -> e.getEventType()).toList();
        // The escalation review is inserted atomically with the FAILED_ESCALATED
        // transition (HITL_REQUESTED), then the engine attributes the escalation
        // (ESCALATED) only once that review exists.
        assertThat(eventTypes).containsSubsequence(
                AuditEventType.STEP_FAILED,
                AuditEventType.RETRY_SCHEDULED,
                AuditEventType.STEP_FAILED,
                AuditEventType.HITL_REQUESTED,
                AuditEventType.ESCALATED);

        var escalations = reviews.findByExecutionIdOrderByCreatedAtAsc(execution.getId());
        assertThat(escalations).hasSize(1);
        assertThat(escalations.getFirst().getGateId()).isEqualTo(HitlGateOpener.ESCALATION_GATE_ID);
        assertThat(escalations.getFirst().getStatus()).isEqualTo(HitlReviewStatus.PENDING);
        assertThat(escalations.getFirst().getReviewPackage()).contains("deliberate test failure");
    }

    @Test
    void guardedAdvanceRefusesStaleDrivers() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", null);
        driver.claimUntilRunning(execution.getId());

        // Wrong index (stale duplicate driver) → refused; correct index → advances.
        assertThat(stateManager.advanceStep(execution.getId(), 5, ExecutionStatus.RUNNING)).isFalse();
        assertThat(stateManager.get(execution.getId()).getCurrentStepIndex()).isZero();
        assertThat(stateManager.advanceStep(execution.getId(), 0, ExecutionStatus.RUNNING)).isTrue();
        assertThat(stateManager.get(execution.getId()).getCurrentStepIndex()).isEqualTo(1);
        // Wrong expected status → refused.
        assertThat(stateManager.advanceStep(execution.getId(), 1, ExecutionStatus.AWAITING_HITL)).isFalse();
    }

    @Test
    void guardedHeartbeatRejectsChangedStepStatusAndReclaimedLease() {
        PipelineExecution execution = stateManager.createExecution("fake-simple", "1.0.0", null);
        driver.claimUntilRunning(execution.getId());
        PipelineExecution running = stateManager.get(execution.getId());
        long version = running.getVersion();

        assertThat(stateManager.heartbeat(execution.getId(), 1, version)).isFalse();
        assertThat(stateManager.heartbeat(execution.getId(), 0, version)).isTrue();
        assertThat(stateManager.get(execution.getId()).getVersion()).isEqualTo(version + 1);
        assertThat(stateManager.heartbeat(execution.getId(), 0, version)).isFalse();

        claimService.release(execution.getId());
        assertThat(stateManager.heartbeat(execution.getId(), 0,
                stateManager.get(execution.getId()).getVersion())).isFalse();
        driver.claimUntilRunning(execution.getId());
        assertThat(stateManager.heartbeat(execution.getId(), 0, version + 1)).isFalse();
        assertThat(stateManager.advanceStep(execution.getId(), 0, ExecutionStatus.RUNNING, version + 1)).isFalse();
        assertThat(stateManager.heartbeat(execution.getId(), 0,
                stateManager.get(execution.getId()).getVersion())).isTrue();
        assertThat(stateManager.advanceStep(execution.getId(), 0, ExecutionStatus.RUNNING,
                stateManager.get(execution.getId()).getVersion())).isTrue();
    }

    @Test
    void reaperLeavesFreshLeaseAloneAndReturnsStaleLeaseToPending() {
        UUID id = stateManager.createExecution("fake-simple", "1.0.0", null).getId();
        driver.claimUntilRunning(id);
        long version = stateManager.get(id).getVersion();
        assertThat(stateManager.heartbeat(id, 0, version)).isTrue();

        claimService.reapStale(3600);
        assertThat(stateManager.get(id).getStatus()).isEqualTo(ExecutionStatus.RUNNING);

        jdbc.update("UPDATE pipeline_execution SET updated_at = now() - interval '2 hours' WHERE id = ?", id);
        assertThat(claimService.reapStale(3600)).isEqualTo(1);
        assertThat(stateManager.get(id).getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(stateManager.heartbeat(id, 0, version + 1)).isFalse();
    }

    @Test
    void postProcessorRejectionStoresNothingAndEscalatesAfterRetries() {
        UUID id = stateManager.createExecution("fake-post-rejected", "1.0.0", null).getId();
        driver.driveUntil(id, ExecutionStatus.FAILED_ESCALATED);

        assertThat(artifactStore.allForExecution(id)).isEmpty();
        assertThat(stateManager.retryCount(id, "gated")).isEqualTo(2);
        var failures = auditLog.forExecution(id).stream()
                .filter(e -> e.getEventType() == AuditEventType.STEP_FAILED)
                .toList();
        assertThat(failures).hasSize(2)
                .allSatisfy(failure -> assertThat(failure.getDetail()).contains(FakeRejectingPostProcessor.MESSAGE));
        assertThat(auditLog.forExecution(id)).noneMatch(e -> e.getEventType() == AuditEventType.ARTIFACT_WRITTEN);
    }

    @Test
    void missingDeclaredOutputFailsStepAndStoresNothing() {
        UUID id = stateManager.createExecution("fake-silent", "1.0.0", null).getId();
        driver.driveUntil(id, ExecutionStatus.FAILED_ESCALATED);

        assertThat(artifactStore.allForExecution(id)).isEmpty();
        var failure = auditLog.forExecution(id).stream()
                .filter(e -> e.getEventType() == AuditEventType.STEP_FAILED)
                .findFirst().orElseThrow();
        assertThat(failure.getDetail()).contains("did not produce declared output artifact 'expected.md'");
    }

    @Test
    void stepSeesOnlyItsDeclaredInputArtifacts() {
        UUID id = stateManager.createExecution("fake-scoped", "1.0.0", "{\"issueKey\":\"ERM-4\"}").getId();
        driver.driveUntil(id, ExecutionStatus.COMPLETED);

        var third = artifactStore.getLatest(id, "scoped-third.md").orElseThrow();
        assertThat(third.getContent())
                .startsWith("echo[scoped-third.md] <- scoped-first.md:")
                .doesNotContain("scoped-second.md");
    }

    @Test
    void retryWithLongBackoffIsNotClaimableBeforeItsTime() {
        UUID id = stateManager.createExecution("fake-backoff", "1.0.0", null).getId();
        driver.claimUntilRunning(id);

        engine.advance(id);

        PipelineExecution retrying = stateManager.get(id);
        assertThat(retrying.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(stateManager.retryCount(id, "doomed")).isEqualTo(1);
        assertThat(retrying.getNextRunAt()).isCloseTo(Instant.now().plusSeconds(3600), within(60, SECONDS));
        assertThat(claimService.claim(10)).doesNotContain(id);
        assertThat(stateManager.get(id).getStatus()).isEqualTo(ExecutionStatus.PENDING);
    }
}
