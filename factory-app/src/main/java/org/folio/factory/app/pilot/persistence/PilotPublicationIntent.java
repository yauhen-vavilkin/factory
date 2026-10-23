package org.folio.factory.app.pilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The exact publication identity persisted before the first remote mutation of
 * a pilot attempt. The first intent for an attempt (and for its execution) stays
 * authoritative; lost push or pull-request responses are reconciled against it
 * and its rows are never rewritten.
 */
@Entity
@Table(name = "pilot_publication_intent")
public class PilotPublicationIntent {

    @Id
    private UUID id;

    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(nullable = false, updatable = false, length = 300)
    private String repository;

    @Column(name = "base_branch", nullable = false, updatable = false, length = 200)
    private String baseBranch;

    @Column(name = "head_branch", nullable = false, updatable = false, length = 200)
    private String headBranch;

    @Column(name = "base_sha", nullable = false, updatable = false, length = 40)
    private String baseSha;

    @Column(name = "tree_sha", nullable = false, updatable = false, length = 40)
    private String treeSha;

    @Column(name = "patch_sha256", nullable = false, updatable = false, length = 64)
    private String patchSha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PilotPublicationIntent() {
    }

    public static PilotPublicationIntent of(UUID attemptId, UUID executionId, String repository,
                                            String baseBranch, String headBranch, String baseSha,
                                            String treeSha, String patchSha256) {
        PilotPublicationIntent intent = new PilotPublicationIntent();
        intent.id = UUID.randomUUID();
        intent.attemptId = attemptId;
        intent.executionId = executionId;
        intent.repository = repository;
        intent.baseBranch = baseBranch;
        intent.headBranch = headBranch;
        intent.baseSha = baseSha;
        intent.treeSha = treeSha;
        intent.patchSha256 = patchSha256;
        intent.createdAt = Instant.now();
        return intent;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public String getRepository() {
        return repository;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public String getHeadBranch() {
        return headBranch;
    }

    public String getBaseSha() {
        return baseSha;
    }

    public String getTreeSha() {
        return treeSha;
    }

    public String getPatchSha256() {
        return patchSha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
