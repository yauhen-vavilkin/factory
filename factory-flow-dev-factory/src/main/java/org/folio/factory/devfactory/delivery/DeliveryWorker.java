package org.folio.factory.devfactory.delivery;

import java.util.Map;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Developer Flow deliver step. It runs in the Factory process after the
 * final verification. {@code LOCAL_ONLY} tasks and candidates that did not pass
 * verification are recorded without any Git or GitHub call; a
 * {@code DELIVER_PR} task with a passed candidate goes through
 * {@link TrustedDeliveryService}.
 */
public final class DeliveryWorker implements AgentWorker {
  public static final String ID = "pi-deliver-worker";
  public static final String ARTIFACT = "delivery.json";
  private final TrustedDeliveryService delivery;
  private final JsonMapper json = JsonMapper.builder().build();

  public DeliveryWorker(TrustedDeliveryService delivery) {
    this.delivery = delivery;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public AgentResult execute(AgentContext context) {
    JsonNode task = context.inputs().containsKey("task.json")
        ? json.readTree(context.requireInput("task.json").content()) : context.triggerPayload();
    String mode = TrustedDeliveryService.deliveryMode(task);
    JsonNode verification = json.readTree(context.requireInput("verification.json").content());
    ObjectNode record;
    if (!TrustedDeliveryService.DELIVER_PR.equals(mode)) {
      record = skipped(mode, "NOT_REQUESTED", "delivery mode " + mode + " keeps the candidate local");
    } else if (!"PASS".equals(verification.path("status").asString(""))) {
      record = skipped(mode, "NOT_ATTEMPTED", "verification status is "
          + verification.path("status").asString("missing") + "; only a verified candidate is delivered");
    } else {
      record = delivery.deliver(new TrustedDeliveryService.Evidence(TrustedDeliveryService.Source.FLOW_STEP,
          context.executionId().toString(), null, task,
          json.readTree(context.requireInput("candidate.json").content()),
          context.requireInput("candidate.patch").content(), verification, null));
    }
    return new AgentResult(Map.of(ARTIFACT, json.writeValueAsString(record)),
        Map.of("delivery_status", record.path("status").asString("")));
  }

  private ObjectNode skipped(String mode, String status, String detail) {
    ObjectNode record = json.createObjectNode();
    record.put("schema", TrustedDeliveryService.SCHEMA);
    record.put("mode", mode);
    record.put("status", status);
    record.put("detail", detail);
    return record;
  }
}
