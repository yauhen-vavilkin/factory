package org.folio.factory.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit record. The database enforces immutability with a trigger
 * that rejects UPDATE and DELETE; the repository exposes no mutation methods.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", updatable = false)
    private UUID executionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private AuditEventType eventType;

    @Column(name = "step_id", updatable = false)
    private String stepId;

    @Column(nullable = false, updatable = false)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(UUID executionId, AuditEventType eventType, String stepId, String actor, String detail) {
        this.executionId = executionId;
        this.eventType = eventType;
        this.stepId = stepId;
        this.actor = actor;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public String getStepId() {
        return stepId;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
