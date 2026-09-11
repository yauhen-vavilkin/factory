package org.folio.factory.devfactory.pi;

import java.time.Duration;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.worker.recovery.RecoveryBundleStore;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.tools.Shell;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Flow-local workers keep Pi execution separate from the legacy harness. */
public final class PiWorker implements AgentWorker {
  private static final long BASELINE_BUILD_TIMEOUT = 1_800L;
  private static final long VERIFY_BUILD_TIMEOUT = 1_800L;
  private static final String POLICY = "Factory owns the repository boundary. Edit only the prepared repository; "
      + "make no external writes; report completion after the requested checks.";

  private final String id;
  private final SandboxService sandboxes;
  private final PiCodingRunner runner;
  private final Modsidecar208Verifier modsidecar208 = new Modsidecar208Verifier();
  private final String modelToken;
  private final String gatewayUrl;
  private final String thinking;
  private final RecoveryBundleStore recoveryStore;
  private final JsonMapper json = JsonMapper.builder().build();

  public PiWorker(String id, SandboxService sandboxes, PiCodingRunner runner,
      String modelToken, String gatewayUrl) {
    this(id, sandboxes, runner, modelToken, gatewayUrl, "high");
  }

  public PiWorker(String id, SandboxService sandboxes, PiCodingRunner runner,
      String modelToken, String gatewayUrl, String thinking) {
    this(id, sandboxes, runner, modelToken, gatewayUrl, thinking, null);
  }

