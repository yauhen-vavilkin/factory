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
import org.folio.factory.sandbox.tools.Shell;
import tools.jackson.databind.json.JsonMapper;

/** Flow-local workers keep Pi execution separate from the legacy harness. */
public final class PiWorker implements AgentWorker {
  private final String id;
  private final SandboxService sandboxes;
  private final PiCodingRunner runner;
  private final String modelToken;
  private final String gatewayUrl;
  private final JsonMapper json = JsonMapper.builder().build();

  public PiWorker(String id, SandboxService sandboxes, PiCodingRunner runner,
      String modelToken, String gatewayUrl) {
    this.id = id; this.sandboxes = sandboxes; this.runner = runner;
    this.modelToken = modelToken == null ? "" : modelToken;
    this.gatewayUrl = gatewayUrl == null ? "http://factory-gateway:8080/v1" : gatewayUrl;
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
      if (patch == null || patch.isBlank()) return AgentResult.of("verification.json",
          "{\"status\":\"FAIL\",\"reason\":\"EMPTY_CANDIDATE\"}");
      return verify(context, patch);
    }
    String verification = context.requireInput("verification.json").content();
    var verificationNode = json.readTree(verification);
    String outcome = verificationNode.path("status").asString().equals("PASS")
        ? "SUCCESS" : "FAILED";
    var result = json.createObjectNode();
    result.put("outcome", outcome);
    if (!"SUCCESS".equals(outcome)) {
      var failure = result.putObject("failure");
      failure.put("reason", verificationNode.path("reason").asString("VERIFICATION_FAILED"));
      failure.put("stage", verificationNode.path("stage").asString("verify"));
    }
    var usage = result.putObject("usage");
    if (context.inputs().containsKey("usage.json")) {
      usage.setAll((tools.jackson.databind.node.ObjectNode) json.readTree(
          context.requireInput("usage.json").content()));
    }
    result.putObject("cleanup").put("status", "COMPLETE");
    var session = result.putObject("session");
    session.put("ref", context.inputs().containsKey("pi-session.jsonl")
        ? "pi-session.jsonl" : "unknown");
    result.putObject("references").put("candidate", "candidate.patch")
        .put("verification", "verification.json");
    return new AgentResult(Map.of("result.json", json.writeValueAsString(result),
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
          Duration.ofMinutes(30), 16L * 1024 * 1024, Map.of(
              "PI_OFFLINE", "1",
              "FACTORY_MODEL_TOKEN", modelToken,
              "FACTORY_PI_GATEWAY_URL", gatewayUrl));
      String base = Shell.quote(p.path("baseRevision").asText());
      CommandResult diff = sandboxes.exec(handle, "cd repo && (git diff --binary " + base
          + " HEAD; git ls-files --others --exclude-standard | while IFS= read -r f; do "
          + "git diff --binary --no-index /dev/null \"$f\" || test $? -eq 1; done)", 120);
      CommandResult head = sandboxes.exec(handle, "cd repo && git rev-parse HEAD", 30);
      if (!attempt.settled()) throw new AgentExecutionException("Pi ended without agent_settled");
      String candidate = head.stdout().trim();
      String patchHash = org.folio.factory.core.service.ArtifactStore.sha256(diff.stdout());
      return new AgentResult(Map.of("candidate.patch", diff.stdout(),
          "candidate.json", "{\"base\":\"" + p.path("baseRevision").asText()
              + "\",\"candidate\":\"" + candidate + "\",\"patchSha256\":\""
              + patchHash + "\"}", "pi-session.jsonl", attempt.rawExchange(),
          "usage.json", "{\"provider_calls\":1,\"settled\":true}"),
          Map.of("provider_calls", 1, "settled", true, "candidate", candidate));
    } finally { sandboxes.teardown(handle); }
  }

  private AgentResult verify(AgentContext context, String patch) {
    var p = context.triggerPayload();
    SandboxHandle fresh = sandboxes.create(new SandboxSpec(p.path("taskId").asText() + "-verify",
        p.path("repoUrl").asText(), p.path("baseRevision").asText(),
        p.path("branch").asText() + "-verify", context.executionId() + "-verify"));
    try {
      CommandResult applied = sandboxes.exec(fresh, "cd repo && printf '%s' " + Shell.quote(patch)
          + " | git apply --binary -", 120);
      if (!applied.ok()) return AgentResult.of("verification.json",
          "{\"status\":\"FAIL\",\"reason\":\"PATCH_REJECTED\",\"stderr\":"
              + json.writeValueAsString(applied.stderr()) + "}");
      CommandResult tests = sandboxes.exec(fresh, "cd repo && mvn -B -ntp verify", 900);
      String status = tests.ok() ? "PASS" : "FAIL";
      return AgentResult.of("verification.json", "{\"status\":\"" + status
          + "\",\"independent\":true,\"freshCheckout\":true,\"mavenVerifyExit\":"
          + tests.exitCode() + ",\"candidateBytes\":" + patch.length() + "}");
    } finally { sandboxes.teardown(fresh); }
  }
}
