package org.folio.factory.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hitl_review")
public class HitlReview {

    @Id
    private UUID id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "gate_id", nullable = false, updatable = false)
    private String gateId;

    @Column(name = "step_index", nullable = false, updatable = false)
    private int stepIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HitlReviewStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review_package", nullable = false, updatable = false)
    private String reviewPackage;

    @Column(length = 20)
    private String decision;

    private String reviewer;

    @Column(columnDefinition = "text")
    private String comments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "amended_artifacts")
    private String amendedArtifacts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    // Optimistic lock: two concurrent decisions on the same review cannot both
    // commit; the loser gets an optimistic-locking failure instead of silently
    // double-driving the execution.
    @Version
    @Column(nullable = false)
    private long version;

    protected HitlReview() {
    }

    public HitlReview(UUID executionId, String gateId, int stepIndex, String reviewPackage) {
        this.id = UUID.randomUUID();
        this.executionId = executionId;
        this.gateId = gateId;
        this.stepIndex = stepIndex;
        this.status = HitlReviewStatus.PENDING;
        this.reviewPackage = reviewPackage;
        this.createdAt = Instant.now();
    }

    public void decide(HitlReviewStatus status, String decision, String reviewer,
                       String comments, String amendedArtifacts) {
        this.status = status;
        this.decision = decision;
        this.reviewer = reviewer;
        this.comments = comments;
        this.amendedArtifacts = amendedArtifacts;
        this.decidedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getGateId() {
        return gateId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public HitlReviewStatus getStatus() {
        return status;
    }

    public String getReviewPackage() {
        return reviewPackage;
    }

    public String getDecision() {
        return decision;
    }

    public String getReviewer() {
        return reviewer;
    }

    public String getComments() {
        return comments;
    }

    public String getAmendedArtifacts() {
        return amendedArtifacts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
