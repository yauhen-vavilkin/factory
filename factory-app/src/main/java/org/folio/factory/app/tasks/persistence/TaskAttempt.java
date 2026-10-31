package org.folio.factory.app.tasks.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One admission of a task: the reserved attempt identity, its approved
 * input/scope/destination view and the engine binding plus lifecycle checkpoints.
 * Each admission is a new row; history is never rewritten.
 */
@Getter
@Entity
@Table(name = "task_attempt")
public class TaskAttempt {

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
    @Setter
    private State state;

    @Column(name = "input_fingerprint", length = 64)
    @Setter
    private String inputFingerprint;

    @Column(name = "destination_fingerprint", length = 64)
    @Setter
    private String destinationFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_snapshot")
    @Setter
    private String approvedSnapshot;

    /** JSON snapshot of the chosen run settings; see TaskRunSettings. Null: the defaults. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "run_settings")
    @Setter
    private String runSettings;

    @Column(name = "execution_id")
    @Setter
    private UUID executionId;

    @Column(name = "started_at")
    @Setter
    private Instant startedAt;

    @Column(name = "deadline_at")
    @Setter
    private Instant deadlineAt;

    @Column(name = "paid_start_at")
    @Setter
    private Instant paidStartAt;

    @Column(name = "result_reason", length = 500)
    @Setter
    private String resultReason;

    @Column(name = "publication_identity", length = 500)
    @Setter
    private String publicationIdentity;

    /** JSON array of the reasons the pull request was opened as a draft; null when it is not a draft. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_reasons")
    @Setter
    private String draftReasons;

    @Enumerated(EnumType.STRING)
    @Column(name = "admitted_via", nullable = false, length = 16)
    @Setter
    private AdmittedVia admittedVia = AdmittedVia.MANUAL;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaskAttempt() {
    }

    public TaskAttempt(UUID taskId, int ordinal, Kind kind, String requestId, State state) {
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

    public enum Kind {
        INITIAL, RETRY
    }

    /** How the run was started: selected in My tasks or added by its Jira key. */
    public enum AdmittedVia {
        MANUAL, BY_KEY
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
         * through uq_task_attempt_live_per_task. Keep the literals in sync with
         * that partial unique index in V5__pilot_tasks.sql.
         */
        public static final Set<State> LIVE_STATES =
                Collections.unmodifiableSet(EnumSet.of(RESERVED, QUEUED, RUNNING, NEEDS_INSPECTION));

        public boolean isLive() {
            return LIVE_STATES.contains(this);
        }

        /** Live and not held for inspection: queued or running work. */
        public boolean isInFlight() {
            return isLive() && this != NEEDS_INSPECTION;
        }
    }
}
