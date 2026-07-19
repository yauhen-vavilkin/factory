package org.folio.factory.agents.prompt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Insert-only versioned override of a worker prompt. The newest row per
 * (workerId, promptName) decides what is active; a {@code useDefault} row reverts
 * to the bundled classpath default. Rows are never updated.
 */
@Entity
@Table(name = "prompt_override")
public class PromptOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "worker_id", nullable = false, updatable = false, length = 100)
    private String workerId;

    @Column(name = "prompt_name", nullable = false, updatable = false, length = 100)
    private String promptName;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(name = "use_default", nullable = false, updatable = false)
    private boolean useDefault;

    @Column(updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PromptOverride() {
    }

    public PromptOverride(String workerId, String promptName, int version,
                          boolean useDefault, String content, String createdBy) {
        this.workerId = workerId;
        this.promptName = promptName;
        this.version = version;
        this.useDefault = useDefault;
        this.content = content;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getPromptName() {
        return promptName;
    }

    public int getVersion() {
        return version;
    }

    public boolean isUseDefault() {
        return useDefault;
    }

    public String getContent() {
        return content;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
