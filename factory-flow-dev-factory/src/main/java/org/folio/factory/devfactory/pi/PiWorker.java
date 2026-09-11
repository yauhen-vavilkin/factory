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
import tools.jackson.databind.JsonNode;

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
      JsonNode resolved = context.triggerPayload().path("resolvedIntent");
      if (!resolved.path("profile").isObject()) {
        throw new AgentExecutionException("resolved trusted profile is required");
      }
      contract.put("profile", resolved.path("profile"));
      contract.put("resolvedIntent", resolved);
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
    JsonNode contract = json.readTree(context.requireInput("contract.json").content());
    JsonNode profile = contract.path("profile");
    String image = profile.path("imageReference").asText(null);
    String platform = profile.path("platform").asText(null);
    String networkPolicy = profile.path("networkPolicy").path("execution").asText(null);
    String provider = profile.path("modelProvider").asText(null);
    String model = profile.path("modelId").asText(null);
    if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
      throw new IllegalStateException("execution contract lacks model provider or model");
    }
    SandboxSpec source = new SandboxSpec(p.path("taskId").asText(),
        p.path("repoUrl").asText(), p.path("baseRevision").asText(), p.path("branch").asText(),
        context.executionId() + "-attempt-" + context.attempt(), image, platform, networkPolicy);
    SandboxHandle handle = sandboxes.create(source);
    SandboxHandle exporter = null;
    PiCodingRunner.CodingAttempt attempt = null;
    boolean exported = false;
    try {
      String task = json.writeValueAsString(Map.of("goal", p.path("goal").asText(),
          "acceptance", p.path("acceptance"), "policy", "Only edit the prepared repository."));
      attempt = runner.run(handle, List.of("/opt/pi/node_modules/.bin/pi", "--mode", "rpc",
          "--provider", provider, "--model", model, "--no-approve",
          "--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes",
          "--no-context-files", "--tools", "read,bash,edit,write,grep,find,ls",
          "--thinking", "high", "--append-system-prompt",
          "Factory owns the repository boundary. Edit only the prepared repository; make no external writes; report completion after the requested checks.",
          "--session-dir", "/state/pi/sessions"), "/workspace/repo", task,
          Duration.ofMinutes(30), 16L * 1024 * 1024, Map.of(
              "PI_OFFLINE", "1",
              "FACTORY_MODEL_TOKEN", modelToken,
              "FACTORY_PI_GATEWAY_URL", gatewayUrl));
      exporter = sandboxes.freezeForExport(handle, source);
      CommandResult diff = snapshot(exporter, p.path("baseRevision").asText());
      CommandResult tree = sandboxes.exec(exporter, "cd repo && git write-tree", 30);
      if (!tree.ok()) throw new IllegalStateException("Cannot identify frozen candidate tree");
      exported = true;
      if (!attempt.settled()) {
        String failure = json.writeValueAsString(Map.of("status", "FAILED", "stage", "coding",
            "reason", "PI_UNSETTLED", "retained", false, "exportAttempted", true,
            "patch_bytes", diff.stdout().length()));
        return new AgentResult(Map.of("candidate.patch", diff.stdout(),
            "candidate.json", failure, "pi-session.jsonl", attempt.rawExchange(),
            "usage.json", "{\"provider_calls\":1,\"settled\":false,\"retained\":true}"),
            Map.of("provider_calls", 1, "settled", false, "retained", false,
                "failure_stage", "coding"));
      }
      String candidate = tree.stdout().trim();
      String patchHash = org.folio.factory.core.service.ArtifactStore.sha256(diff.stdout());
      return new AgentResult(Map.of("candidate.patch", diff.stdout(),
          "candidate.json", "{\"base\":\"" + p.path("baseRevision").asText()
              + "\",\"candidateTree\":\"" + candidate + "\",\"patchSha256\":\""
              + patchHash + "\"}", "pi-session.jsonl", attempt.rawExchange(),
          "usage.json", "{\"provider_calls\":1,\"settled\":true}"),
          Map.of("provider_calls", 1, "settled", true, "candidate", candidate));
    } catch (RuntimeException e) {
      String exportedPatch = "";
      try {
        if (exporter == null) exporter = sandboxes.freezeForExport(handle, source);
        exportedPatch = snapshot(exporter, p.path("baseRevision").asText()).stdout();
        exported = true;
      }
      catch (RuntimeException ignored) { }
      String failure = json.writeValueAsString(Map.of("status", "FAILED", "stage", "coding",
          "reason", failureMessage(e), "retained", !exported, "exportAttempted", true,
          "sandbox", handle.containerId()));
      return new AgentResult(Map.of("candidate.patch", exportedPatch, "candidate.json", failure,
          "pi-session.jsonl", attempt == null ? "" : attempt.rawExchange(),
          "usage.json", "{\"provider_calls\":1,\"retained\":true}"),
          Map.of("provider_calls", 1, "settled", false, "retained", true,
              "failure_stage", "coding"));
    } finally {
      if (exporter != null) sandboxes.teardown(exporter);
      if (exported) sandboxes.teardown(handle);
    }
  }

  private static String failureMessage(Throwable error) {
    StringBuilder message = new StringBuilder();
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (message.length() > 0) message.append("; cause: ");
      message.append(current.getClass().getSimpleName()).append(": ")
          .append(current.getMessage() == null ? "" : current.getMessage());
    }
    return message.length() == 0 ? "SNAPSHOT_FAILED" : message.toString();
  }

  CommandResult snapshot(SandboxHandle handle, String baseRevision) {
    String base = Shell.quote(baseRevision);
    // Stage the complete tree, then diff the index against the frozen base.
    // Comparing base..HEAD loses staged-only work because HEAD is still the base.
    String command = "cd repo && git add -A && git diff --cached --binary " + base
        + " > /tmp/factory-candidate.patch && if test ! -s /tmp/factory-candidate.patch "
        + "&& test -n \"$(git status --porcelain)\"; then "
        + "echo 'snapshot produced an empty patch for a dirty tree' >&2; exit 2; fi; "
        + "cat /tmp/factory-candidate.patch";
    CommandResult result = sandboxes.exec(handle, command, 120);
    if (!result.ok() || result.stdout().contains("usage: git diff")) {
      throw new IllegalStateException("snapshot failed: " + result.stderr());
    }
    return result;
  }

  private AgentResult verify(AgentContext context, String patch) {
    var p = context.triggerPayload();
    JsonNode candidate = json.readTree(context.requireInput("candidate.json").content());
    if ("FAILED".equals(candidate.path("status").asString())) {
      return AgentResult.of("verification.json", json.writeValueAsString(Map.of(
          "status", "FAIL", "independent", false, "stage", candidate.path("stage").asString("coding"),
          "reason", candidate.path("reason").asString("PI_FAILED"), "retained", true)));
    }
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
