package org.folio.factory.app.pilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The single local pilot installation record (row id is always 1, created by
 * migration V12): the last scan's health and the active attempt slot.
 */
@Getter
@Entity
@Table(name = "pilot_installation")
public class PilotInstallation {

    @Id
    private int id = 1;

    @Column(name = "sync_complete", nullable = false)
    @Setter
    private boolean syncComplete;

    @Column(name = "last_successful_sync_at")
    @Setter
    private Instant lastSuccessfulSyncAt;

    @Column(name = "sync_problem", columnDefinition = "text")
    @Setter
    private String syncProblem;

    @Column(name = "active_attempt_id")
    @Setter
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

}
