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
import java.util.UUID;

/**
 * One discovered Jira issue, keyed by the immutable issue id inside the
 * normalized Jira instance. The display key is mutable data; the snapshot is the
 * latest bounded intake view, kept separate from the approved snapshots stored
 * on attempts.
 */
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
    private String displayKey;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "project_key", nullable = false, length = 64)
    private String projectKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issue_snapshot", nullable = false)
    private String issueSnapshot;

    @Column(name = "source_revision", nullable = false, length = 64)
    private String sourceRevision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Eligibility eligibility;

    @Column(name = "routing_reason", nullable = false, length = 500)
    private String routingReason;

    @Column(name = "in_scope", nullable = false)
    private boolean inScope = true;

    @Column(name = "jira_created_at")
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

    public UUID getId() {
        return id;
    }

    public String getJiraInstance() {
        return jiraInstance;
    }

    public String getIssueId() {
        return issueId;
    }

    public String getDisplayKey() {
        return displayKey;
    }

    public void setDisplayKey(String displayKey) {
        this.displayKey = displayKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getIssueSnapshot() {
        return issueSnapshot;
    }

    public void setIssueSnapshot(String issueSnapshot) {
        this.issueSnapshot = issueSnapshot;
    }

    public String getSourceRevision() {
        return sourceRevision;
    }

    public void setSourceRevision(String sourceRevision) {
        this.sourceRevision = sourceRevision;
    }

    public Eligibility getEligibility() {
        return eligibility;
    }

    public void setEligibility(Eligibility eligibility) {
        this.eligibility = eligibility;
    }

    public String getRoutingReason() {
        return routingReason;
    }

    public void setRoutingReason(String routingReason) {
        this.routingReason = routingReason;
    }

    public boolean isInScope() {
        return inScope;
    }

    public void setInScope(boolean inScope) {
        this.inScope = inScope;
    }

    public Instant getJiraCreatedAt() {
        return jiraCreatedAt;
    }

    public void setJiraCreatedAt(Instant jiraCreatedAt) {
        this.jiraCreatedAt = jiraCreatedAt;
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

    public enum Eligibility {
        ELIGIBLE, INELIGIBLE
    }
}
