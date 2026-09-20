package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.Processes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Map;

/** Baseline, one portable coding attempt, and trusted freeze only on COMPLETED. */
public class DevelopWorker implements AgentWorker {
    public static final String ID = "dev-develop";
    public static final String CANDIDATE = "dev_candidate.json";
    public static final String READINESS = "dev_readiness.json";
    public static final String OUTCOME = "dev_coding_outcome.json";

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
        this.properties = properties;
        this.runtime = runtime;
        this.docker = docker;
        this.freezer = freezer;
        this.coding = coding;
        this.codec = codec;
        this.audit = audit;
    }

    @Override public String id() { return ID; }

    @Override
    public AgentResult execute(AgentContext context) {
        var brief = codec.parse(context.requireInput(IntakeResolveWorker.TASK_BRIEF).content()).metadata();
        String state = brief.path("state").asString("");
        if (!state.equals("INTAKE_READY")) return blocked(state, "Intake is not ready", Map.of());
        CodingRequest request = json.readValue(
                context.requireInput(IntakeResolveWorker.CODING_REQUEST).content(), CodingRequest.class);
        Attempt attempt = runAttempt(context, request, true, json.createObjectNode());
        return initialResult(attempt);
    }

    Attempt retry(AgentContext context, CodingRequest request, JsonNode readiness) {
        return runAttempt(context, request, false, readiness);
    }

    private Attempt runAttempt(AgentContext context, CodingRequest request, boolean runBaseline,
                               JsonNode existingReadiness) {
        request.requireReady();
        String key = request.repository().key();
        var repository = properties.repositories().get(key);
        if (repository == null) return failedAttempt("Repository is no longer configured", existingReadiness);
        if (!repository.sourceRepo().equals(request.repository().sourceRepo())
                || !repository.baseBranch().equals(request.repository().baseBranch()))
            return failedAttempt("Coding request repository no longer matches Factory configuration", existingReadiness);
        String base = request.repository().baseSha();
        String url = properties.gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
        Path pristine = null;
        Path exported = null;
        Map<String, Object> readiness = nodeMap(existingReadiness);
        Map<String, Object> metrics = Map.of();
        try {
            pristine = freezer.checkout(url, base);
            if (runBaseline) {
                var command = runtime.command(repository.verificationPlan());
                progress(context, Map.of("activity", "baseline_started", "command", String.join(" ", command),
                        "image", repository.buildImage()));
                try (var baseline = docker.createTrusted(repository.buildImage(), pristine,
                        runtime.mavenCacheVolume())) {
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
                    var details = new java.util.LinkedHashMap<String, Object>();
                    details.put("state", result.exitCode() == 0 ? "BASELINE_PASSED" : "BASELINE_FAILED");
                    details.put("baseSha", base);
                    details.put("image", repository.buildImage());
                    details.put("plan", repository.verificationPlan());
                    details.put("command", command);
                    details.put("exitCode", result.exitCode());
                    if (!summary.isBlank()) details.put("summary", summary);
                    details.put("output", tail(MavenBaselineOutput.sanitize(result.output())));
                    readiness = Map.copyOf(details);
                    progress(context, Map.of("activity", "baseline_completed", "exitCode", result.exitCode(),
                            "summary", summary));
                    if (result.exitCode() != 0
                            && MavenBaselineOutput.isTransientDownloadFailure(result.diagnostics())) {
                        String detail = summary.isBlank() ? "Maven artifact transfer failed." : summary;
                        String reason = "Starting build hit a network/download failure; coding has not started. " + detail;
                        progress(context, Map.of("activity", "baseline_retryable_failure", "message", reason));
                        throw new StartingBuildNetworkException(reason);
                    }
                    if (result.exitCode() != 0)
                        return failedAttempt("BLOCKED_ENVIRONMENT", summary.isBlank()
                                ? "Starting build failed before model spend" : summary, readiness);
                }
            }
            coding.requireConfigured();
            exported = CandidateFreezer.temporary("factory-dev-export-");
            CodingOutcome outcome;
            try (var workload = docker.createSeeded(coding.image(), pristine, runtime.mavenCacheVolume())) {
                var identity = new java.util.LinkedHashMap<>(coding.identity());
                identity.put("activity", "coding_starting");
                progress(context, identity);
                outcome = coding.code(workload, request, runtime.timeoutSeconds(), event -> progress(context, event));
                metrics = withFactoryTokenMetrics(outcome.metrics());
                if (outcome.status() == CodingOutcome.Status.COMPLETED) {
                    workload.stop();
                    workload.export(exported);
                }
            }
            String candidate;
            if (outcome.status() == CodingOutcome.Status.COMPLETED) {
                candidate = json.writeValueAsString(freezer.freeze(key, url, base, exported));
            } else if (outcome.status() == CodingOutcome.Status.NEEDS_DECISION) {
                candidate = placeholder("NEEDS_DECISION", outcome.decision().question());
            } else {
                candidate = placeholder("DEVELOPMENT_FAILED", outcome.failure().message());
            }
            var decision = outcome.status() == CodingOutcome.Status.NEEDS_DECISION
                    ? CodingDecisionArtifacts.Request.from(outcome.decision()) : CodingDecisionArtifacts.Request.none();
            return new Attempt(request, outcome, candidate, json.writeValueAsString(readiness),
                    CodingDecisionArtifacts.renderRequest(codec, decision),
                    CodingDecisionArtifacts.renderAnswer(codec,
                            new CodingDecisionArtifacts.Answer(decision.requestId(), CodingDecisionArtifacts.UNANSWERED)),
                    metrics);
        } catch (StartingBuildNetworkException e) {
            throw e;
        } catch (RuntimeException e) {
            String reason = safeRedact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return failedAttempt("DEVELOPMENT_FAILED", "Development stopped: "
                    + reason.substring(0, Math.min(2000, reason.length())), readiness, metrics);
        } finally {
            CandidateFreezer.cleanup(pristine);
            CandidateFreezer.cleanup(exported);
        }
    }

    private AgentResult initialResult(Attempt attempt) {
        return new AgentResult(Map.of(CANDIDATE, attempt.candidate(), READINESS, attempt.readiness(),
                OUTCOME, json.writeValueAsString(attempt.outcome()),
                CodingDecisionArtifacts.REQUEST, attempt.decisionRequest(),
                CodingDecisionArtifacts.ANSWER, attempt.decisionAnswer()), attempt.metrics());
    }

    private AgentResult blocked(String state, String reason, Map<String, Object> readiness) {
        CodingOutcome outcome = CodingOutcome.failed(state, reason, Map.of());
        var decision = CodingDecisionArtifacts.Request.none();
        return new AgentResult(Map.of(CANDIDATE, placeholder(state, reason),
                READINESS, json.writeValueAsString(readiness), OUTCOME, json.writeValueAsString(outcome),
                CodingDecisionArtifacts.REQUEST, CodingDecisionArtifacts.renderRequest(codec, decision),
                CodingDecisionArtifacts.ANSWER, CodingDecisionArtifacts.renderAnswer(codec,
                        new CodingDecisionArtifacts.Answer(decision.requestId(), CodingDecisionArtifacts.UNANSWERED))),
                Map.of());
    }

    private Attempt failedAttempt(String reason, JsonNode readiness) {
        return failedAttempt(reason, nodeMap(readiness));
    }

    private Attempt failedAttempt(String reason, Map<String, Object> readiness) {
        return failedAttempt("DEVELOPMENT_FAILED", reason, readiness);
    }

    private Attempt failedAttempt(String state, String reason, Map<String, Object> readiness) {
        return failedAttempt(state, reason, readiness, Map.of());
    }

    private Attempt failedAttempt(String state, String reason, Map<String, Object> readiness,
                                  Map<String, Object> metrics) {
        CodingOutcome outcome = CodingOutcome.failed(state, reason, Map.of());
        var decision = CodingDecisionArtifacts.Request.none();
        return new Attempt(null, outcome, placeholder(state, reason),
                json.writeValueAsString(readiness), CodingDecisionArtifacts.renderRequest(codec, decision),
                CodingDecisionArtifacts.renderAnswer(codec,
                        new CodingDecisionArtifacts.Answer(decision.requestId(), CodingDecisionArtifacts.UNANSWERED)),
                metrics);
    }

    private String placeholder(String state, String reason) {
        return json.writeValueAsString(Map.of("state", state, "reason", reason == null ? "" : reason));
    }

    private void progress(AgentContext context, Map<String, Object> event) {
        if (audit == null) return;
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        event.forEach((key, value) -> {
            if (value instanceof String text) {
                text = safeRedact(text);
                safe.put(key, text.substring(0, Math.min(240, text.length())));
            } else safe.put(key, value);
        });
        try {
            audit.record(context.executionId(), org.folio.factory.core.domain.AuditEventType.RUNTIME_PROGRESS,
                    context.stepId(), safe);
        } catch (RuntimeException ignored) { }
    }

    private String safeRedact(String text) {
        String redacted = coding.redact(text);
        return redacted == null ? text : redacted;
    }

    private static Map<String, Object> withFactoryTokenMetrics(Map<String, Object> runtimeMetrics) {
        var metrics = new java.util.LinkedHashMap<>(runtimeMetrics);
        long prompt = 0;
        boolean promptPresent = false;
        for (String key : java.util.List.of("inputTokens", "cacheReadTokens", "cacheWriteTokens")) {
            if (runtimeMetrics.get(key) instanceof Number value) {
                prompt += Math.max(0, value.longValue());
                promptPresent = true;
            }
        }
        if (promptPresent) metrics.putIfAbsent("promptTokens", prompt);
        if (runtimeMetrics.get("outputTokens") instanceof Number value)
            metrics.putIfAbsent("completionTokens", Math.max(0, value.longValue()));
        return Map.copyOf(metrics);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        return json.convertValue(node, Map.class);
    }

    private static String tail(String value) { return value.substring(Math.max(0, value.length() - 16000)); }

    record Attempt(CodingRequest request, CodingOutcome outcome, String candidate, String readiness,
                   String decisionRequest, String decisionAnswer, Map<String, Object> metrics) { }

    /** Only a failed pre-runtime build may consume the engine retry budget. */
    private static final class StartingBuildNetworkException extends AgentExecutionException {
        private StartingBuildNetworkException(String message) { super(message); }
    }
}
