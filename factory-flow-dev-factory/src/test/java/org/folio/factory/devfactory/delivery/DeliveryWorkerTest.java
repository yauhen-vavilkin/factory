package org.folio.factory.devfactory.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class DeliveryWorkerTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void localOnlyAndUnverifiedCandidatesNeverReachGitOrGitHub() {
    DeliveryGitHub github = mock(DeliveryGitHub.class);
    DeliveryWorker worker = new DeliveryWorker(
        new TrustedDeliveryService(github, "delivery-token", "", "Factory", "factory@example.org"));

    JsonNode local = run(worker, "LOCAL_ONLY", "PASS");
    JsonNode failed = run(worker, "DELIVER_PR", "FAIL");
    JsonNode blocked = run(worker, "DELIVER_PR", "BLOCKED_ENVIRONMENT");

    assertThat(local.path("status").asString()).isEqualTo("NOT_REQUESTED");
    assertThat(failed.path("status").asString()).isEqualTo("NOT_ATTEMPTED");
    assertThat(blocked.path("status").asString()).isEqualTo("NOT_ATTEMPTED");
    verifyNoInteractions(github);
  }

  private JsonNode run(DeliveryWorker worker, String mode, String verificationStatus) {
    ObjectNode payload = json.createObjectNode().put("taskId", "X-1");
    payload.putObject("resolvedIntent").putObject("task").put("deliveryMode", mode);
    AgentContext context = new AgentContext(UUID.randomUUID(), "deliver", Map.of(
        "candidate.patch", new ArtifactContent("candidate.patch", 1, "text/plain", "patch"),
        "candidate.json", new ArtifactContent("candidate.json", 1, "application/json", "{\"status\":\"READY\"}"),
        "verification.json", new ArtifactContent("verification.json", 1, "application/json",
            "{\"status\":\"" + verificationStatus + "\"}")),
        payload, Map.of(), List.of());
    return json.readTree(worker.execute(context).outputs().get(DeliveryWorker.ARTIFACT));
  }
}
