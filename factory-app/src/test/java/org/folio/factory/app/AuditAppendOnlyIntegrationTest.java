package org.folio.factory.app;

import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit table on the real Flyway schema refuses to change a recorded event. Each refused statement
 * runs in its own auto-committed transaction, so nothing is left open; the shared cleanup empties the
 * table with TRUNCATE, which the row trigger does not block.
 */
@FactoryIntegrationTest
class AuditAppendOnlyIntegrationTest {

    @Autowired
    AuditLog auditLog;

    @Autowired
    JdbcClient jdbc;

    private UUID executionId;

    @BeforeEach
    void recordOneEvent() {
        executionId = UUID.randomUUID();
        auditLog.record(executionId, AuditEventType.STEP_STARTED, "step-1", Map.of("note", "original"));
    }

    @Test
    void theDatabaseRefusesToUpdateARecordedAuditEvent() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_event SET actor = 'someone else' WHERE execution_id = ?")
                .param(executionId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_event is append-only");

        assertThat(auditLog.forExecution(executionId)).singleElement()
                .satisfies(event -> assertThat(event.getActor()).isEqualTo(AuditLog.SYSTEM_ACTOR));
    }

    @Test
    void theDatabaseRefusesToDeleteARecordedAuditEvent() {
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event WHERE execution_id = ?")
                .param(executionId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_event is append-only");

        assertThat(auditLog.forExecution(executionId)).hasSize(1);
    }
}
