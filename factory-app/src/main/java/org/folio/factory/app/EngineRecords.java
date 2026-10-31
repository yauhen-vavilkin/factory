package org.folio.factory.app;

import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.springframework.stereotype.Component;

/** What the engine keeps about a run: the execution rows, their artifacts and the flow descriptors. */
@Component
public record EngineRecords(PipelineExecutionRepository executions, ArtifactStore artifacts, FlowRegistry flows) {
}
