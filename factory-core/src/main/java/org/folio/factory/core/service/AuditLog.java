package org.folio.factory.core.service;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditLog {

    public static final String SYSTEM_ACTOR = "system";

    private final AuditEventRepository repository;
    private final JsonMapper jsonMapper;

    public AuditLog(AuditEventRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    public void record(UUID executionId, AuditEventType type, String stepId, String actor, Map<String, ?> detail) {
        String detailJson = detail == null ? null : jsonMapper.writeValueAsString(detail);
        repository.save(new AuditEvent(executionId, type, stepId, actor, detailJson));
    }

    public void record(UUID executionId, AuditEventType type, String stepId, Map<String, ?> detail) {
        record(executionId, type, stepId, SYSTEM_ACTOR, detail);
    }

    public List<AuditEvent> forExecution(UUID executionId) {
        return repository.findByExecutionIdOrderByIdAsc(executionId);
    }
}
