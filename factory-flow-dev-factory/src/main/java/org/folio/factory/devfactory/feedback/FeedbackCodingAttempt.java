package org.folio.factory.devfactory.feedback;

import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.CodingTranscripts;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.PreparedWorkspace;
import org.folio.factory.devfactory.tasks.TaskStops;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** One feedback attempt. Saves partial edits before returning a question or failure. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class FeedbackCodingAttempt {
    private final DevFactoryProperties repositories;
    private final CandidateFreezer freezer;
    private final CodingRuntime coding;
    private final DockerWorkloads docker;
    private final DevRuntimeProperties runtime;
    private final AttemptPricing pricing;
    private final CodingTranscripts transcripts;

    /** {@code lockfile} is the yarn.lock that the starting build pinned for a module without one. */
    CodingOutcome run(FeedbackWorker.Operation operation, CodingRequest request, Candidate candidate,
                      Optional<PinnedLockfile> lockfile, String sourceUrl, Consumer<Map<String, Object>> progress) {
        var plan = verificationPlan(request);
        var source = freezer.reconstruct(sourceUrl, candidate);
        var exported = CandidateFreezer.temporary("factory-pr-export-");
        try (var scope = docker.beginStep(operation.context().executionId(), operation.id())) {
            coding.requireConfigured(request.runSettings());
            recordStart(operation.state(), request, progress);
            try (var workload = scope.createSeeded(coding.image(), source, runtime.environment(plan), "coding")) {
                lockfile.ifPresent(workload::seedLockfile);
                // The PR head may carry a package.json change from an earlier round; then a plain install.
                var install = lockfile.map(pin -> pin.installMode(source)).orElse(InstallMode.FROZEN);
                var outcome = prepareAndCode(operation, request, workload, install, progress);
                recordUsage(operation, outcome);
                // Preserve edits even when the agent asks a question or reports a recoverable failure.
                saveEdits(operation, request, sourceUrl, workload, exported);
                return outcome;
            }
        } finally {
            CandidateFreezer.cleanup(source);
            CandidateFreezer.cleanup(exported);
        }
    }

    private String verificationPlan(CodingRequest request) {
        var repository = repositories.repositories().get(request.repository().key());
        if (repository == null) {
            throw new IllegalStateException("Repository is no longer configured");
        }
        return repository.verificationPlan();
    }

    /** The agent starts in the same installed workspace that verification builds on. */
    private CodingOutcome prepareAndCode(FeedbackWorker.Operation operation, CodingRequest request,
                                         DockerWorkloads.Workload workload, InstallMode install,
                                         Consumer<Map<String, Object>> progress) {
        var plan = verificationPlan(request);
        var prepared = PreparedWorkspace.prepare(workload, runtime, plan, install, progress);
        if (prepared.failed()) {
            return CodingOutcome.failed("WORKSPACE_INSTALL",
                    "Dependency install failed before any model call: " + prepared.failure(), Map.of());
        }
        var verified = request.withVerification(String.join(" ", runtime.command(plan)), prepared.installed());
        return code(operation, verified, workload, progress);
    }

    private void recordStart(FeedbackState state, CodingRequest request, Consumer<Map<String, Object>> progress) {
        var identity = coding.identity(request.runSettings());
        state.runtimeName = Objects.toString(identity.get("runtime"), "");
        state.rate = pricing.snapshot(Objects.toString(identity.get("modelId"), null),
                Objects.toString(identity.get("model"), ""));
        var start = new LinkedHashMap<String, Object>(identity);
        start.put("activity", "coding_starting");
        start.put("rate", state.rate);
        progress.accept(start);
    }

    private CodingOutcome code(FeedbackWorker.Operation operation, CodingRequest request,
                               DockerWorkloads.Workload workload, Consumer<Map<String, Object>> progress) {
        var context = operation.context();
        try (var transcript = transcripts.open(
                context.executionId(), UUID.fromString(operation.state().attemptId), context.stepId())) {
            return coding.code(workload, request, runtime.timeoutSeconds(), progress, transcript);
        } catch (RuntimeException e) {
            return CodingOutcome.failed("RUNTIME_FAILURE", safe(coding, e), Map.of());
        }
    }

    private static void recordUsage(FeedbackWorker.Operation operation, CodingOutcome outcome) {
        var state = operation.state();
        state.usage = outcome.metrics();
        var metrics = operation.metrics();
        metrics.putAll(outcome.metrics());
        metrics.put("attemptId", state.attemptId);
        metrics.put("round", state.round);
        metrics.put("rate", state.rate);
    }

    private void saveEdits(FeedbackWorker.Operation operation, CodingRequest request, String sourceUrl,
                           DockerWorkloads.Workload workload, Path exported) {
        try {
            workload.stop();
            workload.export(exported);
            var candidate = freezer.freeze(request.repository().key(), sourceUrl, operation.state().pr.headSha(),
                    exported, true, runtime.toolchain(verificationPlan(request)));
            refuseSecret(candidate);
            operation.outputs().put(CANDIDATE, JSON.writeValueAsString(candidate));
        } catch (RuntimeException lost) {
            throw new IllegalStateException(FeedbackState.EDITS_NOT_SAVED_START + safe(coding, lost));
        }
    }

    /** Edits that add a model's API key are not saved, so they reach no check, commit or pull request. */
    private void refuseSecret(Candidate candidate) {
        candidate.findFileAdding(coding::holdsSecret).ifPresent(file -> {
            throw new IllegalStateException(TaskStops.secretInChangesCause(file));
        });
    }

    /** Redacted and bounded error text, safe for state, audit and pages. */
    static String safe(CodingRuntime coding, Exception error) {
        String text = coding.redact(Objects.toString(error.getMessage(), "Operation failed"));
        return text.substring(0, Math.min(1500, text.length()));
    }
}
