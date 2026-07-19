package org.folio.factory.core.repository;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    Page<AuditEvent> findAllByOrderByIdDesc(Pageable pageable);

    Page<AuditEvent> findByEventTypeOrderByIdDesc(AuditEventType eventType, Pageable pageable);
}
