package org.folio.factory.core.service;

import lombok.RequiredArgsConstructor;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.limits.DedupKeyDeriver;
import org.folio.factory.core.limits.LimitsProperties;
import org.folio.factory.core.repository.ArtifactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Versioned, immutable artifact registry. Writing an artifact that already exists
 * creates the next version; existing rows are never modified.
 */
@Service
@RequiredArgsConstructor
public class ArtifactStore {

    private final ArtifactRepository repository;
    private final AuditLog auditLog;
    private final LimitsProperties limits;

    @Transactional
    public Artifact put(UUID executionId, String name, String content, String contentType, String createdBy) {
        int size = content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
        if (size > limits.maxArtifactBytes()) {
            throw new IllegalArgumentException("Artifact '" + name + "' is " + size
                    + " bytes, exceeding the " + limits.maxArtifactBytes() + "-byte limit");
        }
        int nextVersion = repository.findTopByExecutionIdAndNameOrderByVersionDesc(executionId, name)
                .map(a -> a.getVersion() + 1)
                .orElse(1);
        Artifact artifact = new Artifact(executionId, name, nextVersion, contentType, content,
                sha256(content), createdBy);
        Artifact saved = repository.save(artifact);
        auditLog.record(executionId, AuditEventType.ARTIFACT_WRITTEN, null, createdBy,
                Map.of("name", name, "version", nextVersion, "sha256", saved.getSha256()));
        return saved;
    }

    @Transactional
    public Artifact putMarkdown(UUID executionId, String name, String content, String createdBy) {
        return put(executionId, name, content, "text/markdown", createdBy);
    }

    public Optional<Artifact> getLatest(UUID executionId, String name) {
        return repository.findTopByExecutionIdAndNameOrderByVersionDesc(executionId, name);
    }

    public List<Artifact> allForExecution(UUID executionId) {
        return repository.findByExecutionIdOrderByNameAscVersionAsc(executionId);
    }

    public static String sha256(String content) {
        return DedupKeyDeriver.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }
}
