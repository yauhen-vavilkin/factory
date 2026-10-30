package org.folio.factory.app.pilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * The single local pilot installation record (row id is always 1, created by
 * migration V12): the last scan's health and the active attempt slot.
 */
@Entity
@Table(name = "pilot_installation")
public class PilotInstallation {

    @Id
    private int id = 1;

    @Column(name = "sync_complete", nullable = false)
    private boolean syncComplete;

    @Column(name = "last_successful_sync_at")
    private Instant lastSuccessfulSyncAt;

    @Column(name = "sync_problem", columnDefinition = "text")
    private String syncProblem;

    @Column(name = "active_attempt_id")
    private UUID activeAttemptId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PilotInstallation() {
        this.id = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static PilotInstallation initial() {
        return new PilotInstallation();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public int getId() {
        return id;
    }

    public boolean isSyncComplete() {
        return syncComplete;
    }

    public void setSyncComplete(boolean syncComplete) {
        this.syncComplete = syncComplete;
    }

    public Instant getLastSuccessfulSyncAt() {
        return lastSuccessfulSyncAt;
    }

    public void setLastSuccessfulSyncAt(Instant lastSuccessfulSyncAt) {
        this.lastSuccessfulSyncAt = lastSuccessfulSyncAt;
    }

    public String getSyncProblem() {
        return syncProblem;
    }

    public void setSyncProblem(String syncProblem) {
        this.syncProblem = syncProblem;
    }

    public UUID getActiveAttemptId() {
        return activeAttemptId;
    }

    public void setActiveAttemptId(UUID activeAttemptId) {
        this.activeAttemptId = activeAttemptId;
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
}
