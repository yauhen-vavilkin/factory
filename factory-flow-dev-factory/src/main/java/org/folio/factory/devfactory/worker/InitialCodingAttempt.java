package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.feedback.AttemptPricing;
import org.folio.factory.devfactory.pilot.PilotAttemptPolicy;
import org.folio.factory.devfactory.pilot.PilotExecutionContext;
import org.folio.factory.devfactory.repository.SelectedRepositoryPreflight;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes initial-development attempts and their explicit clarification continuation. Unlike PR
 * feedback, only completed attempts export a candidate.
 */
public final class InitialCodingAttempt {
    private final DevFactoryProperties properties;
    private final DevRuntimeProperties runtime;
    private final DockerWorkloads docker;
    private final CandidateFreezer freezer;
    private final CodingRuntime coding;
    private final FrontmatterCodec codec;
    private final AuditLog audit;
    private final AttemptPricing pricing;
    private final SelectedRepositoryPreflight repositoryPreflight;
    private final PilotAttemptPolicy pilotPolicy;
    private final JsonMapper json = JsonMapper.builder().build();

    public InitialCodingAttempt(
            DevFactoryProperties properties,
            DevRuntimeProperties runtime,
            DockerWorkloads docker,
            CandidateFreezer freezer,
            CodingRuntime coding,
            FrontmatterCodec codec,
            AuditLog audit,
            AttemptPricing pricing) {
        this(properties, runtime, docker, freezer, coding, codec, audit, pricing,
                SelectedRepositoryPreflight.none());
    }

    public InitialCodingAttempt(
            DevFactoryProperties properties,
            DevRuntimeProperties runtime,
            DockerWorkloads docker,
            CandidateFreezer freezer,
            CodingRuntime coding,
            FrontmatterCodec codec,
            AuditLog audit,
            AttemptPricing pricing,
            SelectedRepositoryPreflight repositoryPreflight) {
        this(properties, runtime, docker, freezer, coding, codec, audit, pricing,
                repositoryPreflight, PilotAttemptPolicy.none());
    }

    public InitialCodingAttempt(
            DevFactoryProperties properties,
            DevRuntimeProperties runtime,
            DockerWorkloads docker,
            CandidateFreezer freezer,
            CodingRuntime coding,
            FrontmatterCodec codec,
            AuditLog audit,
            AttemptPricing pricing,
            SelectedRepositoryPreflight repositoryPreflight,
            PilotAttemptPolicy pilotPolicy) {
        this.properties = properties;
        this.runtime = runtime;
        this.docker = docker;
        this.freezer = freezer;
        this.coding = coding;
        this.codec = codec;
        this.audit = audit;
        this.pricing = pricing;
        this.repositoryPreflight = repositoryPreflight;
        this.pilotPolicy = pilotPolicy;
    }

    public Attempt start(AgentContext context, CodingRequest request) {
        return runAttempt(context, request, true, json.createObjectNode(), true);
    }

    public Attempt retry(AgentContext context, CodingRequest request, JsonNode readiness) {
        return runAttempt(context, request, false, readiness, false);
    }

