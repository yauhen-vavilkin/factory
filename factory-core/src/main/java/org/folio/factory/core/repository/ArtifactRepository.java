package org.folio.factory.core.repository;

import org.folio.factory.core.domain.Artifact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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

    Optional<Artifact> findById(UUID id);

    // Slice, not Page: artifacts are insert-only versioned rows, so the table is
    // unbounded and a per-request count(*) would degrade over time.
    Slice<Artifact> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Slice<Artifact> findByNameOrderByCreatedAtDescIdDesc(String name, Pageable pageable);
}
