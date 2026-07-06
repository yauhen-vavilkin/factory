package org.folio.factory.core.repository;

import org.folio.factory.core.domain.Artifact;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only repository: artifacts are immutable, so no update or delete
 * operations are exposed.
 */
public interface ArtifactRepository extends Repository<Artifact, UUID> {

    Artifact save(Artifact artifact);

    Optional<Artifact> findTopByExecutionIdAndNameOrderByVersionDesc(UUID executionId, String name);

    Optional<Artifact> findByExecutionIdAndNameAndVersion(UUID executionId, String name, int version);

    List<Artifact> findByExecutionIdOrderByNameAscVersionAsc(UUID executionId);
}