    private Attempt runAttempt(
            AgentContext context,
            CodingRequest request,
            boolean runBaseline,
            JsonNode existingReadiness,
            boolean claimPilotPaidStart) {
        request.requireReady();
        String repositoryKey = request.repository().key();
        var repository = properties.repositories().get(repositoryKey);
        if (repository == null) {
            return failedAttempt("Repository is no longer configured", existingReadiness);
        }
        if (!repository.sourceRepo().equals(request.repository().sourceRepo())
                || !repository.baseBranch().equals(request.repository().baseBranch())) {
            return failedAttempt(
                    "Coding request repository no longer matches Factory configuration",
                    existingReadiness);
        }
        String frozenBaseSha = request.repository().baseSha();
        String url = properties.gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
        Path pristine = null;
        Path exported = null;
        Map<String, Object> readiness = nodeMap(existingReadiness);
        Map<String, Object> metrics = Map.of();
        try (var stepScope = docker.beginStep(context.executionId(), context.stepId())) {
            // beginStep reconciles stale workloads before preflight on a replay.
            Optional<String> environmentProblem = findEnvironmentProblem(repositoryKey, frozenBaseSha);
            if (environmentProblem.isPresent()) {
                return failedAttempt("BLOCKED_ENVIRONMENT", environmentProblem.get(), readiness);
            }
            pristine = freezer.checkout(url, frozenBaseSha);
            if (runBaseline) {
                progress(
                        context,
                        Map.of(
                                "activity",
                                "baseline_started",
                                "command",
                                String.join(" ", runtime.command(repository.verificationPlan())),
                                "image",
                                repository.buildImage()));
                try (var workload =
                        stepScope.createTrusted(
                                repository.buildImage(), pristine, runtime.mavenCacheVolume())) {
                    var baseline =
                            new BaselineBuild(runtime)
                                    .run(
                                            workload,
                                            frozenBaseSha,
                                            repository,
                                            event -> progress(context, event));
                    readiness = baseline.readiness();
                    String summary = baseline.summary();
                    progress(
                            context,
                            Map.of(
                                    "activity",
                                    "baseline_completed",
                                    "exitCode",
                                    baseline.process().exitCode(),
                                    "summary",
                                    summary));
                    if (baseline.process().exitCode() != 0
                            && MavenBaselineOutput.isTransientDownloadFailure(
                                    baseline.process().diagnostics())) {
                        String detail =
                                summary.isBlank() ? "Maven artifact transfer failed." : summary;
                        String reason =
                                "Starting build hit a network/download failure; coding has not"
                                    + " started. "
                                        + detail;
                        progress(
                                context,
                                Map.of(
                                        "activity",
                                        "baseline_retryable_failure",
                                        "message",
                                        reason));
                        throw new StartingBuildNetworkException(reason);
                    }
                    if (baseline.process().exitCode() != 0)
                        return failedAttempt(
                                "BLOCKED_ENVIRONMENT",
                                summary.isBlank()
                                        ? "Starting build failed before model spend"
                                        : summary,
                                readiness);
                }
            }
            if (claimPilotPaidStart) {
                Optional<PilotExecutionContext> pilot = pilotPolicy.find(context.executionId());
                if (pilot.isPresent() && !pilotPolicy.claimPaidStart(context.executionId())) {
                    String reason =
                            "Paid coding was already started for pilot attempt " + pilot.get().attemptId()
                                    + "; a replayed or retried engine step cannot start a second initial"
                                    + " coding invocation";
                    pilotPolicy.recordStop(context.executionId(), context.stepId(), reason);
                    return failedAttempt("PAID_START_CONSUMED", reason, readiness);
                }
            }
            exported = CandidateFreezer.temporary("factory-dev-export-");
            CodingOutcome outcome;
            try (var workload =
                    stepScope.createSeeded(
                            coding.image(), pristine, runtime.mavenCacheVolume(), "coding")) {
                var identity = new LinkedHashMap<>(coding.identity());
                String attemptId = UUID.randomUUID().toString();
                var rate = pricing.snapshot(Objects.toString(identity.get("model"), ""));
                identity.put("activity", "coding_starting");
                identity.put("attemptId", attemptId);
                identity.put("rate", rate);
                progress(context, identity);
                outcome =
                        coding.code(
                                workload,
                                request,
                                runtime.timeoutSeconds(),
                                event -> {
                                    var detail = new LinkedHashMap<String, Object>(event);
                                    detail.put("attemptId", attemptId);
                                    progress(context, detail);
                                });
                var reported =
                        new LinkedHashMap<String, Object>(
                                withFactoryTokenMetrics(outcome.metrics()));
                reported.put("attemptId", attemptId);
                reported.put("rate", rate);
                metrics = reported;
                if (outcome.status() == CodingOutcome.Status.COMPLETED) {
                    workload.stop();
                    workload.export(exported);
                }
            }
            String candidate;
            if (outcome.status() == CodingOutcome.Status.COMPLETED) {
                candidate = json.writeValueAsString(
                        freezer.freeze(repositoryKey, url, frozenBaseSha, exported));
            } else if (outcome.status() == CodingOutcome.Status.NEEDS_DECISION) {
                candidate = placeholder("NEEDS_DECISION", outcome.decision().question());
            } else {
                candidate = placeholder("DEVELOPMENT_FAILED", outcome.failure().message());
            }
            var decision =
                    outcome.status() == CodingOutcome.Status.NEEDS_DECISION
                            ? CodingDecisionArtifacts.Request.from(outcome.decision())
                            : CodingDecisionArtifacts.Request.none();
            return new Attempt(
                    request,
                    outcome,
                    candidate,
                    json.writeValueAsString(readiness),
                    CodingDecisionArtifacts.renderRequest(codec, decision),
                    CodingDecisionArtifacts.renderAnswer(
                            codec,
                            new CodingDecisionArtifacts.Answer(
                                    decision.requestId(), CodingDecisionArtifacts.UNANSWERED)),
                    metrics);
        } catch (StartingBuildNetworkException e) {
            throw e;
        } catch (DockerWorkloads.ActiveStepException e) {
            throw e;
        } catch (RuntimeException e) {
            String reason =
                    safeRedact(
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return failedAttempt(
                    "DEVELOPMENT_FAILED",
                    "Development stopped: " + reason.substring(0, Math.min(2000, reason.length())),
                    readiness,
                    metrics);
        } finally {
            CandidateFreezer.cleanup(pristine);
            CandidateFreezer.cleanup(exported);
        }
    }

    private Optional<String> findEnvironmentProblem(String repositoryKey, String frozenBaseSha) {
        try {
            coding.requireConfigured();
        } catch (RuntimeException exception) {
            // Provider failures can contain credentials; persist only the safe operator guidance.
            return Optional.of(
                    "Coding runtime configuration is incomplete or unsupported; check Developer Flow configuration.");
        }
        try {
            repositoryPreflight.requireRepositoryReady(repositoryKey, frozenBaseSha);
        } catch (RuntimeException exception) {
            return Optional.of(
                    "Selected repository configuration or access is not ready; check Developer Flow configuration.");
        }
        return Optional.empty();
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

    private Attempt failedAttempt(
            String state,
            String reason,
            Map<String, Object> readiness,
            Map<String, Object> metrics) {
        CodingOutcome outcome = CodingOutcome.failed(state, reason, Map.of());
        var decision = CodingDecisionArtifacts.Request.none();
        return new Attempt(
                null,
                outcome,
                placeholder(state, reason),
                json.writeValueAsString(readiness),
                CodingDecisionArtifacts.renderRequest(codec, decision),
                CodingDecisionArtifacts.renderAnswer(
                        codec,
                        new CodingDecisionArtifacts.Answer(
                                decision.requestId(), CodingDecisionArtifacts.UNANSWERED)),
                metrics);
    }

    private String placeholder(String state, String reason) {
        return json.writeValueAsString(
                Map.of("state", state, "reason", reason == null ? "" : reason));
    }

    private void progress(AgentContext context, Map<String, Object> event) {
        if (audit == null) return;
        Map<String, Object> safe = new LinkedHashMap<>();
        event.forEach(
                (key, value) -> {
                    if (value instanceof String text) {
                        text = safeRedact(text);
                        safe.put(key, text.substring(0, Math.min(240, text.length())));
                    } else safe.put(key, value);
                });
        try {
            audit.record(
                    context.executionId(), AuditEventType.RUNTIME_PROGRESS, context.stepId(), safe);
        } catch (RuntimeException ignored) {
            // Best-effort progress must not change the coding result.
        }
    }

    private String safeRedact(String text) {
        String redacted = coding.redact(text);
        return redacted == null ? text : redacted;
    }

    private static Map<String, Object> withFactoryTokenMetrics(Map<String, Object> runtimeMetrics) {
        var metrics = new LinkedHashMap<>(runtimeMetrics);
        long prompt = 0;
        boolean promptPresent = false;
        for (String key : List.of("inputTokens", "cacheReadTokens", "cacheWriteTokens")) {
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

    private static String tail(String value) {
        return value.substring(Math.max(0, value.length() - 16000));
    }

    public record Attempt(
            CodingRequest request,
            CodingOutcome outcome,
            String candidate,
            String readiness,
            String decisionRequest,
            String decisionAnswer,
            Map<String, Object> metrics) {}

    /** Only a failed pre-runtime build may consume the engine retry budget. */
    private static final class StartingBuildNetworkException extends AgentExecutionException {
        private StartingBuildNetworkException(String message) {
            super(message);
        }
    }
}
