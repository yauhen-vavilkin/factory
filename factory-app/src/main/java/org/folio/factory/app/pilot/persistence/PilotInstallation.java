package org.folio.factory.app.pilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * The single local pilot installation record (row id is always 1): admission
 * mode, the accepted source authority, discovery sync health and the active
 * attempt slot.
 */
@Entity
@Table(name = "pilot_installation")
public class PilotInstallation {

    @Id
    private int id = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Mode mode = Mode.REVIEW_BEFORE_RUN;

    @Column(name = "accepted_authority_fingerprint", length = 64)
    private String acceptedAuthorityFingerprint;

    @Column(name = "observed_authority_fingerprint", length = 64)
    private String observedAuthorityFingerprint;

    @Column(name = "sync_complete", nullable = false)
    private boolean syncComplete;

    @Column(name = "last_successful_sync_at")
    private Instant lastSuccessfulSyncAt;

    @Column(name = "sync_problem", columnDefinition = "text")
    private String syncProblem;

    @Column(name = "source_name", length = 500)
    private String sourceName;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "sprint_name", length = 500)
    private String sprintName;

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

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getAcceptedAuthorityFingerprint() {
        return acceptedAuthorityFingerprint;
    }

    public void setAcceptedAuthorityFingerprint(String acceptedAuthorityFingerprint) {
        this.acceptedAuthorityFingerprint = acceptedAuthorityFingerprint;
    }

    public String getObservedAuthorityFingerprint() {
        return observedAuthorityFingerprint;
    }

    public void setObservedAuthorityFingerprint(String observedAuthorityFingerprint) {
        this.observedAuthorityFingerprint = observedAuthorityFingerprint;
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

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getSprintName() {
        return sprintName;
    }

    public void setSprintName(String sprintName) {
        this.sprintName = sprintName;
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

    public enum Mode {
        REVIEW_BEFORE_RUN, AUTOMATIC
    }
}
