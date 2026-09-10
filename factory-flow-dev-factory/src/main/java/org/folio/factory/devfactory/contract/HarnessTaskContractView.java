package org.folio.factory.devfactory.contract;

import org.folio.factory.sandbox.harness.TaskContract;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Lossless compatibility adapter from the frozen contract to the existing harness API. */
public final class HarnessTaskContractView {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HarnessTaskContractView() {
  }

  public static TaskContract from(ExecutionContract contract) {
    if (contract == null || contract.contractHash() == null || contract.contractHash().isBlank()) {
      throw new IllegalArgumentException("a frozen ExecutionContract is required");
    }
    ArrayNode acceptance = JSON.createArrayNode();
    contract.acceptance().forEach(item -> acceptance.add(item.text()));
    ObjectNode constraints = JSON.valueToTree(contract.constraints());
    ArrayNode checks = constraints.putArray(TaskContract.CHECKS_KEY);
    contract.verificationPlan().checks().stream().filter(check -> check.required()).forEach(check -> {
      ObjectNode entry = checks.addObject();
      entry.put("id", check.id());
      // Compatibility only: argv comes from the trusted frozen plan, never task/model text.
      entry.put("command", String.join(" ", check.argv()));
    });
    return new TaskContract(contract.task().goal(), acceptance, constraints, contract.task().notes(),
        contract.task().rawTaskText());
  }
}