  public PiWorker(String id, SandboxService sandboxes, PiCodingRunner runner,
      String modelToken, String gatewayUrl, String thinking, RecoveryBundleStore recoveryStore) {
    this.id = id;
    this.sandboxes = sandboxes;
    this.runner = runner;
    this.modelToken = modelToken == null ? "" : modelToken;
    this.gatewayUrl = gatewayUrl == null ? "http://factory-gateway:8080/v1" : gatewayUrl;
    this.thinking = thinking == null || thinking.isBlank() ? "high" : thinking;
    this.recoveryStore = recoveryStore;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public AgentResult execute(AgentContext context) throws AgentExecutionException {
    if ("pi-prepare-worker".equals(id)) {
      return prepare(context);
    }
    if ("pi-coding-worker".equals(id)) {
      return code(context);
    }
    if ("pi-verify-worker".equals(id)) {
      JsonNode candidate = context.inputs().containsKey("candidate.json")
          ? json.readTree(context.requireInput("candidate.json").content()) : null;
      if (candidate != null && "FAILED".equals(candidate.path("status").asText())) {
        return failedVerification(candidate, context.triggerPayload());
      }
      String patch = context.inputs().containsKey("candidate.patch")
          ? context.requireInput("candidate.patch").content() : "";
      if (patch == null || patch.isBlank()) {
        ObjectNode result = json.createObjectNode();
        result.put("status", "FAIL");
        result.put("independent", false);
        result.put("stage", "VERIFY");
        result.put("reason", "EMPTY_CANDIDATE");
        result.put("base", context.triggerPayload().path("baseRevision").asText());
        return AgentResult.of("verification.json", json.writeValueAsString(result));
      }
      return verify(context, patch);
    }
    return finalizeResult(context);
  }

  private AgentResult prepare(AgentContext context) {
    JsonNode payload = context.triggerPayload();
    JsonNode resolved = payload.path("resolvedIntent");
    if (!resolved.path("profile").isObject()) {
      throw new AgentExecutionException("resolved trusted profile is required");
    }
    JsonNode profile = resolved.path("profile");
    ObjectNode contract = json.createObjectNode();
    contract.put("schema", "ExecutionContract/v1");
    contract.set("profile", profile);
    contract.set("resolvedIntent", resolved);
    contract.put("sourceRevision", payload.path("baseRevision").asText());
    contract.put("mode", "PRIVATE_FRESH_RESOLUTION");

    if (!isModsidecar208(payload)) {
      contract.put("status", "READY");
      ObjectNode baseline = contract.putObject("baseline");
      baseline.put("status", "NOT_REQUIRED");
      baseline.put("reason", "generic Pi flow path");
      return new AgentResult(Map.of("contract.json", json.writeValueAsString(contract),
          "baseline.json", json.writeValueAsString(baseline), "baseline.log", ""), Map.of());
    }

    ObjectNode baseline = json.createObjectNode();
    baseline.put("required", true);
    baseline.set("checker", json.valueToTree(modsidecar208.details()));
    baseline.put("baseRevision", payload.path("baseRevision").asText());
    CommandResult tests = null;
    CommandResult redCheck = null;
    CommandResult reports = null;
    SandboxHandle handle = null;
    String status = "INCOMPLETE";
    String failure = null;
    try {
      handle = sandboxes.create(sourceSpec(payload, profile, context, "-baseline",
          dependencyNetworkPolicy(profile)));
      tests = modsidecar208.runUnitTests(sandboxes, handle, BASELINE_BUILD_TIMEOUT);
      redCheck = modsidecar208.verifyBaseIsRed(sandboxes, handle, 900L);
      reports = modsidecar208.summarizeReports(sandboxes, handle, 300L);
      Modsidecar208Verifier.ReportSummary summary = modsidecar208.parseReportSummary(reports);
      boolean red = redCheck.ok() && redCheck.stdout().contains("BASELINE=MISSING");
      boolean reportEvidence = reports.ok() && summary.valid() && summary.totalTests() > 0
          && summary.failures() == 0 && summary.errors() == 0;
      boolean passed = tests.ok() && red && reportEvidence;
      status = passed ? "PASS" : "FAIL";
      if (!passed) {
        failure = !tests.ok() ? "BASELINE_TESTS_FAILED"
            : !red ? "BASELINE_TASK_CHECK_NOT_RED"
                : !reportEvidence ? "BASELINE_REPORT_INVALID" : "BASELINE_REPORT_MISSING";
      }
      baseline.put("mavenExit", tests.exitCode());
      baseline.put("mavenDurationMs", tests.durationMs());
      baseline.put("redCheckExit", redCheck.exitCode());
      baseline.put("redCheck", red ? "ABSENT_SETTING_CONFIRMED" : "NOT_CONFIRMED");
      baseline.put("reportsExit", reports.exitCode());
      baseline.set("surefire", json.valueToTree(summary.asMap()));
      baseline.put("status", status);
    } catch (RuntimeException error) {
      failure = failureMessage(error);
      baseline.put("status", "INCOMPLETE");
      baseline.put("failureStage", "PREPARE");
      baseline.put("failure", failure);
    } finally {
      if (handle != null) {
        sandboxes.teardown(handle);
      }
    }
    if (failure != null) {
      baseline.put("failure", failure);
    }
    contract.put("status", "PASS".equals(status) ? "READY" : status);
    contract.set("baseline", baseline);
    String log = commandLog("maven-clean-test", tests) + commandLog("base-task-check", redCheck)
        + commandLog("surefire-summary", reports);
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("baseline", status);
    metrics.put("task_checker", "modsidecar-208-v1");
    return new AgentResult(Map.of("contract.json", json.writeValueAsString(contract),
        "baseline.json", json.writeValueAsString(baseline), "baseline.log", log), metrics);
  }

  private AgentResult code(AgentContext context) {
    JsonNode payload = context.triggerPayload();
    JsonNode contract = json.readTree(context.requireInput("contract.json").content());
    if (!"READY".equals(contract.path("status").asText("READY"))) {
      return failedCoding("PREPARE", "BASELINE_NOT_READY", null, 0, false);
    }
    JsonNode profile = contract.path("profile");
    String provider = required(profile, "modelProvider");
    String model = required(profile, "modelId");
    SandboxSpec source = sourceSpec(payload, profile, context, "");
    SandboxHandle handle = sandboxes.create(source);
    SandboxHandle exporter = null;
    PiCodingRunner.CodingAttempt attempt = null;
    boolean exported = false;
    boolean recoveryPublished = recoveryStore == null;
    long started = System.nanoTime();
    try {
      ObjectNode task = codingTask(payload);
      attempt = runner.run(handle, piArgv(provider, model), "/workspace/repo",
          json.writeValueAsString(task), Duration.ofMinutes(30), 16L * 1024 * 1024,
          Map.of("PI_OFFLINE", "1", "FACTORY_MODEL_TOKEN", modelToken,
              "FACTORY_PI_GATEWAY_URL", gatewayUrl));
      exporter = sandboxes.freezeForExport(handle, source);
      CommandResult diff = snapshotAllowEmpty(exporter, payload.path("baseRevision").asText());
      CommandResult tree = sandboxes.exec(exporter, "cd repo && git write-tree", 30);
      if (!tree.ok()) {
        throw new IllegalStateException("Cannot identify frozen candidate tree: " + tree.stderr());
      }
      exported = true;
      long durationMs = (System.nanoTime() - started) / 1_000_000L;
      String usage = usageJson(attempt, provider, model, durationMs);
      String baseRevision = payload.path("baseRevision").asText();
      if (!attempt.settled()) {
        String failureStage = isModsidecar208(payload) ? "PI_RUNTIME" : "coding";
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("candidate.patch", diff.stdout());
        outputs.put("candidate.json", failureJson(failureStage, "PI_UNSETTLED", false,
            diff.stdout().length(), baseRevision));
        outputs.put("pi-session.jsonl", attempt.rawExchange());
        outputs.put("usage.json", usage);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("provider_calls", providerCalls(attempt));
        metrics.put("settled", false);
        metrics.put("failure_stage", failureStage);
        AgentResult result = publishResult(context, payload, outputs, metrics, true, "PI_UNSETTLED");
        recoveryPublished = true;
        return result;
      }
      if (diff.stdout().isBlank()) {
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("candidate.patch", "");
        outputs.put("candidate.json", failureJson("coding", "EMPTY_CANDIDATE", false, 0,
            baseRevision));
        outputs.put("pi-session.jsonl", attempt.rawExchange());
        outputs.put("usage.json", usage);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("provider_calls", providerCalls(attempt));
        metrics.put("settled", true);
        metrics.put("failure_stage", "coding");
        metrics.put("retained", false);
        AgentResult result = publishResult(context, payload, outputs, metrics, true,
            "EMPTY_CANDIDATE");
        recoveryPublished = true;
        return result;
      }
      String candidate = tree.stdout().trim();
      ObjectNode metadata = json.createObjectNode();
      metadata.put("status", "READY");
      metadata.put("base", baseRevision);
      metadata.put("candidateTree", candidate);
      metadata.put("patchSha256", ArtifactStore.sha256(diff.stdout()));
      metadata.put("requestedProvider", provider);
      metadata.put("requestedModel", model);
      metadata.put("durationMs", durationMs);
      metadata.put("retained", false);
      Map<String, String> outputs = new LinkedHashMap<>();
      outputs.put("candidate.patch", diff.stdout());
      outputs.put("candidate.json", json.writeValueAsString(metadata));
      outputs.put("pi-session.jsonl", attempt.rawExchange());
      outputs.put("usage.json", usage);
      Map<String, Object> metrics = new LinkedHashMap<>();
      metrics.put("provider_calls", providerCalls(attempt));
      metrics.put("settled", true);
      metrics.put("candidate", candidate);
      AgentResult result = publishResult(context, payload, outputs, metrics, true, null);
      recoveryPublished = true;
      return result;
    } catch (RecoveryPublicationException error) {
      throw new AgentExecutionException("Pi result durability failed; retaining coding sandbox "
          + handle.containerId() + " and exporter "
          + (exporter == null ? "<none>" : exporter.containerId()), error);
    } catch (RuntimeException error) {
      String exportedPatch = "";
      try {
        if (exporter == null) {
          exporter = sandboxes.freezeForExport(handle, source);
        }
        exportedPatch = snapshotAllowEmpty(exporter, payload.path("baseRevision").asText()).stdout();
        exported = true;
      } catch (RuntimeException ignored) {
        // The failure artifact names the still-retained stopped workspace.
      }
      String failureStage = isModsidecar208(payload) ? "PI_RUNTIME" : "coding";
      boolean settled = attempt != null && attempt.settled();
      String usage = usageJson(attempt, provider, model,
          (System.nanoTime() - started) / 1_000_000L);
      Map<String, String> outputs = new LinkedHashMap<>();
      outputs.put("candidate.patch", exportedPatch);
      outputs.put("candidate.json", failureJson(failureStage, failureMessage(error), !exported,
          exportedPatch.length(), payload.path("baseRevision").asText()));
      outputs.put("pi-session.jsonl", attempt == null ? "" : attempt.rawExchange());
      outputs.put("usage.json", usage);
      Map<String, Object> metrics = new LinkedHashMap<>();
      metrics.put("provider_calls", attempt == null ? 0 : providerCalls(attempt));
      metrics.put("settled", settled);
      metrics.put("failure_stage", failureStage);
      metrics.put("retained", !exported);
      try {
        AgentResult result = publishResult(context, payload, outputs, metrics, false,
            failureMessage(error));
        recoveryPublished = true;
        return result;
      } catch (RecoveryPublicationException publishFailure) {
        throw new AgentExecutionException("Pi failure artifacts could not be made durable; retaining "
            + "coding sandbox " + handle.containerId(), publishFailure);
      }
    } finally {
      if (recoveryPublished && exporter != null) {
        sandboxes.teardown(exporter);
      }
      if (recoveryPublished && exported) {
        sandboxes.teardown(handle);
      }
    }
  }

  private AgentResult failedCoding(String stage, String reason, String session,
                                   int patchBytes, boolean retained) {
    String raw = session == null ? "" : session;
    return new AgentResult(Map.of("candidate.patch", "",
        "candidate.json", failureJson(stage, reason, retained, patchBytes),
        "pi-session.jsonl", raw,
        "usage.json", "{\"provider_calls\":0,\"settled\":false,\"costStatus\":\"UNPRICED\"}"),
        Map.of("provider_calls", 0, "settled", false, "failure_stage", stage,
            "retained", retained));
  }

  private AgentResult publishResult(AgentContext context, JsonNode payload,
                                    Map<String, String> outputs, Map<String, Object> metrics,
                                    boolean complete, String failureReason) {
    Map<String, Object> publishedMetrics = new LinkedHashMap<>(metrics);
    if (recoveryStore != null) {
      try {
        Path bundle = recoveryStore.publish(context.executionId(), context.stepId(), context.attempt(),
            payload.path("taskId").asText(), payload.path("baseRevision").asText(), outputs,
            complete, failureReason);
        publishedMetrics.put("recovery_locator", bundle.toAbsolutePath().toString());
      } catch (RuntimeException error) {
        throw new RecoveryPublicationException("could not publish Pi recovery bundle", error);
      }
    }
    return new AgentResult(outputs, publishedMetrics);
  }

  CommandResult snapshot(SandboxHandle handle, String baseRevision) {
    return snapshot(handle, baseRevision, false);
  }

  private CommandResult snapshotAllowEmpty(SandboxHandle handle, String baseRevision) {
    return snapshot(handle, baseRevision, true);
  }

  private CommandResult snapshot(SandboxHandle handle, String baseRevision, boolean allowEmpty) {
    String base = Shell.quote(baseRevision);
    String command = "cd repo && git add -A && git diff --cached --binary " + base
        + " > /tmp/factory-candidate.patch && "
        + (allowEmpty ? "cat /tmp/factory-candidate.patch"
            : "if test ! -s /tmp/factory-candidate.patch; then "
                + "echo 'candidate patch is empty' >&2; exit 3; fi; cat /tmp/factory-candidate.patch");
    CommandResult result = sandboxes.exec(handle, command, 120);
    if (!result.ok() || result.stdout().contains("usage: git diff")) {
      throw new IllegalStateException("snapshot failed: " + result.stderr());
    }
    return result;
  }

  private AgentResult verify(AgentContext context, String patch) {
    JsonNode payload = context.triggerPayload();
    JsonNode candidate = json.readTree(context.requireInput("candidate.json").content());
    if ("FAILED".equals(candidate.path("status").asText())) {
      return failedVerification(candidate, payload);
    }
    JsonNode baseline = context.inputs().containsKey("baseline.json")
        ? json.readTree(context.requireInput("baseline.json").content()) : null;
    boolean task = isModsidecar208(payload);
    JsonNode profile = context.inputs().containsKey("contract.json")
        ? json.readTree(context.requireInput("contract.json").content()).path("profile") : null;
    SandboxSpec spec = profile == null || !profile.isObject()
        ? new SandboxSpec(payload.path("taskId").asText() + "-verify",
            payload.path("repoUrl").asText(), payload.path("baseRevision").asText(),
            payload.path("branch").asText() + "-verify", context.executionId() + "-verify")
        : sourceSpec(payload, profile, context, "-verify",
            dependencyNetworkPolicy(profile));
    SandboxHandle fresh = sandboxes.create(spec);
    try {
      CommandResult applied = sandboxes.exec(fresh, "cd repo && printf '%s' " + Shell.quote(patch)
          + " | git apply --binary - && git add -A", 120);
      if (!applied.ok()) {
        return AgentResult.of("verification.json", json.writeValueAsString(Map.of(
            "status", "FAIL", "independent", false, "freshCheckout", true,
            "stage", "VERIFY", "reason", "PATCH_REJECTED", "stderr", bounded(applied.stderr()))));
      }
      if (!task) {
        CommandResult tests = sandboxes.exec(fresh, "cd repo && mvn -B -ntp verify", 900);
        return AgentResult.of("verification.json", "{\"status\":\"" + (tests.ok() ? "PASS" : "FAIL")
            + "\",\"independent\":true,\"freshCheckout\":true,\"mavenVerifyExit\":"
            + tests.exitCode() + ",\"candidateBytes\":" + patch.length() + "}");
      }
      CommandResult checker = modsidecar208.verifyCandidate(sandboxes, fresh,
          payload.path("baseRevision").asText(), 900L);
      CommandResult tests = modsidecar208.runUnitTests(sandboxes, fresh, VERIFY_BUILD_TIMEOUT);
      CommandResult reports = modsidecar208.summarizeReports(sandboxes, fresh, 300L);
      Modsidecar208Verifier.ReportSummary reportSummary = modsidecar208.parseReportSummary(reports);
      Modsidecar208Verifier.ReportSummary baselineSummary = baseline == null ? null
          : Modsidecar208Verifier.ReportSummary.fromJson(baseline.path("surefire"));
      CommandResult tree = sandboxes.exec(fresh, "cd repo && git write-tree", 30);
      boolean checksPass = checker.ok() && checker.stdout().contains("DEFAULT=8")
          && checker.stdout().contains("OVERRIDE=12");
      boolean identityPass = payload.path("baseRevision").asText()
          .equalsIgnoreCase(candidate.path("base").asText())
          && ArtifactStore.sha256(patch).equals(candidate.path("patchSha256").asText());
      boolean treePass = tree.ok() && candidate.path("candidateTree").asText().equals(tree.stdout().trim());
      boolean reportPass = reports.ok() && reportSummary.valid() && reportSummary.totalTests() > 0
          && reportSummary.failures() == 0 && reportSummary.errors() == 0
          && baselineSummary != null && baselineSummary.valid()
          && reportSummary.preserves(baselineSummary);
      boolean passed = checksPass && identityPass && treePass && reportPass
          && tests.ok() && tree.ok();
      ObjectNode result = json.createObjectNode();
      result.put("status", passed ? "PASS" : "FAIL");
      result.put("independent", true);
      result.put("freshCheckout", true);
      result.put("freshVerifier", true);
      result.put("stage", "VERIFY");
      result.put("base", payload.path("baseRevision").asText());
      result.put("candidateTree", tree.ok() ? tree.stdout().trim() : "unknown");
      result.put("candidateTreeMatches", treePass);
      result.put("candidateIdentityMatches", identityPass);
      result.put("candidateBytes", patch.length());
      result.put("checkerVersion", Modsidecar208Verifier.CHECKER_VERSION);
      result.put("checkerExit", checker.exitCode());
      result.put("mavenCleanTestExit", tests.exitCode());
      result.put("surefireSummaryExit", reports.exitCode());
      result.put("checkerOutput", bounded(checker.stdout()));
      result.put("surefireSummary", bounded(reports.stdout()));
      result.set("baselineSurefire", json.valueToTree(
          baselineSummary == null ? Map.of() : baselineSummary.asMap()));
      result.set("candidateSurefire", json.valueToTree(reportSummary.asMap()));
      if (!passed) {
        result.put("reason", !checker.ok() || !checksPass ? "TASK_CHECK_FAILED"
            : !identityPass ? "CANDIDATE_IDENTITY_FAILED"
                : !treePass ? "CANDIDATE_TREE_FAILED"
                    : !tests.ok() ? "CANDIDATE_TESTS_FAILED" : "SUREFIRE_EVIDENCE_FAILED");
      }
      return AgentResult.of("verification.json", json.writeValueAsString(result));
    } finally {
      sandboxes.teardown(fresh);
    }
  }

  private AgentResult failedVerification(JsonNode candidate, JsonNode payload) {
    ObjectNode result = json.createObjectNode();
    result.put("status", "FAIL");
    result.put("independent", false);
    result.put("stage", candidate.path("stage").asText("PI_RUNTIME"));
    result.put("reason", candidate.path("reason").asText("PI_FAILED"));
    result.put("retained", candidate.path("retained").asBoolean(false));
    result.put("base", candidate.path("base").asText(payload.path("baseRevision").asText()));
    return AgentResult.of("verification.json", json.writeValueAsString(result));
  }

  private AgentResult finalizeResult(AgentContext context) {
    JsonNode verification = json.readTree(context.requireInput("verification.json").content());
    String status = verification.path("status").asText("FAIL");
    String outcome = switch (status) {
      case "PASS" -> "SUCCESS";
      case "INCOMPLETE" -> "INCOMPLETE";
      case "CANCELLED" -> "CANCELLED";
      case "ERROR" -> "ERROR";
      default -> "FAILED";
    };
    ObjectNode result = json.createObjectNode();
    result.put("outcome", outcome);
    result.put("workflowOperational", true);
    result.put("benchmarkOutcome", outcome);
    if (!"SUCCESS".equals(outcome)) {
      ObjectNode failure = result.putObject("failure");
      failure.put("reason", verification.path("reason").asText("VERIFICATION_FAILED"));
      failure.put("stage", verification.path("stage").asText("VERIFY"));
    }
    ObjectNode usage = result.putObject("usage");
    if (context.inputs().containsKey("usage.json")) {
      JsonNode usageInput = json.readTree(context.requireInput("usage.json").content());
      if (usageInput.isObject()) {
        usage.setAll((ObjectNode) usageInput);
      }
    }
    boolean retained = context.inputs().containsKey("candidate.json")
        && json.readTree(context.requireInput("candidate.json").content()).path("retained").asBoolean(false);
    result.putObject("cleanup").put("status", retained ? "RETAINED" : "COMPLETE")
        .put("retained", retained);
    result.putObject("session").put("ref", context.inputs().containsKey("pi-session.jsonl")
        ? "pi-session.jsonl" : "unknown");
    result.putObject("references").put("candidate", "candidate.patch")
        .put("verification", "verification.json").put("usage", "usage.json");
    ObjectNode manifest = json.createObjectNode();
    manifest.put("candidate", "candidate.patch");
    manifest.put("verification", "verification.json");
    JsonNode candidate = context.inputs().containsKey("candidate.json")
        ? json.readTree(context.requireInput("candidate.json").content()) : null;
    String base = verification.path("base").asText("");
    if (base.isBlank() && candidate != null) {
      base = candidate.path("base").asText("");
    }
    manifest.put("base", base.isBlank() ? "unknown" : base);
    manifest.put("candidatePatchSha256", candidate == null
        ? "unknown" : candidate.path("patchSha256").asText("unknown"));
    return new AgentResult(Map.of("result.json", json.writeValueAsString(result),
        "manifest.json", json.writeValueAsString(manifest)),
        Map.of("workflowOperational", true, "outcome", outcome));
  }

  private SandboxSpec sourceSpec(JsonNode payload, JsonNode profile, AgentContext context,
                                 String suffix) {
    return sourceSpec(payload, profile, context, suffix,
        profile.path("networkPolicy").path("execution").asText(null));
  }

  private SandboxSpec sourceSpec(JsonNode payload, JsonNode profile, AgentContext context,
                                 String suffix, String networkPolicy) {
    String branch = payload.path("branch").asText() + suffix;
    return new SandboxSpec(payload.path("taskId").asText(), payload.path("repoUrl").asText(),
        payload.path("baseRevision").asText(), branch,
        context.executionId() + "-attempt-" + context.attempt(),
        profile.path("imageReference").asText(null), profile.path("platform").asText(null),
        networkPolicy);
  }

  private static String dependencyNetworkPolicy(JsonNode profile) {
    String execution = profile.path("networkPolicy").path("execution").asText(null);
    return "GATEWAY_ONLY".equals(execution) ? "DEPENDENCY_ONLY" : execution;
  }

  private ObjectNode codingTask(JsonNode payload) {
    ObjectNode task = json.createObjectNode();
    task.put("taskId", payload.path("taskId").asText());
    task.put("repository", canonicalRepository(payload));
    task.put("baseRevision", payload.path("baseRevision").asText());
    task.put("goal", payload.path("goal").asText());
    task.set("acceptance", payload.path("acceptance").deepCopy());
    task.set("acceptanceCriteria", payload.path("acceptanceCriteria").deepCopy());
    task.set("constraints", payload.path("constraints").deepCopy());
    task.put("rawTaskText", payload.path("rawTaskText").asText());
    task.put("policy", POLICY);
    return task;
  }

  private List<String> piArgv(String provider, String model) {
    return List.of("/opt/pi/node_modules/.bin/pi", "--mode", "rpc", "--provider", provider,
        "--model", model, "--no-approve", "--no-extensions", "--no-skills",
        "--no-prompt-templates", "--no-themes", "--no-context-files", "--tools",
        "read,bash,edit,write,grep,find,ls", "--thinking", thinking,
        "--append-system-prompt", POLICY, "--session-dir", "/state/pi/sessions");
  }

  private boolean isModsidecar208(JsonNode payload) {
    String slug = payload.path("resolvedIntent").path("repository").path("canonicalSlug").asText();
    String origin = payload.path("resolvedIntent").path("repository").path("origin").asText();
    return modsidecar208.applies(payload.path("taskId").asText(), slug,
        payload.path("baseRevision").asText())
        || (Modsidecar208Verifier.TASK_ID.equals(payload.path("taskId").asText())
        && origin.contains("folio-module-sidecar")
        && Modsidecar208Verifier.BASE_REVISION.equalsIgnoreCase(payload.path("baseRevision").asText()));
  }

  private static String canonicalRepository(JsonNode payload) {
    String slug = payload.path("resolvedIntent").path("repository").path("canonicalSlug").asText();
    return slug.isBlank() ? payload.path("repoUrl").asText() : slug;
  }

  private static String required(JsonNode node, String name) {
    String value = node.path(name).asText(null);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("execution contract lacks " + name);
    }
    return value;
  }

