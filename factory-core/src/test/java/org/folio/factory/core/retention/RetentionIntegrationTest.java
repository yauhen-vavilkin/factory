package org.folio.factory.core.retention;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.ArtifactRepository;
import org.folio.factory.core.repository.AuditEventRepository;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "factory.retention.enabled=true",
        "factory.retention.ttl-days=30",
        "factory.retention.batch-size=10",
        "factory.retention.run-cron=-"})
@Testcontainers
class RetentionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    RetentionService retentionService;

    @Autowired
    RetentionRepository retentionRepository;

    @Autowired
    RetentionPurger retentionPurger;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    ArtifactRepository artifacts;

    @Autowired
    HitlReviewRepository hitlReviews;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void purgeExpired_expiredCompletedTree_deletesExecutionsArtifactsReviewsButNeverAudit() {
        PipelineExecution root = stateManager.createExecution("fake-simple", "1.0.0", "{}");
        UUID rootId = root.getId();
        PipelineExecution child = stateManager.createChildExecution("fake-simple", "1.0.0", "{}", rootId, 0);
        UUID childId = child.getId();
        artifactStore.putMarkdown(rootId, "root-report", "# root", "test");
        artifactStore.putMarkdown(childId, "child-report", "# child", "test");
        hitlReviews.save(new HitlReview(rootId, "gate-x", 0, "{}"));
        stateManager.transition(childId, ExecutionStatus.COMPLETED, null);
        stateManager.transition(rootId, ExecutionStatus.COMPLETED, null);
        backdate(childId, 40);
        backdate(rootId, 40);
        List<Long> preAuditIds = auditEvents.findByExecutionIdOrderByIdAsc(rootId)
                .stream().map(AuditEvent::getId).toList();
        assertThat(preAuditIds).isNotEmpty();

        retentionService.purgeExpired();

        assertThat(executions.findById(rootId)).isEmpty();
        assertThat(executions.findById(childId)).isEmpty();
        assertThat(artifacts.findByExecutionIdOrderByNameAscVersionAsc(rootId)).isEmpty();
        assertThat(artifacts.findByExecutionIdOrderByNameAscVersionAsc(childId)).isEmpty();
        assertThat(hitlReviews.findByExecutionIdOrderByCreatedAtAsc(rootId)).isEmpty();
        List<AuditEvent> postAudit = auditEvents.findByExecutionIdOrderByIdAsc(rootId);
        assertThat(postAudit).extracting(AuditEvent::getId).containsAll(preAuditIds);
        assertThat(postAudit).extracting(AuditEvent::getEventType).contains(AuditEventType.RETENTION_PURGED);
    }

    @Test
    void purgeExpired_treeWithEscalatedChild_leftEntirelyInPlace() {
        PipelineExecution root = stateManager.createExecution("fake-simple", "1.0.0", "{}");
        UUID rootId = root.getId();
        PipelineExecution child = stateManager.createChildExecution("fake-simple", "1.0.0", "{}", rootId, 0);
        UUID childId = child.getId();
        stateManager.transition(childId, ExecutionStatus.FAILED_ESCALATED, null);
        stateManager.transition(rootId, ExecutionStatus.COMPLETED, null);
        backdate(childId, 40);
        backdate(rootId, 40);

        retentionService.purgeExpired();

        assertThat(executions.findById(rootId)).isPresent();
        assertThat(executions.findById(childId)).isPresent();
    }

    @Test
    void purgeExpired_recentCompletedExecution_notPurged() {
        PipelineExecution root = stateManager.createExecution("fake-simple", "1.0.0", "{}");
        UUID rootId = root.getId();
        stateManager.transition(rootId, ExecutionStatus.COMPLETED, null);
        backdate(rootId, 5);

        retentionService.purgeExpired();

        assertThat(executions.findById(rootId)).isPresent();
    }

    @Test
    void purgeExpired_escalatedRoot_neverACandidate() {
        PipelineExecution root = stateManager.createExecution("fake-simple", "1.0.0", "{}");
        UUID rootId = root.getId();
        stateManager.transition(rootId, ExecutionStatus.FAILED_ESCALATED, null);
        backdate(rootId, 40);

        retentionService.purgeExpired();

        assertThat(executions.findById(rootId)).isPresent();
    }

    @Test
    void purgeExpired_nonPositiveTtl_refusesToDeleteAnything() {
        PipelineExecution root = stateManager.createExecution("fake-simple", "1.0.0", "{}");
        UUID rootId = root.getId();
        stateManager.transition(rootId, ExecutionStatus.COMPLETED, null);
        backdate(rootId, 40);
        RetentionService zeroTtlService = new RetentionService(retentionRepository, retentionPurger,
                new RetentionProperties(true, 0, 10, null));

        zeroTtlService.purgeExpired();

        assertThat(executions.findById(rootId)).isPresent();
    }

    // transition(..., COMPLETED/FAILED_ESCALATED, ...) stamps completed_at = now, so
    // backdating must run after the transition or it would be overwritten.
    private void backdate(UUID id, int daysAgo) {
        Timestamp past = Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo)));
        jdbc.update("UPDATE pipeline_execution SET completed_at = ?, created_at = ? WHERE id = ?",
                past, past, id);
    }
}
