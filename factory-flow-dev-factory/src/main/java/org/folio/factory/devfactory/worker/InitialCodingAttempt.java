package org.folio.factory.devfactory.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.feedback.AttemptPricing;
import org.folio.factory.devfactory.pilot.AttemptDeadline;
import org.folio.factory.devfactory.pilot.PilotAttemptPolicy;
import org.folio.factory.devfactory.pilot.PilotExecutionContext;
import org.folio.factory.devfactory.pilot.PilotStops;
import org.folio.factory.devfactory.repository.SelectedRepositoryPreflight;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.CodingTranscripts;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.RunProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
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
@Slf4j
@RequiredArgsConstructor
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
    private final CodingTranscripts transcripts;
    private final JsonMapper json = JsonMapper.builder().build();

    public Attempt start(AgentContext context, CodingRequest request) {
        return runAttempt(context, request, Mode.START, json.createObjectNode());
    }

    public Attempt retry(AgentContext context, CodingRequest request, JsonNode readiness) {
        return runAttempt(context, request, Mode.CONTINUATION, readiness);
    }

    private Attempt runAttempt(AgentContext context, CodingRequest request, Mode mode, JsonNode existingReadiness) {
        request.requireReady();
        var state = new RunState(nodeMap(existingReadiness));
        var repository = properties.repositories().get(request.repository().key());
        var repositoryProblem = findRepositoryProblem(request, repository);
        if (repositoryProblem.isPresent()) {
            return failedAttempt(new Stop("DEVELOPMENT_FAILED", repositoryProblem.get()), state);
        }
        try (var scope = docker.beginStep(context.executionId(), context.stepId())) {
            // beginStep reconciles stale workloads before preflight on a replay.
            return develop(newRun(context, request, repository, scope), mode, state);
        } catch (StartingBuildNetworkException | DockerWorkloads.ActiveStepException e) {
            throw e;
        } catch (RuntimeException e) {
            return failedAttempt(developmentStopped(e), state);
        } finally {
            CandidateFreezer.cleanup(state.pristine);
            CandidateFreezer.cleanup(state.exported);
        }
    }

    private static Optional<String> findRepositoryProblem(
            CodingRequest request, DevFactoryProperties.Repository repository) {
        if (repository == null) {
            return Optional.of("Repository is no longer configured");
        }
        var requested = request.repository();
        if (!repository.sourceRepo().equals(requested.sourceRepo())
                || !repository.baseBranch().equals(requested.baseBranch())) {
            return Optional.of("Coding request repository no longer matches Factory configuration");
        }
        return Optional.empty();
    }

    private Run newRun(AgentContext context, CodingRequest request, DevFactoryProperties.Repository repository,
                       DockerWorkloads.StepScope scope) {
        var url = properties.gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
        var pilot = pilotPolicy.find(context.executionId());
        var deadline = pilot.isPresent()
                ? pilotPolicy.deadline(context.executionId())
                : Optional.<AttemptDeadline>empty();
        return new Run(context, request, repository, url, scope, pilot, deadline);
    }

    private Attempt develop(Run run, Mode mode, RunState state) {
        var stop = prepare(run, mode, state);
        if (stop.isPresent()) {
            return failedAttempt(stop.get(), state);
        }
        state.exported = CandidateFreezer.temporary("factory-dev-export-");
        var outcome = code(run, state);
        return toAttempt(run, state, outcome);
    }

    private Optional<Stop> prepare(Run run, Mode mode, RunState state) {
        var stop = pilotStop(run, "preparation");
        if (stop.isPresent()) {
            return stop;
        }
        var environmentProblem =
                findEnvironmentProblem(run.repositoryKey(), run.baseSha(), run.request().runSettings());
        if (environmentProblem.isPresent()) {
            return Optional.of(new Stop("BLOCKED_ENVIRONMENT", environmentProblem.get()));
        }
        state.pristine = freezer.checkout(run.url(), run.baseSha());
        if (mode == Mode.CONTINUATION) {
            return Optional.empty();
        }
        return runBaseline(run, state).or(() -> claimPaidStart(run));
    }

    private Optional<Stop> runBaseline(Run run, RunState state) {
        var repository = run.repository();
        progress(run.context(), Map.of(
                "activity", "baseline_started",
                "command", String.join(" ", runtime.command(repository.verificationPlan())),
                "image", repository.buildImage()));
        try (var workload = run.scope().createTrusted(repository.buildImage(), state.pristine,
                runtime.mavenCacheVolume())) {
            var baseline = new BaselineBuild(runtime).run(workload, run.baseSha(), repository,
                    stageTimeoutSeconds(run), event -> progress(run.context(), event));
            state.readiness = baseline.readiness();
            progress(run.context(), Map.of(
                    "activity", "baseline_completed",
                    "exitCode", baseline.process().exitCode(),
                    "summary", baseline.summary()));
            return baselineStop(run.context(), baseline);
        }
    }

    private Optional<Stop> baselineStop(AgentContext context, BaselineBuild.Result baseline) {
        if (baseline.process().exitCode() == 0) {
            return Optional.empty();
        }
        var summary = baseline.summary();
        if (MavenBaselineOutput.isTransientDownloadFailure(baseline.process().diagnostics())) {
            var detail = summary.isBlank() ? "Maven artifact transfer failed." : summary;
            var reason = "Starting build hit a network/download failure; coding has not started. " + detail;
            progress(context, Map.of("activity", "baseline_retryable_failure", "message", reason));
            throw new StartingBuildNetworkException(reason);
        }
        return Optional.of(new Stop("BLOCKED_ENVIRONMENT",
                summary.isBlank() ? "Starting build failed before model spend" : summary));
    }

    private Optional<Stop> claimPaidStart(Run run) {
        var stop = pilotStop(run, "coding");
        if (stop.isPresent()) {
            return stop;
        }
        if (run.pilot().isEmpty() || pilotPolicy.claimPaidStart(run.executionId())) {
            return Optional.empty();
        }
        var reason = "Paid coding was already started for pilot attempt " + run.pilot().orElseThrow().attemptId()
                + "; a replayed or retried engine step cannot start a second initial"
                + " coding invocation";
        pilotPolicy.recordStop(run.executionId(), run.stepId(), reason);
        return Optional.of(new Stop("PAID_START_CONSUMED", reason));
    }

    private CodingOutcome code(Run run, RunState state) {
        try (var workload = run.scope().createSeeded(coding.image(), state.pristine, runtime.mavenCacheVolume(),
                "coding")) {
            var identity = new LinkedHashMap<>(coding.identity(run.request().runSettings()));
            var attemptId = UUID.randomUUID().toString();
            var rate = pricing.snapshot(Objects.toString(identity.get("model"), ""));
            identity.put("activity", "coding_starting");
            identity.put("attemptId", attemptId);
            identity.put("rate", rate);
            progress(run.context(), identity);
            var outcome = callRuntime(run, workload, attemptId);
            state.metrics = reportedMetrics(outcome, attemptId, rate);
            if (outcome.status() == CodingOutcome.Status.COMPLETED) {
                workload.stop();
                workload.export(state.exported);
            }
            return outcome;
        }
    }

    private CodingOutcome callRuntime(Run run, DockerWorkloads.Workload workload, String attemptId) {
        var context = run.context();
        try (var transcript = transcripts.open(context.executionId(), UUID.fromString(attemptId), context.stepId())) {
            return coding.code(workload, run.request(), stageTimeoutSeconds(run), event -> {
                var detail = new LinkedHashMap<String, Object>(event);
                detail.put("attemptId", attemptId);
                // A failed write reaches the runtime, which logs it once and notes it in the transcript.
                recordProgress(context, detail);
            }, transcript);
        }
    }

    private Attempt toAttempt(Run run, RunState state, CodingOutcome outcome) {
        return switch (outcome.status()) {
            case COMPLETED -> completedAttempt(run, state, outcome);
            case NEEDS_DECISION -> attempt(run.request(), outcome,
                    placeholder("NEEDS_DECISION", outcome.decision().question()), state,
                    CodingDecisionArtifacts.Request.from(outcome.decision()));
            case FAILED -> attempt(run.request(), outcome,
                    placeholder("DEVELOPMENT_FAILED", outcome.failure().message()), state,
                    CodingDecisionArtifacts.Request.none());
        };
    }

    private Attempt completedAttempt(Run run, RunState state, CodingOutcome outcome) {
        var stop = pilotStop(run, "candidate freeze");
        if (stop.isPresent()) {
            return failedAttempt(stop.get(), state);
        }
        var candidate = freezer.freeze(run.repositoryKey(), run.url(), run.baseSha(), state.exported);
        return attempt(run.request(), outcome, json.writeValueAsString(candidate), state,
                CodingDecisionArtifacts.Request.none());
    }

    private Optional<Stop> pilotStop(Run run, String stage) {
        var stop = pilotGuardProblem(run, stage);
        stop.ifPresent(found -> pilotPolicy.recordStop(run.executionId(), run.stepId(), found.reason()));
        return stop;
    }

    /**
     * A stopped, superseded or expired pilot attempt must start no further side
     * effects. Legacy executions without pilot policy never produce a guard stop.
     */
    private Optional<Stop> pilotGuardProblem(Run run, String stage) {
        if (run.pilot().isEmpty()) {
            return Optional.empty();
        }
        if (!pilotPolicy.ownsActiveSlot(run.executionId())) {
            return Optional.of(new Stop(PilotStops.INSPECTION_STATE,
                    "the pilot attempt no longer owns the installation's active slot;"
                            + " a stopped, superseded or released attempt must not continue"));
        }
        if (run.deadline().filter(budget -> budget.expired(Clock.systemUTC())).isPresent()) {
            return Optional.of(new Stop(PilotStops.DEADLINE_STATE,
                    "the whole-attempt deadline expired before " + stage
                            + "; queue and human-review waiting are excluded from the budget"));
        }
        return Optional.empty();
    }

    private int stageTimeoutSeconds(Run run) {
        var perOperationSeconds = runtime.timeoutSeconds();
        return run.deadline()
                .map(budget -> budget.capSeconds(Clock.systemUTC(), perOperationSeconds))
                .orElse(perOperationSeconds);
    }

    private Optional<String> findEnvironmentProblem(
            String repositoryKey, String frozenBaseSha, CodingRequest.RunSettings settings) {
        try {
            coding.requireConfigured(settings);
        } catch (RunProfiles.ProfileException exception) {
            // Written to be shown: it names the profile and the fix.
            return Optional.of(safeRedact(exception.getMessage()));
        } catch (RuntimeException exception) {
            // Other failures can contain credentials; persist only the safe operator guidance.
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

    private Stop developmentStopped(RuntimeException e) {
        var reason = safeRedact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return new Stop("DEVELOPMENT_FAILED",
                "Development stopped: " + reason.substring(0, Math.min(2000, reason.length())));
    }

    private Attempt failedAttempt(Stop stop, RunState state) {
        var outcome = CodingOutcome.failed(stop.state(), stop.reason(), Map.of());
        return attempt(null, outcome, placeholder(stop.state(), stop.reason()), state,
                CodingDecisionArtifacts.Request.none());
    }

    private Attempt attempt(CodingRequest request, CodingOutcome outcome, String candidate, RunState state,
                            CodingDecisionArtifacts.Request decision) {
        var answer = new CodingDecisionArtifacts.Answer(decision.requestId(), CodingDecisionArtifacts.UNANSWERED);
        return new Attempt(request, outcome, candidate, json.writeValueAsString(state.readiness),
                CodingDecisionArtifacts.renderRequest(codec, decision),
                CodingDecisionArtifacts.renderAnswer(codec, answer),
                state.metrics);
    }

    private String placeholder(String state, String reason) {
        return json.writeValueAsString(
                Map.of("state", state, "reason", reason == null ? "" : reason));
    }

    private void progress(AgentContext context, Map<String, Object> event) {
        try {
            recordProgress(context, event);
        } catch (RuntimeException e) {
            // Best-effort progress must not change the coding result.
            log.warn("Could not record runtime activity for execution {}: {}", context.executionId(), e.getMessage());
        }
    }

    private void recordProgress(AgentContext context, Map<String, Object> event) {
        if (audit == null) return;
        Map<String, Object> safe = new LinkedHashMap<>();
        event.forEach(
                (key, value) -> {
                    if (value instanceof String text) {
                        text = safeRedact(text);
                        safe.put(key, text.substring(0, Math.min(240, text.length())));
                    } else safe.put(key, value);
                });
        audit.record(context.executionId(), AuditEventType.RUNTIME_PROGRESS, context.stepId(), safe);
    }

    private String safeRedact(String text) {
        String redacted = coding.redact(text);
        return redacted == null ? text : redacted;
    }

    private static Map<String, Object> reportedMetrics(
            CodingOutcome outcome, String attemptId, Map<String, Object> rate) {
        var reported = new LinkedHashMap<String, Object>(withFactoryTokenMetrics(outcome.metrics()));
        reported.put("attemptId", attemptId);
        reported.put("rate", rate);
        return reported;
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

    public record Attempt(
            CodingRequest request,
            CodingOutcome outcome,
            String candidate,
            String readiness,
            String decisionRequest,
            String decisionAnswer,
            Map<String, Object> metrics) {}

    /** A start runs the starting build and claims the paid start; its continuation keeps the start's readiness. */
    private enum Mode {
        START,
        CONTINUATION
    }

    /**
     * The state and reason that end an attempt without a candidate. Pilot stops are classified like
     * VerifyWorker and DeliveryWorker.
     */
    private record Stop(String state, String reason) {
    }

    /** One attempt inside its engine step. A pilot deadline exists only for a pilot execution. */
    private record Run(
            AgentContext context,
            CodingRequest request,
            DevFactoryProperties.Repository repository,
            String url,
            DockerWorkloads.StepScope scope,
            Optional<PilotExecutionContext> pilot,
            Optional<AttemptDeadline> deadline) {

        UUID executionId() {
            return context.executionId();
        }

        String stepId() {
            return context.stepId();
        }

        String repositoryKey() {
            return request.repository().key();
        }

        String baseSha() {
            return request.repository().baseSha();
        }
    }

    /**
     * What the steps leave for the catch and finally blocks of runAttempt: a failure still reports the
     * latest readiness and metrics, and the temporary checkouts are always removed.
     */
    private static final class RunState {
        private Map<String, Object> readiness;
        private Map<String, Object> metrics = Map.of();
        private Path pristine;
        private Path exported;

        private RunState(Map<String, Object> readiness) {
            this.readiness = readiness;
        }
    }

    /** Only a failed pre-runtime build may consume the engine retry budget. */
    private static final class StartingBuildNetworkException extends AgentExecutionException {
        private StartingBuildNetworkException(String message) {
            super(message);
        }
    }
}