  private static int providerCalls(PiCodingRunner.CodingAttempt attempt) {
    return (int) attempt.events().stream().filter(event -> "message_start".equals(event.path("type").asText())
        && "assistant".equals(event.path("message").path("role").asText())).count();
  }

  private String usageJson(PiCodingRunner.CodingAttempt attempt, String provider, String model,
                           long durationMs) {
    ObjectNode usage = json.createObjectNode();
    usage.put("schema", "PiUsage/v1");
    usage.put("provider", provider);
    usage.put("model", model);
    usage.put("provider_calls", attempt == null ? 0 : providerCalls(attempt));
    usage.put("settled", attempt != null && attempt.settled());
    usage.put("process_exit_code", attempt == null ? -1 : attempt.processExitCode());
    usage.put("duration_ms", durationMs);
    usage.put("stdout_bytes", attempt == null ? 0 : attempt.stdoutBytes());
    usage.put("stderr_bytes", attempt == null ? 0 : attempt.stderrBytes());
    usage.put("cost_status", "UNPRICED");
    usage.putNull("estimated_cost");
    JsonNode nativeUsage = latestUsage(attempt);
    if (nativeUsage == null) {
      usage.putNull("native_usage");
    } else {
      usage.set("native_usage", nativeUsage);
    }
    return json.writeValueAsString(usage);
  }

