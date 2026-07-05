package org.folio.factory.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, versioned flow document. Instances are insert-only: amendments create
 * a new row with an incremented version, never an update.
 */
@Entity
@Table(name = "artifact")
public class Artifact {

    @Id
    private UUID id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Artifact() {
    }

    public Artifact(UUID executionId, String name, int version, String contentType,
                    String content, String sha256, String createdBy) {
        this.id = UUID.randomUUID();
        this.executionId = executionId;
        this.name = name;
        this.version = version;
        this.contentType = contentType;
        this.content = content;
        this.sha256 = sha256;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public String getContentType() {
        return contentType;
    }

    public String getContent() {
        return content;
    }

    public String getSha256() {
        return sha256;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
