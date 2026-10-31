package org.folio.factory.devfactory.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.CodingServices;
import org.folio.factory.devfactory.DevFactoryHooks;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.DevFlowSettings;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.tasks.Stop;
import org.folio.factory.devfactory.tasks.TaskExecutionContext;
import org.folio.factory.devfactory.tasks.TaskGuard;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.RunProfiles;
import org.folio.factory.devfactory.runtime.TokenUsage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    private final DevFlowSettings settings;
    private final CodingServices services;
    private final FrontmatterCodec codec;
    private final AuditLog audit;
    private final DevFactoryHooks hooks;
    private final TaskGuard taskGuard;
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
        var repository = settings.repositories().repositories().get(request.repository().key());
        var repositoryProblem = findRepositoryProblem(request, repository);
        if (repositoryProblem.isPresent()) {
            return failedAttempt(new Stop("DEVELOPMENT_FAILED", repositoryProblem.get()), state);
        }
        try (var scope = services.docker().beginStep(context.executionId(), context.stepId())) {
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
        var url = settings.repositories().gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
        return new Run(context, request, repository, url, scope, taskGuard.policy().find(context.executionId()));
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
        var stop = taskStop(run, "preparation");
        if (stop.isPresent()) {
            return stop;
        }
        var environmentProblem =
                findEnvironmentProblem(run.repositoryKey(), run.baseSha(), run.request().runSettings());
        if (environmentProblem.isPresent()) {
            return Optional.of(new Stop("BLOCKED_ENVIRONMENT", environmentProblem.get()));
        }
        state.pristine = services.freezer().checkout(run.url(), run.baseSha());
        if (mode == Mode.CONTINUATION) {
            return Optional.empty();
        }
        return runBaseline(run, state).or(() -> claimPaidStart(run));
    }

    private Optional<Stop> runBaseline(Run run, RunState state) {
        var repository = run.repository();
        progress(run.context(), Map.of(
                "activity", "baseline_started",
                "command", String.join(" ", settings.runtime().command(repository.verificationPlan())),
                "image", repository.buildImage()));
        try (var workload = run.scope().createTrusted(repository.buildImage(), state.pristine,
                settings.runtime().mavenCacheVolume())) {
            var baseline = new BaselineBuild(settings.runtime()).run(workload, run.baseSha(), repository,
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
        var stop = taskStop(run, "coding");
        if (stop.isPresent()) {
            return stop;
        }
        if (run.taskRun().isEmpty() || taskGuard.policy().claimPaidStart(run.executionId())) {
            return Optional.empty();
        }
        var reason = "Paid coding was already started for task attempt " + run.taskRun().orElseThrow().attemptId()
                + "; a replayed or retried engine step cannot start a second initial"
                + " coding invocation";
        taskGuard.recordStop(run.executionId(), run.stepId(), reason);
        return Optional.of(new Stop("PAID_START_CONSUMED", reason));
    }

    private CodingOutcome code(Run run, RunState state) {
        try (var workload = run.scope().createSeeded(services.coding().image(), state.pristine,
                settings.runtime().mavenCacheVolume(), "coding")) {
            var identity = new LinkedHashMap<>(services.coding().identity(run.request().runSettings()));
            var attemptId = UUID.randomUUID().toString();
            var rate = hooks.pricing().snapshot(Objects.toString(identity.get("model"), ""));
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
        var attempt = UUID.fromString(attemptId);
        try (var transcript = hooks.transcripts().open(context.executionId(), attempt, context.stepId())) {
            return services.coding().code(workload, run.request(), stageTimeoutSeconds(run), event -> {
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
        var stop = taskStop(run, "candidate freeze");
        if (stop.isPresent()) {
            return failedAttempt(stop.get(), state);
        }
        var candidate = services.freezer().freeze(run.repositoryKey(), run.url(), run.baseSha(), state.exported, false);
        return attempt(run.request(), outcome, json.writeValueAsString(candidate), state,
                CodingDecisionArtifacts.Request.none());
    }

    /** A stopped, superseded or expired task attempt must start no further side effects. */
    private Optional<Stop> taskStop(Run run, String stage) {
        return taskGuard.stop(run.executionId(), run.stepId(), stage);
    }

    private int stageTimeoutSeconds(Run run) {
        return taskGuard.capTimeout(run.executionId(), settings.runtime().timeoutSeconds());
    }

    private Optional<String> findEnvironmentProblem(
            String repositoryKey, String frozenBaseSha, CodingRequest.RunSettings runSettings) {
        try {
            services.coding().requireConfigured(runSettings);
        } catch (RunProfiles.ProfileException exception) {
            // Written to be shown: it names the profile and the fix.
            return Optional.of(safeRedact(exception.getMessage()));
        } catch (RuntimeException exception) {
            // Other failures can contain credentials; persist only the safe operator guidance.
            return Optional.of(
                    "Coding runtime configuration is incomplete or unsupported; check Developer Flow configuration.");
        }
        try {
            hooks.repositoryPreflight().requireRepositoryReady(repositoryKey, frozenBaseSha);
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
        String redacted = services.coding().redact(text);
        return redacted == null ? text : redacted;
    }

    private static Map<String, Object> reportedMetrics(
            CodingOutcome outcome, String attemptId, Map<String, Object> rate) {
        var reported = new LinkedHashMap<String, Object>(TokenUsage.withFactoryTotals(outcome.metrics()));
        reported.put("attemptId", attemptId);
        reported.put("rate", rate);
        return reported;
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

    /** One attempt inside its engine step; {@code taskRun} is empty outside My tasks. */
    private record Run(
            AgentContext context,
            CodingRequest request,
            DevFactoryProperties.Repository repository,
            String url,
            DockerWorkloads.StepScope scope,
            Optional<TaskExecutionContext> taskRun) {

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