  private static JsonNode latestUsage(PiCodingRunner.CodingAttempt attempt) {
    if (attempt == null) {
      return null;
    }
    JsonNode latest = null;
    for (JsonNode event : attempt.events()) {
      JsonNode message = event.path("message");
      if (message.path("role").asText().equals("assistant") && message.path("usage").isObject()) {
        latest = message.path("usage");
      }
    }
    return latest;
  }

  private static String failureJson(String stage, String reason, boolean retained, int patchBytes) {
    return failureJson(stage, reason, retained, patchBytes, null);
  }

  private static String failureJson(String stage, String reason, boolean retained, int patchBytes,
                                    String baseRevision) {
    ObjectNode result = JsonMapper.builder().build().createObjectNode();
    result.put("status", "FAILED");
    result.put("stage", stage == null ? "" : stage);
    result.put("reason", reason == null ? "" : reason);
    result.put("retained", retained);
    result.put("patch_bytes", patchBytes);
    if (baseRevision != null && !baseRevision.isBlank()) {
      result.put("base", baseRevision);
    }
    return JsonMapper.builder().build().writeValueAsString(result);
  }

  private static final class RecoveryPublicationException extends RuntimeException {
    private RecoveryPublicationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static String commandLog(String name, CommandResult result) {
    if (result == null) {
      return name + "=NOT_RUN\n";
    }
    return name + " exit=" + result.exitCode() + " durationMs=" + result.durationMs()
        + "\nSTDOUT\n" + bounded(result.stdout()) + "\nSTDERR\n" + bounded(result.stderr()) + "\n";
  }

  private static String bounded(String value) {
    if (value == null) {
      return "";
    }
    int max = 256 * 1024;
    return value.length() <= max ? value : value.substring(0, max) + "\n[output truncated]\n";
  }

  private static String failureMessage(Throwable error) {
    StringBuilder message = new StringBuilder();
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (message.length() > 0) {
        message.append("; cause: ");
      }
      message.append(current.getClass().getSimpleName()).append(": ")
          .append(current.getMessage() == null ? "" : current.getMessage());
    }
    return message.length() == 0 ? "PI_RUNTIME_FAILED" : bounded(message.toString());
  }
}
