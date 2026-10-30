package org.folio.factory.app.pilot.persistence;

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

/**
 * One discovered Jira issue, keyed by the immutable issue id inside the
 * normalized Jira instance. The display key is mutable data; the snapshot is the
 * latest bounded intake view, kept separate from the approved snapshots stored
 * on attempts.
 */
@Getter
@Entity
@Table(name = "pilot_task")
public class PilotTask {

    @Id
    private UUID id;

    @Column(name = "jira_instance", nullable = false, updatable = false)
    private String jiraInstance;

    @Column(name = "issue_id", nullable = false, updatable = false, length = 64)
    private String issueId;

    @Column(name = "display_key", nullable = false, length = 64)
    @Setter
    private String displayKey;

    @Column(nullable = false, length = 500)
    @Setter
    private String title;

    @Column(name = "project_key", nullable = false, length = 64)
    @Setter
    private String projectKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issue_snapshot", nullable = false)
    @Setter
    private String issueSnapshot;

    @Column(name = "source_revision", nullable = false, length = 64)
    @Setter
    private String sourceRevision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter
    private Eligibility eligibility;

    @Column(name = "routing_reason", nullable = false, length = 500)
    @Setter
    private String routingReason;

    @Column(name = "in_scope", nullable = false)
    @Setter
    private boolean inScope = true;

    @Column(name = "jira_created_at")
    @Setter
    private Instant jiraCreatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PilotTask() {
    }

    public PilotTask(String jiraInstance, String issueId, String displayKey, String title,
                     String projectKey, String issueSnapshot, String sourceRevision,
                     Eligibility eligibility, String routingReason) {
        this.id = UUID.randomUUID();
        this.jiraInstance = jiraInstance;
        this.issueId = issueId;
        this.displayKey = displayKey;
        this.title = title;
        this.projectKey = projectKey;
        this.issueSnapshot = issueSnapshot;
        this.sourceRevision = sourceRevision;
        this.eligibility = eligibility;
        this.routingReason = routingReason;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public enum Eligibility {
        ELIGIBLE, INELIGIBLE
    }
}
