package org.folio.factory.devfactory.feedback;

import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.PreparedWorkspace;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** One feedback attempt. Saves partial edits before returning a question or failure. */
final class FeedbackCodingAttempt {
    private final DevFactoryProperties repositories;
    private final CandidateFreezer freezer;
    private final CodingRuntime coding;
    private final DockerWorkloads docker;
    private final DevRuntimeProperties runtime;
    private final AttemptPricing pricing;

    FeedbackCodingAttempt(
            DevFactoryProperties repositories,
            CandidateFreezer freezer,
            CodingRuntime coding,
            DockerWorkloads docker,
            DevRuntimeProperties runtime,
            AttemptPricing pricing) {
        this.repositories = repositories;
        this.freezer = freezer;
        this.coding = coding;
        this.docker = docker;
        this.runtime = runtime;
        this.pricing = pricing;
    }

    CodingOutcome run(
            AgentContext context,
            FeedbackState state,
            CodingRequest request,
            Candidate candidate,
            Optional<PinnedLockfile> lockfile,
            String sourceUrl,
            String operation,
            Map<String, String> outputs,
            Map<String, Object> metrics,
            Consumer<Map<String, Object>> progress) {
        var repository = repositories.repositories().get(request.repository().key());
        if (repository == null) throw new IllegalStateException("Repository is no longer configured");
        var environment = runtime.environment(repository.verificationPlan());
        Path source = freezer.reconstruct(sourceUrl, candidate);
        Path exported = CandidateFreezer.temporary("factory-pr-export-");
        CodingOutcome outcome = null;
        try (var scope = docker.beginStep(context.executionId(), operation)) {
            coding.requireConfigured();
            state.runtimeName = Objects.toString(coding.identity().get("runtime"), "");
            state.rate = pricing.snapshot(Objects.toString(coding.identity().get("model"), ""));
            var start = new LinkedHashMap<String, Object>(coding.identity());
            start.put("activity", "coding_starting");
            start.put("rate", state.rate);
            progress.accept(start);
            try (var workload =
                    scope.createSeeded(coding.image(), source, environment, "coding")) {
                lockfile.ifPresent(workload::seedLockfile);
                // The PR head may carry a package.json change from an earlier round; the pin then needs a plain install.
                var install = lockfile.map(pin -> pin.installMode(source)).orElse(InstallMode.FROZEN);
                var prepared = PreparedWorkspace.prepare(workload, runtime, repository.verificationPlan(), install, progress);
                if (prepared.failed()) {
                    outcome = CodingOutcome.failed("WORKSPACE_INSTALL",
                            "Dependency install failed before any model call: " + prepared.failure(), Map.of());
                } else {
                    try {
                        outcome = coding.code(workload,
                                request.withVerification(String.join(" ", runtime.command(repository.verificationPlan())),
                                        prepared.installed()),
                                runtime.timeoutSeconds(), progress);
                    } catch (RuntimeException e) {
                        outcome = CodingOutcome.failed("RUNTIME_FAILURE", safe(e), Map.of());
                    }
                }
                state.usage = outcome.metrics();
                metrics.putAll(outcome.metrics());
                metrics.put("attemptId", state.attemptId);
                metrics.put("round", state.round);
                metrics.put("rate", state.rate);
                // Preserve edits even when the agent asks a question or reports a recoverable
                // failure.
                try {
                    workload.stop();
                    workload.export(exported);
                    outputs.put(
                            CANDIDATE,
                            JSON.writeValueAsString(
                                    freezer.freeze(
                                            request.repository().key(),
                                            sourceUrl,
                                            state.pr.headSha(),
                                            exported,
                                            true,
                                            environment.toolchain())));
                } catch (RuntimeException lost) {
                    throw new IllegalStateException(
                            "Latest edits could not be saved; the previous confirmed patch is"
                                    + " retained. "
                                    + safe(lost));
                }
            }
            return outcome;
        } finally {
            CandidateFreezer.cleanup(source);
            CandidateFreezer.cleanup(exported);
        }
    }

    private String safe(Exception error) {
        String text = coding.redact(Objects.toString(error.getMessage(), "Operation failed"));
        return text.substring(0, Math.min(1500, text.length()));
    }
}
