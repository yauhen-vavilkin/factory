package org.folio.factory.app.web;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.model.StepDescriptor;

import java.util.List;
import java.util.UUID;

/** What one run page reads; {@code chain} is the flow's step list, empty when the flow is no longer registered. */
record ExecutionDetail(UUID id, PipelineExecution execution, List<Artifact> artifacts, List<AuditEvent> events,
                       List<StepDescriptor> chain) {
}
