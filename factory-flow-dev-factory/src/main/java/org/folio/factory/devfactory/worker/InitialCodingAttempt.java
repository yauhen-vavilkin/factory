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
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.decision.CodingDecisionArtifacts;
import org.folio.factory.devfactory.tasks.Stop;
import org.folio.factory.devfactory.tasks.TaskGuard;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.PreparedWorkspace;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.TokenUsage;
import org.folio.factory.devfactory.runtime.Toolchain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.LinkOption;
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
        return runAttempt(context, request, Mode.START, json.createObjectNode(), null);
    }

    /**
     * {@code lockfile} is the {@code dev_lockfile.json} of the starting build that already ran; null for an
     * execution that started before Factory wrote one.
     */
    public Attempt retry(AgentContext context, CodingRequest request, JsonNode readiness, String lockfile) {
        return runAttempt(context, request, Mode.CONTINUATION, readiness, lockfile);
    }

    private Attempt runAttempt(AgentContext context, CodingRequest request, Mode mode, JsonNode existingReadiness,
                               String lockfile) {
        request.requireReady();
        var state = new RunState(nodeMap(existingReadiness), lockfile != null ? lockfile
                : PinnedLockfile.none("NOT_RUN", "The starting build did not complete"));
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
        } catch (DockerWorkloads.CacheSeedException e) {
            return failedAttempt(new Stop("BLOCKED_ENVIRONMENT", safeRedact(e.getMessage())), state);
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
        var url = settings.repositories().gitUrl(repository.sourceRepo());
        var taskRun = taskGuard.policy().find(context.executionId()).isPresent();
        return new Run(context, request, repository, url, scope, taskRun);
    }

    private Attempt develop(Run run, Mode mode, RunState state) {
        var stop = prepare(run, mode, state);
        if (stop.isPresent()) {
            return failedAttempt(stop.get(), state);
        }
        state.exported = CandidateFreezer.temporary("factory-dev-export-");
        CodingOutcome outcome;
        try (var workload = run.scope().createSeeded(services.coding().image(), state.pristine,
                settings.runtime().environment(run.plan()), "coding")) {
            var install = prepareWorkspace(run, state, workload);
            var codingStop = codingStop(run, mode, install);
            if (codingStop.isPresent()) {
                return failedAttempt(codingStop.get(), state);
            }
            outcome = code(run, state, workload, run.request().withVerification(
                    String.join(" ", settings.runtime().command(run.plan())), install.installed()));
        }
        return toAttempt(run, state, outcome);
    }

    /** Seeds the pinned lockfile before the install, so the prepared workspace is the pinned one. */
    private PreparedWorkspace.Result prepareWorkspace(Run run, RunState state, DockerWorkloads.Workload workload) {
        var pinned = PinnedLockfile.parse(state.lockfile);
        pinned.ifPresent(workload::seedLockfile);
        var install = pinned.map(pin -> pin.installMode(state.pristine)).orElse(InstallMode.FROZEN);
        return PreparedWorkspace.prepare(workload, settings.runtime(), run.plan(), install,
                stageTimeoutSeconds(run), event -> progress(run.context(), event));
    }

    /**
     * Checked after the install, right before the model call: a run whose time ran out during the install
     * stops at the deadline, and only a ready workspace claims the paid start.
     */
    private Optional<Stop> codingStop(Run run, Mode mode, PreparedWorkspace.Result install) {
        var stop = taskStop(run, "coding");
        if (stop.isPresent()) {
            return stop;
        }
        if (install.failed()) {
            return Optional.of(installStop(install));
        }
        return mode == Mode.START ? claimPaidStart(run) : Optional.empty();
    }

    private static Stop installStop(PreparedWorkspace.Result install) {
        if (install.retryable()) {
            throw new StartingBuildNetworkException("Workspace dependency install hit a network/download failure;"
                    + " coding has not started. " + install.failure());
        }
        return new Stop("BLOCKED_ENVIRONMENT",
                "Workspace dependency install failed before model spend: " + install.failure());
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
        // A run that lost its slot or ran out of time in the starting build opens no coding container.
        return runBaseline(run, state).or(() -> taskStop(run, "coding"));
    }

    private Optional<Stop> runBaseline(Run run, RunState state) {
        var repository = run.repository();
        var install = baselineInstall(run, state);
        progress(run.context(), Map.of(
                "activity", "baseline_started",
                "command", String.join(" ", settings.runtime().verificationCommand(run.plan(), install)),
                "image", repository.buildImage()));
        try (var workload = run.scope().createTrusted(repository.buildImage(), state.pristine,
                settings.runtime().environment(run.plan()))) {
            var baseline = new BaselineBuild(settings.runtime()).run(workload, run.baseSha(), repository,
                    install, stageTimeoutSeconds(run), event -> progress(run.context(), event));
            state.readiness = baseline.readiness();
            state.lockfile = lockfileArtifact(baseline, state.pristine);
            progress(run.context(), Map.of(
                    "activity", "baseline_completed",
                    "exitCode", baseline.process().exitCode(),
                    "summary", baseline.summary()));
            return baselineStop(run, baseline);
        }
    }

    /** A yarn module without a tracked lockfile gets one generated by the starting build and pinned. */
    private InstallMode baselineInstall(Run run, RunState state) {
        var untracked = !Files.exists(state.pristine.resolve(PinnedLockfile.FILE), LinkOption.NOFOLLOW_LINKS);
        return settings.runtime().toolchain(run.plan()) == Toolchain.YARN && untracked
                ? InstallMode.RESOLVE : InstallMode.FROZEN;
    }

    private static String lockfileArtifact(BaselineBuild.Result baseline, Path checkout) {
        return baseline.lockfile()
                .map(lock -> lock.forPackageJson(CandidateFreezer.trackedBlob(checkout, "package.json")))
                .map(PinnedLockfile::toArtifact)
                .orElseGet(() -> PinnedLockfile.none("NOT_NEEDED", "No lockfile to pin"));
    }

    private Optional<Stop> baselineStop(Run run, BaselineBuild.Result baseline) {
        if (baseline.process().exitCode() == 0) {
            return Optional.ofNullable(baseline.lockfileError()).map(error -> new Stop("BLOCKED_ENVIRONMENT",
                    "Starting build could not pin the dependency lockfile: " + error));
        }
        var context = run.context();
        var summary = baseline.summary();
        var toolchain = settings.runtime().toolchain(run.plan());
        if (toolchain.isTransientDownloadFailure(baseline.process().diagnostics())) {
            var detail = summary.isBlank() ? "Maven artifact transfer failed." : summary;
            var reason = "Starting build hit a network/download failure; coding has not started. " + detail;
            progress(context, Map.of("activity", "baseline_retryable_failure", "message", reason));
            throw new StartingBuildNetworkException(reason);
        }
        return Optional.of(new Stop("BLOCKED_ENVIRONMENT", TaskStops.startingBuildFailedReason(summary)));
    }

    private Optional<Stop> claimPaidStart(Run run) {
        if (!run.taskRun() || taskGuard.policy().claimPaidStart(run.executionId())) {
            return Optional.empty();
        }
        // A replayed or retried engine step must not start a second paid coding invocation.
        taskGuard.recordStop(run.executionId(), run.stepId(), TaskStops.REPLAYED_CODING_REASON);
        return Optional.of(new Stop("PAID_START_CONSUMED", TaskStops.REPLAYED_CODING_REASON));
    }

    /** {@code request} also tells the agent how Factory verifies the work; the stored request does not. */
    private CodingOutcome code(Run run, RunState state, DockerWorkloads.Workload workload, CodingRequest request) {
        var identity = new LinkedHashMap<>(services.coding().identity(run.request().runSettings()));
        var attemptId = UUID.randomUUID().toString();
        var rate = hooks.pricing().snapshot(Objects.toString(identity.get("modelId"), null),
                Objects.toString(identity.get("model"), ""));
        identity.put("activity", "coding_starting");
        identity.put("attemptId", attemptId);
        identity.put("rate", rate);
        progress(run.context(), identity);
        var outcome = callRuntime(run, workload, request, attemptId);
        state.metrics = reportedMetrics(outcome, attemptId, rate);
        if (outcome.status() == CodingOutcome.Status.COMPLETED) {
            workload.stop();
            workload.export(state.exported);
        }
        return outcome;
    }

    private CodingOutcome callRuntime(Run run, DockerWorkloads.Workload workload, CodingRequest request,
                                      String attemptId) {
        var context = run.context();
        var attempt = UUID.fromString(attemptId);
        try (var transcript = hooks.transcripts().open(context.executionId(), attempt, context.stepId())) {
            return services.coding().code(workload, request, stageTimeoutSeconds(run), event -> {
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
                    DevelopWorker.placeholder(json, "NEEDS_DECISION", outcome.decision().question()), state,
                    CodingDecisionArtifacts.Request.from(outcome.decision()));
            case FAILED -> attempt(run.request(), outcome,
                    DevelopWorker.placeholder(json, "DEVELOPMENT_FAILED", outcome.failure().message()), state,
                    CodingDecisionArtifacts.Request.none());
        };
    }

    private Attempt completedAttempt(Run run, RunState state, CodingOutcome outcome) {
        var stop = taskStop(run, "saving the agent's changes");
        if (stop.isPresent()) {
            return failedAttempt(stop.get(), state);
        }
        var candidate = services.freezer().freeze(run.repositoryKey(), run.url(), run.baseSha(), state.exported,
                false, settings.runtime().toolchain(run.plan()));
        var secret = secretStop(candidate);
        if (secret.isPresent()) {
            return failedAttempt(secret.get(), state);
        }
        return attempt(run.request(), outcome, json.writeValueAsString(candidate), state,
                CodingDecisionArtifacts.Request.none());
    }

    /** A patch that adds a model's API key must reach no artifact, check or pull request. */
    private Optional<Stop> secretStop(Candidate candidate) {
        return candidate.findFileAdding(services.coding()::holdsSecret)
                .map(file -> new Stop("DEVELOPMENT_FAILED", safeRedact(TaskStops.secretInChangesReason(file))));
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
        } catch (ModelCatalog.ModelException exception) {
            // Written to be shown: it names the model and the fix.
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

    /** The stage already says that development stopped, so the reason is only the cause. */
    private Stop developmentStopped(RuntimeException e) {
        if (Processes.OUTPUT_LIMIT_EXCEEDED.equals(e.getMessage())) {
            return new Stop("DEVELOPMENT_FAILED", TaskStops.OUTPUT_LIMIT_REASON);
        }
        var reason = safeRedact(CodingRuntime.failureText(e));
        return new Stop("DEVELOPMENT_FAILED", reason.substring(0, Math.min(2000, reason.length())));
    }

    private Attempt failedAttempt(Stop stop, RunState state) {
        var outcome = CodingOutcome.failed(stop.state(), stop.reason(), Map.of());
        return attempt(null, outcome, DevelopWorker.placeholder(json, stop.state(), stop.reason()), state,
                CodingDecisionArtifacts.Request.none());
    }

    private Attempt attempt(CodingRequest request, CodingOutcome outcome, String candidate, RunState state,
                            CodingDecisionArtifacts.Request decision) {
        var answer = new CodingDecisionArtifacts.Answer(decision.requestId(), CodingDecisionArtifacts.UNANSWERED);
        return new Attempt(request, outcome, candidate, json.writeValueAsString(state.readiness),
                CodingDecisionArtifacts.renderRequest(codec, decision),
                CodingDecisionArtifacts.renderAnswer(codec, answer),
                state.metrics,
                state.lockfile);
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
            Map<String, Object> metrics,
            String lockfile) {}

    /** A start runs the starting build and claims the paid start; its continuation keeps the start's readiness. */
    private enum Mode {
        START,
        CONTINUATION
    }

    /** One attempt inside its engine step; {@code taskRun} is false outside My tasks. */
    private record Run(
            AgentContext context,
            CodingRequest request,
            DevFactoryProperties.Repository repository,
            String url,
            DockerWorkloads.StepScope scope,
            boolean taskRun) {

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

        String plan() {
            return repository.verificationPlan();
        }
    }

    /**
     * What the steps leave for the catch and finally blocks of runAttempt: a failure still reports the
     * latest readiness, lockfile and metrics, and the temporary checkouts are always removed.
     */
    private static final class RunState {
        private Map<String, Object> readiness;
        private String lockfile;
        private Map<String, Object> metrics = Map.of();
        private Path pristine;
        private Path exported;

        private RunState(Map<String, Object> readiness, String lockfile) {
            this.readiness = readiness;
            this.lockfile = lockfile;
        }
    }

    /** Only a failed pre-runtime build may consume the engine retry budget. */
    private static final class StartingBuildNetworkException extends AgentExecutionException {
        private StartingBuildNetworkException(String message) {
            super(message);
        }
    }
}
