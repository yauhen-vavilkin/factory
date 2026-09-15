package org.folio.factory.app.web;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.delivery.DeliveryWorker;
import org.folio.factory.devfactory.delivery.TrustedDeliveryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Operator boundary for trusted delivery of an already completed Developer Flow
 * execution. Delivery reads only the persisted frozen candidate and its
 * verification evidence (no sandbox, no rebuild) and records every attempt as
 * a new {@code delivery.json} version. 200 means DELIVERED; 409 carries the
 * REFUSED or FAILED record.
 */
@RestController
@RequestMapping("/api/dev/executions/{executionId}/delivery")
public class DevDeliveryController {
  private final PipelineExecutionRepository executions;
  private final ArtifactStore artifacts;
  private final TrustedDeliveryService delivery;
  private final JsonMapper json;

  public DevDeliveryController(PipelineExecutionRepository executions, ArtifactStore artifacts,
                               TrustedDeliveryService delivery, JsonMapper json) {
    this.executions = executions;
    this.artifacts = artifacts;
    this.delivery = delivery;
    this.json = json;
  }

  @PostMapping
  public ResponseEntity<JsonNode> deliver(@PathVariable("executionId") UUID executionId) {
    PipelineExecution execution = executions.findById(executionId)
        .orElseThrow(() -> new NoSuchElementException("No execution " + executionId));
    String task = latest(executionId, "task.json");
    ObjectNode record = delivery.deliver(new TrustedDeliveryService.Evidence(
        TrustedDeliveryService.Source.COMPLETED_EXECUTION, executionId.toString(),
        execution.getStatus() == null ? null : execution.getStatus().name(),
        json.readTree(task != null ? task : execution.getTriggerPayload()),
        readJson(executionId, "candidate.json"), latest(executionId, "candidate.patch"),
        readJson(executionId, "verification.json"), readJson(executionId, "result.json")));
    artifacts.put(executionId, DeliveryWorker.ARTIFACT, json.writeValueAsString(record), "application/json",
        "factory-trusted-delivery");
    HttpStatus status = "DELIVERED".equals(record.path("status").asString("")) ? HttpStatus.OK : HttpStatus.CONFLICT;
    return ResponseEntity.status(status).body(record);
  }

  private JsonNode readJson(UUID executionId, String name) {
    String content = latest(executionId, name);
    return content == null ? null : json.readTree(content);
  }

  private String latest(UUID executionId, String name) {
    return artifacts.getLatest(executionId, name).map(Artifact::getContent).orElse(null);
  }
}
