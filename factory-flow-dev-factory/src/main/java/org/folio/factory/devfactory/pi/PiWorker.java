package org.folio.factory.devfactory.pi;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Flow-local workers keep Pi execution separate from the legacy harness. */
public final class PiWorker implements AgentWorker {
  private static final long BASELINE_BUILD_TIMEOUT = 1_800L;
  private static final long VERIFY_BUILD_TIMEOUT = 1_800L;
  private static final int MAX_REPAIR_EVIDENCE_CHARS = 16 * 1024;
  private static final String REPAIR_POLICY = "ONE_AUTOMATIC_REPAIR";
  private static final String DOCKER_PROBE_COMMAND =
      "if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; "
          + "then echo DOCKER=AVAILABLE; else echo DOCKER=UNAVAILABLE; fi";
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
    if ("pi-repair-worker".equals(id)) {
      return repair(context);
    }
    if ("pi-reverify-worker".equals(id)) {
      return reverify(context);
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
      return prepareGeneric(payload, resolved, profile, contract, context);
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

  /**
   * Honest readiness for the generic Pi path. The resolved verification plan
   * is the authority, so preparation proves the sandbox can actually run its
   * required checks before any coding budget is spent: a check that declares
   * a capability the sandbox cannot provide (Docker for Testcontainers
   * integration suites) blocks the execution as {@code BLOCKED_ENVIRONMENT}
   * with zero provider calls, and otherwise the required checks run once on
   * the clean base as the baseline, proving the verification environment is
   * usable for this repository. A runnable-but-red base is recorded as
   * {@code BASE_NOT_GREEN}, not blocked — a bug fix may legitimately have to
   * turn a red base green — while an output carrying the known
   * Docker/Testcontainers environment-failure signature is an environment
   * blocker, never a coding failure.
   */
  private AgentResult prepareGeneric(JsonNode payload, JsonNode resolved, JsonNode profile,
                                     ObjectNode contract, AgentContext context) {
    List<Check> checks = requiredChecks(resolved);
    ObjectNode baseline = json.createObjectNode();
    baseline.put("required", true);
    baseline.put("planId", resolved.path("verificationPlan").path("id").asString(""));
    if (checks.isEmpty()) {
      // No authoritative gate means no honest readiness: fail loudly here
      // instead of letting coding run against an unverifiable contract.
      baseline.put("status", "BLOCKED_ENVIRONMENT");
      baseline.put("reason", "VERIFICATION_PLAN_MISSING");
      baseline.put("detail", "the resolved verification plan carries no required checks");
      contract.put("status", "BLOCKED_ENVIRONMENT");
      contract.set("baseline", baseline);
      return new AgentResult(Map.of("contract.json", json.writeValueAsString(contract),
          "baseline.json", json.writeValueAsString(baseline), "baseline.log", ""),
          Map.of("baseline", "BLOCKED_ENVIRONMENT", "blocked_environment", true));
    }
    SandboxHandle handle = null;
    StringBuilder log = new StringBuilder();
    try {
      handle = sandboxes.create(sourceSpec(payload, profile, context, "-baseline",
          dependencyNetworkPolicy(profile)));
      String blocked = probeRequiredCapabilities(sandboxes, handle, checks, log);
      if (blocked != null) {
        baseline.put("status", "BLOCKED_ENVIRONMENT");
        baseline.put("reason", "REQUIRED_CAPABILITY_UNAVAILABLE");
        baseline.put("detail", blocked);
        contract.put("status", "BLOCKED_ENVIRONMENT");
        contract.set("baseline", baseline);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("baseline", "BLOCKED_ENVIRONMENT");
        metrics.put("blocked_environment", true);
        return new AgentResult(Map.of("contract.json", json.writeValueAsString(contract),
            "baseline.json", json.writeValueAsString(baseline), "baseline.log", log.toString()),
            metrics);
      }
      boolean green = true;
      boolean environmentFailure = false;
      ArrayNode evidence = baseline.putArray("checks");
      for (Check check : checks) {
        CommandResult run = sandboxes.exec(handle, "cd repo && " + check.command(),
            BASELINE_BUILD_TIMEOUT);
        log.append(commandLog(check.id(), run));
        ObjectNode entry = evidence.addObject();
        entry.put("id", check.id());
        entry.put("command", check.command());
        entry.put("exitCode", run.exitCode());
        entry.put("durationMs", run.durationMs());
        if (!run.ok()) {
          green = false;
          if (environmentFailureSignature(run.stdout()) || environmentFailureSignature(run.stderr())) {
            environmentFailure = true;
            entry.put("environmentFailure", true);
          }
        }
      }
      if (environmentFailure) {
        baseline.put("status", "BLOCKED_ENVIRONMENT");
        baseline.put("reason", "REQUIRED_CHECK_CANNOT_RUN");
        baseline.put("detail", "required check failed with the Docker/Testcontainers "
            + "environment-failure signature in a sandbox without Docker");
        contract.put("status", "BLOCKED_ENVIRONMENT");
        contract.set("baseline", baseline);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("baseline", "BLOCKED_ENVIRONMENT");
        metrics.put("blocked_environment", true);
        return new AgentResult(Map.of("contract.json", json.writeValueAsString(contract),
            "baseline.json", json.writeValueAsString(baseline), "baseline.log", log.toString()),
            metrics);
      }
      baseline.put("status", green ? "PASS" : "FAIL");
      if (!green) {
        baseline.put("failure", "BASE_NOT_GREEN");
      }
      contract.put("status", "READY");
      contract.set("baseline", baseline);
      Map<String, Object> metrics = new LinkedHashMap<>();
      metrics.put("baseline", green ? "PASS" : "FAIL");
      return new AgentResult(Map.of("contract.json", json.writeValueAsString(contract),
          "baseline.json", json.writeValueAsString(baseline), "baseline.log", log.toString()),
          metrics);
    } finally {
      if (handle != null) {
        sandboxes.teardown(handle);
      }
    }
  }

  /** One plan check as preparation and verification execute it: id plus exact argv. */
  private record Check(String id, String command, boolean requiresDocker) {
  }

  /** The plan's required checks; an empty list means the plan carries no authority. */
  private static List<Check> requiredChecks(JsonNode resolved) {
    List<Check> checks = new ArrayList<>();
    JsonNode plan = resolved == null ? null : resolved.path("verificationPlan");
    JsonNode checkNodes = plan == null ? null : plan.path("checks");
    if (checkNodes != null && checkNodes.isArray()) {
      for (JsonNode node : checkNodes) {
        if (!node.path("required").asBoolean(false)) {
          continue;
        }
        JsonNode argv = node.path("argv");
        if (!argv.isArray() || argv.isEmpty()) {
          continue;
        }
        List<String> parts = new ArrayList<>();
        argv.forEach(item -> parts.add(item.asString()));
        checks.add(new Check(node.path("id").asString("check"),
            String.join(" ", parts), node.path("requiresDocker").asBoolean(false)));
      }
    }
    return checks;
  }

  /**
   * Probes every capability a required check declares. Returns null when all
   * capabilities are available, or a concise explanation of the first missing
   * capability (which check needs it, what the probe showed).
   */
  private String probeRequiredCapabilities(SandboxService sandboxes, SandboxHandle handle,
                                           List<Check> checks, StringBuilder log) {
    if (checks.stream().noneMatch(Check::requiresDocker)) {
      return null;
    }
    CommandResult probe = sandboxes.exec(handle, DOCKER_PROBE_COMMAND, 60L);
    log.append(commandLog("docker-capability-probe", probe));
    if (probe.stdout() != null && probe.stdout().contains("DOCKER=AVAILABLE")) {
      return null;
    }
    Check needy = checks.stream().filter(Check::requiresDocker).findFirst().orElseThrow();
    return "required check '" + needy.id() + "' (" + needy.command() + ") needs the Docker "
        + "capability for its Testcontainers integration suite, but the sandbox has no usable "
        + "Docker environment: probe output '" + bounded(probe.stdout()) + "' "
        + bounded(probe.stderr());
  }

  /**
   * Best-effort signature of the known environment failure this deployment
   * cannot satisfy: Testcontainers unable to reach a Docker daemon. Used only
   * to classify a failure as an environment blocker, never to pass a check.
   */
  private static boolean environmentFailureSignature(String output) {
    if (output == null) {
      return false;
    }
    String lower = output.toLowerCase(Locale.ROOT);
    return lower.contains("could not find a valid docker environment")
        || lower.contains("rootlessdockerclientproviderstrategy")
        || (lower.contains("noclassdeffounderror")
            && (lower.contains("testcontainers") || lower.contains("docker")));
  }

  private AgentResult code(AgentContext context) {
    JsonNode payload = context.triggerPayload();
    JsonNode contract = json.readTree(context.requireInput("contract.json").content());
    // A preparation-time environment blocker must not spend coding budget:
    // return before any sandbox or provider call so the blocked classification
    // flows to verification and finalization with provider_calls = 0.
    if ("BLOCKED_ENVIRONMENT".equals(contract.path("status").asString(null))) {
      return failedCoding("PREPARE", "BLOCKED_ENVIRONMENT", null, 0, false);
    }
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
      if (attempt.terminalProviderFailure()) {
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("candidate.patch", diff.stdout());
        outputs.put("candidate.json", failureJson("PI_RUNTIME", "PROVIDER_ERROR", false,
            diff.stdout().length(), baseRevision));
        outputs.put("pi-session.jsonl", attempt.rawExchange());
        outputs.put("usage.json", usage);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("provider_calls", providerCalls(attempt));
        metrics.put("settled", false);
        metrics.put("failure_stage", "PI_RUNTIME");
        AgentResult result = publishResult(context, payload, outputs, metrics, true,
            "PROVIDER_ERROR");
        recoveryPublished = true;
        return result;
      }
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

  /**
   * The single Milestone 2 repair boundary. It deterministically routes the
   * first verification result and starts one fresh Pi process only for a
   * candidate-caused failure. The previous frozen patch is applied before Pi
   * starts, so the runtime repairs that candidate rather than starting over.
   */
  private AgentResult repair(AgentContext context) {
    JsonNode payload = context.triggerPayload();
    JsonNode verification = json.readTree(context.requireInput("verification.json").content());
    JsonNode previousCandidate = json.readTree(context.requireInput("candidate.json").content());
    String previousPatch = context.requireInput("candidate.patch").content();
    VerificationFailureClassifier.Decision decision =
        VerificationFailureClassifier.classify(verification);
    ObjectNode repair = repairArtifact(decision, previousCandidate, verification);
    if (decision.route() != VerificationFailureClassifier.Route.REPAIR) {
      repair.put("attempted", false);
      repair.put("attempt", 0);
      repair.put("providerCalls", 0);
      repair.put("result", "NOT_DISPATCHED");
      return new AgentResult(Map.of(
          "candidate.patch", previousPatch,
          "candidate.json", context.requireInput("candidate.json").content(),
          "repair.json", json.writeValueAsString(repair),
          "usage.json", context.requireInput("usage.json").content(),
          "pi-session.jsonl", context.requireInput("pi-session.jsonl").content()),
          Map.of("repair_attempted", false, "repair_route", decision.route().name(),
              "repair_provider_calls", 0));
    }

    JsonNode contract = json.readTree(context.requireInput("contract.json").content());
    JsonNode profile = contract.path("profile");
    String provider = required(profile, "modelProvider");
    String model = required(profile, "modelId");
    SandboxSpec source = sourceSpec(payload, profile, context, "-repair");
    SandboxHandle handle = null;
    SandboxHandle exporter = null;
    PiCodingRunner.CodingAttempt attempt = null;
    boolean recoveryPublished = recoveryStore == null;
    long started = System.nanoTime();
    repair.put("attempted", true);
    repair.put("attempt", 1);
    repair.put("runtimeContinuity", "FRESH_PI_INVOCATION_ON_FROZEN_CANDIDATE");
    try {
      handle = sandboxes.create(source);
      CommandResult applied = sandboxes.exec(handle, "cd repo && printf '%s' "
          + Shell.quote(previousPatch) + " | git apply --binary - && git add -A", 120);
      if (!applied.ok()) {
        AgentResult result = failedRepair(context, payload, previousPatch, repair, "PATCH_REJECTED",
            bounded(applied.stderr()), null, provider, model, started);
        recoveryPublished = true;
        return result;
      }
      ObjectNode task = codingTask(payload);
      task.put("mode", "REPAIR_EXISTING_CANDIDATE");
      task.set("repair", compactRepairFeedback(previousCandidate, verification));
      attempt = runner.run(handle, piArgv(provider, model), "/workspace/repo",
          json.writeValueAsString(task), Duration.ofMinutes(30), 16L * 1024 * 1024,
          Map.of("PI_OFFLINE", "1", "FACTORY_MODEL_TOKEN", modelToken,
              "FACTORY_PI_GATEWAY_URL", gatewayUrl));
      // Surface terminal Pi/provider failures before spending an export sandbox:
      // a provider-failed repair produced no candidate, and export preparation
      // (a fresh base fetch) must not mask the truthful failure reason.
      int calls = providerCalls(attempt);
      repair.put("providerCalls", calls);
      if (attempt.terminalProviderFailure()) {
        AgentResult result = failedRepair(context, payload, previousPatch, repair,
            "REPAIR_PROVIDER_ERROR", "Provider retries were exhausted", attempt, provider,
            model, started);
        recoveryPublished = true;
        return result;
      }
      if (!attempt.settled()) {
        AgentResult result = failedRepair(context, payload, previousPatch, repair, "PI_UNSETTLED", "",
            attempt, provider, model, started);
        recoveryPublished = true;
        return result;
      }
      exporter = sandboxes.freezeForExport(handle, source);
      CommandResult diff = snapshotAllowEmpty(exporter, payload.path("baseRevision").asText());
      CommandResult tree = sandboxes.exec(exporter, "cd repo && git write-tree", 30);
      if (!tree.ok()) {
        throw new IllegalStateException("Cannot identify repaired candidate tree: " + tree.stderr());
      }
      String patchHash = ArtifactStore.sha256(diff.stdout());
      String candidateTree = tree.stdout().trim();
      boolean changed = !candidateTree.equals(previousCandidate.path("candidateTree").asText())
          || !patchHash.equals(previousCandidate.path("patchSha256").asText());
      if (!changed) {
        AgentResult result = failedRepair(context, payload, diff.stdout(), repair,
            "REPAIR_DID_NOT_CHANGE_CANDIDATE", "Pi settled without changing the frozen candidate",
            attempt, provider, model, started);
        recoveryPublished = true;
        return result;
      }
      long durationMs = (System.nanoTime() - started) / 1_000_000L;
      ObjectNode metadata = json.createObjectNode();
      metadata.put("status", "READY");
      metadata.put("base", payload.path("baseRevision").asText());
      metadata.put("candidateTree", candidateTree);
      metadata.put("patchSha256", patchHash);
      metadata.put("requestedProvider", provider);
      metadata.put("requestedModel", model);
      metadata.put("durationMs", durationMs);
      metadata.put("repairAttempt", 1);
      metadata.put("previousCandidateTree", previousCandidate.path("candidateTree").asText());
      metadata.put("retained", false);
      repair.put("result", "NEW_CANDIDATE");
      repair.putObject("repairedCandidate")
          .put("base", metadata.path("base").asText())
          .put("candidateTree", candidateTree)
          .put("patchSha256", patchHash);
      String repairUsage = usageJson(attempt, provider, model, durationMs);
      Map<String, String> outputs = new LinkedHashMap<>();
      outputs.put("candidate.patch", diff.stdout());
      outputs.put("candidate.json", json.writeValueAsString(metadata));
      outputs.put("repair.json", json.writeValueAsString(repair));
      outputs.put("usage.json", aggregateUsage(context, repairUsage, calls));
      outputs.put("pi-session.jsonl", combinedSession(context, attempt.rawExchange()));
      Map<String, Object> metrics = new LinkedHashMap<>();
      metrics.put("repair_attempted", true);
      metrics.put("repair_route", decision.route().name());
      metrics.put("repair_provider_calls", calls);
      metrics.put("candidate", candidateTree);
      AgentResult result = publishResult(context, payload, outputs, metrics, true, null);
      recoveryPublished = true;
      return result;
    } catch (RecoveryPublicationException error) {
      throw new AgentExecutionException("Pi repair result durability failed; retaining repair sandbox "
          + (handle == null ? "<none>" : handle.containerId()), error);
    } catch (RuntimeException error) {
      AgentResult result = failedRepair(context, payload, previousPatch, repair,
          "REPAIR_RUNTIME_ERROR", failureMessage(error), attempt, provider, model, started);
      recoveryPublished = true;
      return result;
    } finally {
      if (recoveryPublished && exporter != null) {
        sandboxes.teardown(exporter);
      }
      if (recoveryPublished && handle != null) {
        sandboxes.teardown(handle);
      }
    }
  }

  private AgentResult failedRepair(AgentContext context, JsonNode payload, String patch,
                                   ObjectNode repair, String reason, String detail,
                                   PiCodingRunner.CodingAttempt attempt, String provider,
                                   String model, long started) {
    int calls = attempt == null ? 0 : providerCalls(attempt);
    repair.put("providerCalls", calls);
    repair.put("result", reason);
    if (detail != null && !detail.isBlank()) {
      repair.put("detail", bounded(detail));
    }
    long durationMs = (System.nanoTime() - started) / 1_000_000L;
    String usage = usageJson(attempt, provider, model, durationMs);
    Map<String, String> outputs = new LinkedHashMap<>();
    outputs.put("candidate.patch", patch == null ? "" : patch);
    outputs.put("candidate.json", failureJson("REPAIR", reason, false,
        patch == null ? 0 : patch.length(), payload.path("baseRevision").asText()));
    outputs.put("repair.json", json.writeValueAsString(repair));
    outputs.put("usage.json", aggregateUsage(context, usage, calls));
    outputs.put("pi-session.jsonl", combinedSession(context,
        attempt == null ? "" : attempt.rawExchange()));
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("repair_attempted", true);
    metrics.put("repair_route", "REPAIR");
    metrics.put("repair_provider_calls", calls);
    metrics.put("failure_stage", "REPAIR");
    return publishResult(context, payload, outputs, metrics, true, reason);
  }

  private ObjectNode repairArtifact(VerificationFailureClassifier.Decision decision,
                                    JsonNode candidate, JsonNode verification) {
    ObjectNode repair = json.createObjectNode();
    repair.put("schema", "DevFlowRepair/v1");
    repair.put("policy", REPAIR_POLICY);
    repair.put("maxAttempts", 1);
    repair.put("route", decision.route().name());
    repair.put("failureClass", decision.failureClass());
    repair.put("reason", decision.reason());
    repair.putObject("previousCandidate")
        .put("base", candidate.path("base").asText())
        .put("candidateTree", candidate.path("candidateTree").asText())
        .put("patchSha256", candidate.path("patchSha256").asText());
    repair.set("feedback", compactRepairFeedback(candidate, verification));
    return repair;
  }

  /** Only failed check identifiers, commands, exits and bounded output enter model context. */
  private ObjectNode compactRepairFeedback(JsonNode candidate, JsonNode verification) {
    ObjectNode feedback = json.createObjectNode();
    feedback.put("instruction", "Repair the existing frozen candidate for the original task. "
        + "Do not redesign unrelated code or change the acceptance criteria.");
    feedback.putObject("candidate")
        .put("base", candidate.path("base").asText())
        .put("candidateTree", candidate.path("candidateTree").asText())
        .put("patchSha256", candidate.path("patchSha256").asText());
    feedback.put("verificationReason", verification.path("reason").asText("VERIFICATION_FAILED"));
    ArrayNode failed = feedback.putArray("failedChecks");
    int remaining = MAX_REPAIR_EVIDENCE_CHARS;
    JsonNode checks = verification.path("checks");
    if (checks.isArray()) {
      for (JsonNode check : checks) {
        if (check.path("exitCode").asInt(0) == 0) {
          continue;
        }
        ObjectNode entry = failed.addObject();
        entry.put("id", check.path("id").asText("check"));
        entry.put("command", check.path("command").asText(""));
        entry.put("exitCode", check.path("exitCode").asInt(-1));
        String output = check.path("outputTail").asText("");
        int take = Math.min(remaining, output.length());
        entry.put("output", output.substring(Math.max(0, output.length() - take)));
        remaining -= take;
        if (remaining == 0) {
          break;
        }
      }
    }
    if (failed.isEmpty()) {
      String output = verification.path("checkerOutput").asText("") + "\n"
          + verification.path("surefireSummary").asText("");
      int take = Math.min(remaining, output.length());
      feedback.put("output", output.substring(Math.max(0, output.length() - take)));
    }
    feedback.put("evidenceLimitChars", MAX_REPAIR_EVIDENCE_CHARS);
    return feedback;
  }

  private String aggregateUsage(AgentContext context, String repairUsage, int repairCalls) {
    JsonNode initial = json.readTree(context.requireInput("usage.json").content());
    ObjectNode merged = (ObjectNode) json.readTree(repairUsage);
    int initialCalls = initial.path("provider_calls").asInt(0);
    merged.put("provider_calls", initialCalls + repairCalls);
    merged.put("initial_provider_calls", initialCalls);
    merged.put("repair_provider_calls", repairCalls);
    merged.put("repair_attempts", 1);
    return json.writeValueAsString(merged);
  }

  private String combinedSession(AgentContext context, String repairSession) {
    String initial = context.requireInput("pi-session.jsonl").content();
    return initial + (initial.endsWith("\n") || initial.isEmpty() ? "" : "\n")
        + "{\"type\":\"factory_repair_boundary\",\"attempt\":1}\n" + repairSession;
  }

  private AgentResult reverify(AgentContext context) {
    JsonNode repair = json.readTree(context.requireInput("repair.json").content());
    if (!repair.path("attempted").asBoolean(false)) {
      ObjectNode verification = (ObjectNode) json.readTree(
          context.requireInput("verification.json").content());
      String route = repair.path("route").asText("ERROR");
      if (VerificationFailureClassifier.Route.ERROR.name().equals(route)) {
        verification.put("status", "ERROR");
      } else if (VerificationFailureClassifier.Route.BLOCKED_ENVIRONMENT.name().equals(route)) {
        verification.put("status", "BLOCKED_ENVIRONMENT");
      } else if (VerificationFailureClassifier.Route.CANCELLED.name().equals(route)) {
        verification.put("status", "CANCELLED");
      }
      verification.put("failureClass", repair.path("failureClass").asText());
      verification.put("repairAttempted", false);
      return AgentResult.of("verification.json", json.writeValueAsString(verification));
    }
    JsonNode candidate = json.readTree(context.requireInput("candidate.json").content());
    if ("FAILED".equals(candidate.path("status").asText())) {
      ObjectNode result = (ObjectNode) json.readTree(
          failedVerification(candidate, context.triggerPayload()).outputs().get("verification.json"));
      if ("PATCH_REJECTED".equals(candidate.path("reason").asText())
          || "REPAIR_RUNTIME_ERROR".equals(candidate.path("reason").asText())
          || "REPAIR_PROVIDER_ERROR".equals(candidate.path("reason").asText())) {
        result.put("status", "ERROR");
      }
      result.put("repairAttempted", true);
      result.put("repairAttempt", 1);
      return AgentResult.of("verification.json", json.writeValueAsString(result));
    }
    AgentResult verified = verify(context, context.requireInput("candidate.patch").content());
    ObjectNode result = (ObjectNode) json.readTree(verified.outputs().get("verification.json"));
    result.put("repairAttempted", true);
    result.put("repairAttempt", 1);
    return AgentResult.of("verification.json", json.writeValueAsString(result));
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
        return verifyGeneric(context, fresh, patch);
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

  /**
   * Authoritative generic verification: the resolved verification plan's
   * required checks are the task's gate, executed exactly as resolved — never
   * silently replaced by a stronger or weaker hardcoded command such as a
   * blanket {@code mvn verify}. A missing plan fails loudly instead of
   * substituting a default. A failing check whose output carries the
   * Docker/Testcontainers environment-failure signature is classified as
   * BLOCKED_ENVIRONMENT, not as a coding failure.
   */
  private AgentResult verifyGeneric(AgentContext context, SandboxHandle fresh, String patch) {
    JsonNode payload = context.triggerPayload();
    JsonNode candidate = json.readTree(context.requireInput("candidate.json").content());
    JsonNode contractResolved = context.inputs().containsKey("contract.json")
        ? json.readTree(context.requireInput("contract.json").content()).path("resolvedIntent")
        : null;
    List<Check> checks = contractResolved != null && contractResolved.isObject()
        ? requiredChecks(contractResolved) : requiredChecks(payload.path("resolvedIntent"));
    ObjectNode result = json.createObjectNode();
    result.put("independent", true);
    result.put("freshCheckout", true);
    result.put("stage", "VERIFY");
    result.put("base", payload.path("baseRevision").asText());
    result.put("candidateBytes", patch.length());
    result.put("planId", payload.path("resolvedIntent").path("verificationPlan").path("id")
        .asText(""));
    CommandResult tree = sandboxes.exec(fresh, "cd repo && git write-tree", 30);
    boolean identityPass = payload.path("baseRevision").asText()
        .equalsIgnoreCase(candidate.path("base").asText())
        && ArtifactStore.sha256(patch).equals(candidate.path("patchSha256").asText());
    boolean treePass = tree.ok()
        && candidate.path("candidateTree").asText().equals(tree.stdout().trim());
    result.put("candidateTree", tree.ok() ? tree.stdout().trim() : "unknown");
    result.put("candidateTreeMatches", treePass);
    result.put("candidateIdentityMatches", identityPass);
    if (!identityPass || !treePass) {
      result.put("status", "ERROR");
      result.put("reason", !identityPass ? "CANDIDATE_IDENTITY_FAILED" : "CANDIDATE_TREE_FAILED");
      result.put("detail", "verification evidence cannot be attached to a different candidate");
      return AgentResult.of("verification.json", json.writeValueAsString(result));
    }
    if (checks.isEmpty()) {
      result.put("status", "FAIL");
      result.put("reason", "VERIFICATION_PLAN_MISSING");
      result.put("detail", "the resolved verification plan carries no required checks; "
          + "refusing to substitute a hardcoded verification command");
      return AgentResult.of("verification.json", json.writeValueAsString(result));
    }
    ArrayNode evidence = result.putArray("checks");
    boolean passed = true;
    boolean environmentFailure = false;
    for (Check check : checks) {
      CommandResult run = sandboxes.exec(fresh, "cd repo && " + check.command(),
          VERIFY_BUILD_TIMEOUT);
      ObjectNode entry = evidence.addObject();
      entry.put("id", check.id());
      entry.put("command", check.command());
      entry.put("exitCode", run.exitCode());
      entry.put("durationMs", run.durationMs());
      if (!run.ok()) {
        passed = false;
        if (environmentFailureSignature(run.stdout())
            || environmentFailureSignature(run.stderr())) {
          environmentFailure = true;
          entry.put("environmentFailure", true);
        }
        entry.put("outputTail", tail(run.stdout() + "\n" + run.stderr()));
      }
    }
    if (environmentFailure) {
      result.put("status", "BLOCKED_ENVIRONMENT");
      result.put("reason", "REQUIRED_CHECK_CANNOT_RUN");
      result.put("detail", "a required check failed with the Docker/Testcontainers "
          + "environment-failure signature in a sandbox without Docker");
    } else {
      result.put("status", passed ? "PASS" : "FAIL");
      if (!passed) {
        result.put("reason", "VERIFICATION_FAILED");
      }
    }
    return AgentResult.of("verification.json", json.writeValueAsString(result));
  }

  private AgentResult failedVerification(JsonNode candidate, JsonNode payload) {
    String reason = candidate.path("reason").asText("PI_FAILED");
    boolean blocked = "BLOCKED_ENVIRONMENT".equals(reason);
    ObjectNode result = json.createObjectNode();
    // An environment blocker from preparation is not a verification attempt
    // and must not surface as a coding/benchmark failure.
    result.put("status", blocked ? "BLOCKED_ENVIRONMENT" : "FAIL");
    result.put("independent", false);
    result.put("stage", candidate.path("stage").asText("PI_RUNTIME"));
    result.put("reason", reason);
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
      case "BLOCKED_ENVIRONMENT" -> "BLOCKED_ENVIRONMENT";
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
    if (context.inputs().containsKey("repair.json")) {
      JsonNode repair = json.readTree(context.requireInput("repair.json").content());
      ObjectNode repairSummary = result.putObject("repair");
      repairSummary.put("policy", repair.path("policy").asText(REPAIR_POLICY));
      repairSummary.put("attempted", repair.path("attempted").asBoolean(false));
      repairSummary.put("attempt", repair.path("attempt").asInt(0));
      repairSummary.put("providerCalls", repair.path("providerCalls").asInt(0));
      repairSummary.put("route", repair.path("route").asText("ERROR"));
      repairSummary.put("result", repair.path("result").asText("UNKNOWN"));
    }
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

  /** Last 8 KiB of a failed check's combined output, as verification evidence. */
  private static String tail(String value) {
    if (value == null) {
      return "";
    }
    int max = 8 * 1024;
    return value.length() <= max ? value : "[...]\n" + value.substring(value.length() - max);
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
