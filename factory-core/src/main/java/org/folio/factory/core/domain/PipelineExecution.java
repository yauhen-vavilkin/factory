package org.folio.factory.core.domain;

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
import java.util.UUID;

@Getter
@Entity
@Table(name = "pipeline_execution")
public class PipelineExecution {

    @Id
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private String flowId;

    @Column(name = "flow_version", nullable = false)
    private String flowVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionStatus status;

    @Column(name = "current_step_index", nullable = false)
    @Setter
    private int currentStepIndex;

    @Column(name = "parent_execution_id")
    @Setter
    private UUID parentExecutionId;

    @Column(name = "parent_step_index")
    @Setter
    private Integer parentStepIndex;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_payload")
    private String triggerPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_counts", nullable = false)
    @Setter
    private String retryCounts = "{}";

    @Column(name = "dedup_key")
    @Setter
    private String dedupKey;

    @Column(name = "error_message", columnDefinition = "text")
    @Setter
    private String errorMessage;

    @Column(name = "next_run_at", nullable = false)
    @Setter
    private Instant nextRunAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PipelineExecution() {
    }

    public PipelineExecution(String flowId, String flowVersion, String triggerPayload) {
        this.id = UUID.randomUUID();
        this.flowId = flowId;
        this.flowVersion = flowVersion;
        this.triggerPayload = triggerPayload;
        this.status = ExecutionStatus.PENDING;
        this.currentStepIndex = 0;
        this.nextRunAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
        if (status.isTerminal()) {
            this.completedAt = Instant.now();
        }
    }

    public void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

}
