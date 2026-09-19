package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.Processes;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.Map;

/** Baseline first, one coding attempt, then trusted freeze. No provider retry in the flow. */
public class DevelopWorker implements AgentWorker {
    public static final String CANDIDATE = "dev_candidate.json";
    public static final String READINESS = "dev_readiness.json";
    private final DevFactoryProperties properties;
    private final DevRuntimeProperties runtime;
    private final DockerWorkloads docker;
    private final CandidateFreezer freezer;
    private final CodingRuntime coding;
    private final FrontmatterCodec codec;
    private final org.folio.factory.core.service.AuditLog audit;
    private final JsonMapper json = JsonMapper.builder().build();
    public DevelopWorker(DevFactoryProperties properties, DevRuntimeProperties runtime, DockerWorkloads docker,
                         CandidateFreezer freezer, CodingRuntime coding, FrontmatterCodec codec) {
        this(properties, runtime, docker, freezer, coding, codec, null);
    }
    public DevelopWorker(DevFactoryProperties properties, DevRuntimeProperties runtime, DockerWorkloads docker,
                         CandidateFreezer freezer, CodingRuntime coding, FrontmatterCodec codec,
                         org.folio.factory.core.service.AuditLog audit) {
        this.properties = properties; this.runtime = runtime; this.docker = docker;
        this.freezer = freezer; this.coding = coding; this.codec = codec;
        this.audit = audit;
    }
    @Override public String id() { return "dev-develop"; }
    @Override public AgentResult execute(AgentContext context) {
        String task = context.requireInput(IntakeResolveWorker.TASK_BRIEF).content();
        var brief = codec.parse(task).metadata();
        String state = brief.path("state").asString("");
        if (!state.equals("INTAKE_READY")) return blocked(state, "Intake is not ready", Map.of());
        String key = brief.path("repository").path("key").asString("");
        var repo = properties.repositories().get(key);
        if (repo == null) return blocked("BLOCKED_ENVIRONMENT", "Repository is no longer configured", Map.of());
        String base = brief.path("repository").path("base_sha").asString("");
        String url = properties.gitBaseUrl() + "/" + repo.sourceRepo() + ".git";
        Path pristine = null;
        Path exported = null;
        Map<String, Object> readiness = Map.of();
        Map<String, Object> metrics = Map.of();
        try {
            var command = runtime.command(repo.verificationPlan());
            progress(context, Map.of("activity", "baseline_started", "command", String.join(" ", command), "image", repo.buildImage()));
            pristine = freezer.checkout(url, base);
            try (var baseline = docker.createTrusted(repo.buildImage(), pristine, runtime.mavenCacheVolume())) {
                var observer = new MavenBaselineOutput(line -> progress(context,
                        Map.of("activity", "baseline_progress", "message", line)));
                var heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual().name("factory-maven-progress-", 0).factory());
                Processes.Result result;
                try {
                    heartbeat.scheduleAtFixedRate(observer::heartbeat, 15, 30,
                            java.util.concurrent.TimeUnit.SECONDS);
                    result = baseline.execute(command, runtime.timeoutSeconds(), Processes.OUTPUT_LIMIT, observer);
                } finally {
                    observer.close();
                    heartbeat.shutdownNow();
                }
                String summary = MavenBaselineOutput.failureSummary(result.diagnostics()).orElse("");
                var readinessDetails = new java.util.LinkedHashMap<String, Object>();
                readinessDetails.put("state", result.exitCode() == 0 ? "BASELINE_PASSED" : "BASELINE_FAILED");
                readinessDetails.put("baseSha", base);
                readinessDetails.put("image", repo.buildImage());
                readinessDetails.put("plan", repo.verificationPlan());
                readinessDetails.put("command", command);
                readinessDetails.put("exitCode", result.exitCode());
                if (!summary.isBlank()) readinessDetails.put("summary", summary);
                readinessDetails.put("output", tail(MavenBaselineOutput.sanitize(result.output())));
                readiness = Map.copyOf(readinessDetails);
                progress(context, Map.of("activity", "baseline_completed", "exitCode", result.exitCode(),
                        "summary", summary));
                if (result.exitCode() != 0 && MavenBaselineOutput.isTransientDownloadFailure(result.diagnostics())) {
                    // Maven 3 does not cache transfer errors (only not-found results). The next
                    // engine attempt uses this same persistent repository without -U or eviction.
                    String detail = summary.isBlank() ? "Maven artifact transfer failed." : summary;
                    String reason = "Starting build hit a network/download failure; Pi has not started. " + detail;
                    progress(context, Map.of("activity", "baseline_retryable_failure", "message", reason));
                    throw new StartingBuildNetworkException(reason);
                }
                if (result.exitCode() != 0) return blocked("BLOCKED_ENVIRONMENT",
                        summary.isBlank() ? "Starting build failed before model spend" : summary, readiness);
            }
            runtime.coding().requireConfigured();
            exported = CandidateFreezer.temporary("factory-dev-export-");
            try (var workload = docker.createSeeded(runtime.coding().image(), pristine, runtime.mavenCacheVolume())) {
                progress(context, Map.of("activity", "pi_starting", "image", runtime.coding().image(),
                        "provider", runtime.coding().provider(), "model", runtime.coding().model()));
                var codingResult = coding.code(workload, "Implement this task in /workspace. Inspect, understand, plan, edit, run targeted checks, debug and self-review. "
                        + "Keep changes focused. Do not push or access Jira/GitHub writes. Do not alter .git or generate final verification receipts. "
                        + "Trusted intake has already approved this task for implementation; its Jira status is not an unresolved requirement. "
                        + "For this first demo, do not add or run integration checks that require nested Docker/Testcontainers; add focused unit coverage where useful. "
                        + "If a material requirement is unresolved, stop and return FACTORY_DECISION_REQUIRED with one concrete question and 2-4 options. "
                        + "Your self-checks are diagnostic; Factory independently verifies the final frozen tree.\n\n" + task,
                        runtime.timeoutSeconds(), event -> progress(context, event));
                metrics = codingResult.metrics();
                workload.stop();
                workload.export(exported);
            }
            var candidate = freezer.freeze(key, url, base, exported);
            return new AgentResult(Map.of(CANDIDATE, json.writeValueAsString(candidate), READINESS, json.writeValueAsString(readiness)), metrics);
        } catch (StartingBuildNetworkException e) {
            throw e;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String secret = runtime.coding().apiKey();
            if (secret != null && !secret.isBlank()) reason = reason.replace(secret, "[REDACTED]");
            return new AgentResult(blocked("DEVELOPMENT_FAILED", "Development stopped: " + reason.substring(0, Math.min(2000, reason.length())), readiness).outputs(), metrics);
        } finally {
            CandidateFreezer.cleanup(pristine);
            CandidateFreezer.cleanup(exported);
        }
    }
    private void progress(AgentContext context, Map<String, Object> event) {
        if (audit == null) return;
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        event.forEach((key, value) -> {
            if (value instanceof String text) {
                String secret = runtime.coding().apiKey();
                if (secret != null && !secret.isBlank()) text = text.replace(secret, "[REDACTED]");
                safe.put(key, text.substring(0, Math.min(240, text.length())));
            } else safe.put(key, value);
        });
        try { audit.record(context.executionId(), org.folio.factory.core.domain.AuditEventType.RUNTIME_PROGRESS, context.stepId(), safe); }
        catch (RuntimeException ignored) { /* Progress does not authorize or reject a candidate. */ }
    }
    private AgentResult blocked(String state, String reason, Map<String, Object> readiness) {
        return new AgentResult(Map.of(CANDIDATE, json.writeValueAsString(Map.of("state", state, "reason", reason)),
                READINESS, json.writeValueAsString(readiness)), Map.of());
    }
    private static String tail(String value) { return value.substring(Math.max(0, value.length() - 16000)); }
    /** Only a failed pre-Pi build may consume the engine retry budget. */
    private static final class StartingBuildNetworkException extends AgentExecutionException {
        private StartingBuildNetworkException(String message) { super(message); }
    }
}
