package org.folio.factory.core.repository;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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

    // Slice, not Page: the table is append-only and unbounded, so the count(*) a
    // Page would issue per request becomes an ever-growing full-table scan.
    Slice<AuditEvent> findAllByOrderByIdDesc(Pageable pageable);

    Slice<AuditEvent> findByEventTypeOrderByIdDesc(AuditEventType eventType, Pageable pageable);
}
