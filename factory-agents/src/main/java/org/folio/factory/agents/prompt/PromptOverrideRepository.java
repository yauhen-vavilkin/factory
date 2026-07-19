package org.folio.factory.agents.prompt;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Insert-only repository: no update or delete operations. Rows are versioned,
 * mirroring {@code AuditEventRepository}.
 */
public interface PromptOverrideRepository extends Repository<PromptOverride, Long> {

    PromptOverride save(PromptOverride override);

    Optional<PromptOverride> findTopByWorkerIdAndPromptNameOrderByVersionDesc(String workerId, String promptName);

    List<PromptOverride> findByWorkerIdAndPromptNameOrderByVersionDesc(String workerId, String promptName);

    Optional<PromptOverride> findByWorkerIdAndPromptNameAndVersion(String workerId, String promptName, int version);
}
