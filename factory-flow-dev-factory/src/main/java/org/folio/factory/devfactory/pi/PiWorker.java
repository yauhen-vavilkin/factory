package org.folio.factory.devfactory.pi;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import tools.jackson.databind.json.JsonMapper;

/** Flow-local workers keep Pi execution separate from the legacy harness. */
public final class PiWorker implements AgentWorker {
  private final String id;
  private final SandboxService sandboxes;
  private final PiCodingRunner runner;
  private final JsonMapper json = JsonMapper.builder().build();

  public PiWorker(String id, SandboxService sandboxes, PiCodingRunner runner) {
    this.id = id; this.sandboxes = sandboxes; this.runner = runner;
  }
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
    if ("pi-coding-worker".equals(id)) return code(context);
    if ("pi-verify-worker".equals(id)) {
      String patch = context.requireInput("candidate.patch").content();
      return AgentResult.of("verification.json", patch == null || patch.isBlank()
          ? "{\"status\":\"FAIL\",\"reason\":\"EMPTY_CANDIDATE\"}"
          : "{\"status\":\"PASS\",\"candidateBytes\":" + patch.length() + "}");
    }
    String verification = context.requireInput("verification.json").content();
    String outcome = verification.contains("\"status\":\"PASS\"") ? "SUCCESS" : "FAILED";
    return new AgentResult(Map.of("result.json", "{\"outcome\":\"" + outcome + "\"}",
        "manifest.json", "{\"candidate\":\"candidate.patch\",\"verification\":\"verification.json\"}"),
        Map.of("workflowOperational", true, "outcome", outcome));
  }

  private AgentResult code(AgentContext context) {
    var p = context.triggerPayload();
    SandboxHandle handle = sandboxes.create(new SandboxSpec(p.path("taskId").asText(),
        p.path("repoUrl").asText(), p.path("baseRevision").asText(), p.path("branch").asText(),
        context.executionId() + "-attempt-" + context.attempt()));
    try {
      String task = json.writeValueAsString(Map.of("goal", p.path("goal").asText(),
          "acceptance", p.path("acceptance"), "policy", "Only edit the prepared repository."));
      var attempt = runner.run(handle, List.of("/opt/pi/node_modules/.bin/pi", "--mode", "rpc",
          "--provider", "factory-zai", "--model", "glm-5.3-flash", "--no-approve",
          "--no-extensions", "--no-skills", "--no-context-files", "--tools",
          "read,bash,edit,write,grep,find,ls"), "/workspace/repo", task,
          Duration.ofMinutes(30), 16L * 1024 * 1024, Map.of("PI_OFFLINE", "1"));
      CommandResult diff = sandboxes.exec(handle, "cd repo && git diff --binary HEAD", 120);
      if (!attempt.settled()) throw new AgentExecutionException("Pi ended without agent_settled");
      return new AgentResult(Map.of("candidate.patch", diff.stdout(), "pi-session.jsonl", attempt.rawExchange(),
          "usage.json", "{\"provider_calls\":1}"), Map.of("provider_calls", 1, "settled", true));
    } finally { sandboxes.teardown(handle); }
  }
}
