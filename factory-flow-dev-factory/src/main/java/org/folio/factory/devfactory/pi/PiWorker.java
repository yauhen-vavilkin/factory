package org.folio.factory.devfactory.pi;

import java.util.LinkedHashMap;
import java.util.Map;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import tools.jackson.databind.json.JsonMapper;

/** Flow-local workers keep Pi execution separate from the legacy harness. */
public final class PiWorker implements AgentWorker {
  private final String id;
  private final JsonMapper json = JsonMapper.builder().build();

  public PiWorker(String id) { this.id = id; }
  @Override public String id() { return id; }

  @Override public AgentResult execute(AgentContext context) throws AgentExecutionException {
    if ("pi-prepare-worker".equals(id)) {
      Map<String, Object> contract = new LinkedHashMap<>();
      contract.put("schema", "ExecutionContract/v1");
      contract.put("profile", "java21-pi-unit");
      contract.put("sourceRevision", context.triggerPayload().path("baseRevision").asText());
      contract.put("mode", "PRIVATE_FRESH_RESOLUTION");
      return AgentResult.of("contract.json", json.writeValueAsString(contract));
    }
    if ("pi-coding-worker".equals(id)) {
      return new AgentResult(Map.of("candidate.patch", "", "pi-session.jsonl", "", "usage.json", "{}"),
          Map.of("workflowOperational", false, "reason", "Pi runner requires prepared Docker invocation"));
    }
    if ("pi-verify-worker".equals(id)) return AgentResult.of("verification.json", "{\"status\":\"NOT_RUN\"}");
    return new AgentResult(Map.of("result.json", "{\"outcome\":\"INCOMPLETE\"}", "manifest.json", "{}"), Map.of());
  }
}
