package org.folio.factory.core.repository;

import org.folio.factory.core.domain.AuditEvent;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only repository: no update or delete operations. The database
 * additionally enforces immutability with a trigger.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    List<AuditEvent> findByExecutionIdOrderByIdAsc(UUID executionId);

    List<AuditEvent> findTop200ByOrderByIdDesc();
}
