package org.folio.factory.core.service;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class CoreDomainIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    AuditLog auditLog;

    @Test
    void artifactWritesAreVersionedAndImmutable() {
        PipelineExecution execution = stateManager.createExecution("test-flow", "1.0.0", null);

        Artifact v1 = artifactStore.putMarkdown(execution.getId(), "test_plan.md", "first draft", "test-spec-agent");
        Artifact v2 = artifactStore.putMarkdown(execution.getId(), "test_plan.md", "amended draft", "hitl:qa");

        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getId()).isNotEqualTo(v1.getId());
        assertThat(artifactStore.getLatest(execution.getId(), "test_plan.md"))
                .hasValueSatisfying(a -> assertThat(a.getContent()).isEqualTo("amended draft"));
        assertThat(artifactStore.get(execution.getId(), "test_plan.md", 1))
                .hasValueSatisfying(a -> assertThat(a.getContent()).isEqualTo("first draft"));
        assertThat(v1.getSha256()).hasSize(64).isNotEqualTo(v2.getSha256());
    }

    @Test
    void transitionsAreAuditedWithBeforeAndAfterStatus() {
        PipelineExecution execution = stateManager.createExecution("test-flow", "1.0.0", "{\"issueKey\":\"ERM-1\"}");
        stateManager.transition(execution.getId(), ExecutionStatus.RUNNING, null);
        execution = stateManager.transition(execution.getId(), ExecutionStatus.COMPLETED, null);

        List<AuditEvent> events = auditLog.forExecution(execution.getId());
        assertThat(events).extracting(AuditEvent::getEventType).containsExactly(
                AuditEventType.EXECUTION_STARTED,
                AuditEventType.STATE_TRANSITION,
                AuditEventType.STATE_TRANSITION,
                AuditEventType.EXECUTION_COMPLETED);
        assertThat(events.get(2).getDetail())
                .containsIgnoringWhitespaces("\"from\":\"RUNNING\"")
                .containsIgnoringWhitespaces("\"to\":\"COMPLETED\"");
        assertThat(execution.getCompletedAt()).isNotNull();
    }

    @Test
    void retryCountsAccumulatePerStep() {
        PipelineExecution execution = stateManager.createExecution("test-flow", "1.0.0", null);

        assertThat(stateManager.incrementRetry(execution.getId(), "triage")).isEqualTo(1);
        assertThat(stateManager.incrementRetry(execution.getId(), "triage")).isEqualTo(2);
        assertThat(stateManager.incrementRetry(execution.getId(), "test-spec")).isEqualTo(1);
        assertThat(stateManager.retryCount(execution.getId(), "triage")).isEqualTo(2);
        assertThat(stateManager.retryCount(execution.getId(), "unknown")).isZero();
    }
}
