package org.folio.factory.app.pilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One admission of a pilot task: the reserved attempt identity, its approved
 * input/scope/destination view and the engine binding plus lifecycle checkpoints.
 * Each admission is a new row; history is never rewritten.
 */
@Entity
@Table(name = "pilot_attempt")
public class PilotAttempt {

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(nullable = false, updatable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Kind kind;

    @Column(name = "request_id", nullable = false, updatable = false, length = 100)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private State state;

    @Column(name = "input_fingerprint", length = 64)
    private String inputFingerprint;

    @Column(name = "scope_fingerprint", length = 64)
    private String scopeFingerprint;

    @Column(name = "destination_fingerprint", length = 64)
    private String destinationFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_snapshot")
    private String approvedSnapshot;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "paid_start_at")
    private Instant paidStartAt;

    @Column(name = "result_reason", length = 500)
    private String resultReason;

    @Column(name = "publication_identity", length = 500)
    private String publicationIdentity;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PilotAttempt() {
    }

    public PilotAttempt(UUID taskId, int ordinal, Kind kind, String requestId, State state) {
        this.id = UUID.randomUUID();
        this.taskId = taskId;
        this.ordinal = ordinal;
        this.kind = kind;
        this.requestId = requestId;
        this.state = state;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public Kind getKind() {
        return kind;
    }

    public String getRequestId() {
        return requestId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String getInputFingerprint() {
        return inputFingerprint;
    }

    public void setInputFingerprint(String inputFingerprint) {
        this.inputFingerprint = inputFingerprint;
    }

    public String getScopeFingerprint() {
        return scopeFingerprint;
    }

    public void setScopeFingerprint(String scopeFingerprint) {
        this.scopeFingerprint = scopeFingerprint;
    }

    public String getDestinationFingerprint() {
        return destinationFingerprint;
    }

    public void setDestinationFingerprint(String destinationFingerprint) {
        this.destinationFingerprint = destinationFingerprint;
    }

    public String getApprovedSnapshot() {
        return approvedSnapshot;
    }

    public void setApprovedSnapshot(String approvedSnapshot) {
        this.approvedSnapshot = approvedSnapshot;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public void setExecutionId(UUID executionId) {
        this.executionId = executionId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(Instant deadlineAt) {
        this.deadlineAt = deadlineAt;
    }

    public Instant getPaidStartAt() {
        return paidStartAt;
    }

    public void setPaidStartAt(Instant paidStartAt) {
        this.paidStartAt = paidStartAt;
    }

    public String getResultReason() {
        return resultReason;
    }

    public void setResultReason(String resultReason) {
        this.resultReason = resultReason;
    }

    public String getPublicationIdentity() {
        return publicationIdentity;
    }

    public void setPublicationIdentity(String publicationIdentity) {
        this.publicationIdentity = publicationIdentity;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public enum Kind {
        INITIAL, RETRY
    }

    public enum State {
        RESERVED,
        QUEUED,
        RUNNING,
        NEEDS_INSPECTION,
        WITHDRAWN,
        PR_READY,
        STOPPED;

        /**
         * Live admissions hold the task's single slot; the database enforces this
         * through uq_pilot_attempt_live_per_task. Keep the literals in sync with
         * that partial unique index in V5__pilot_tasks.sql.
         */
        public static final Set<State> LIVE_STATES =
                Collections.unmodifiableSet(EnumSet.of(RESERVED, QUEUED, RUNNING, NEEDS_INSPECTION));

        public boolean isLive() {
            return LIVE_STATES.contains(this);
        }
    }
}
