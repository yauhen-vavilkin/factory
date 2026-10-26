package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.Toolchain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Independent acceptance authority. No coding output or task text supplies executable policy. */
public class CandidateVerifier {
    private final DevFactoryProperties repositories;
    private final DevRuntimeProperties runtime;
    private final CandidateFreezer candidates;
    private final DockerWorkloads docker;

    public CandidateVerifier(DevFactoryProperties repositories, DevRuntimeProperties runtime,
                             CandidateFreezer candidates, DockerWorkloads docker) {
        this.repositories = repositories;
        this.runtime = runtime;
        this.candidates = candidates;
        this.docker = docker;
    }

    public VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate) {
        return verify(executionId, stepId, candidate, Optional.empty());
    }

    public VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate,
                                      Optional<PinnedLockfile> lockfile) {
        var repository = repositories.repositories().get(candidate.repository());
        if (repository == null) throw new IllegalStateException("Missing trusted repository: " + candidate.repository());
        return verify(executionId, stepId, candidate,
                repositories.gitBaseUrl() + "/" + repository.sourceRepo() + ".git", lockfile);
    }

    /** Source is supplied by Factory's authorized PR adapter, never by model output. */
    public VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate, String sourceUrl) {
        return verify(executionId, stepId, candidate, sourceUrl, Optional.empty());
    }

    /**
     * A pinned lockfile is seeded as the workspace's yarn.lock. The install is frozen against it unless the
     * candidate changes package.json, where pinned versions are kept and only new dependencies are resolved.
     */
    public VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate, String sourceUrl,
                                      Optional<PinnedLockfile> lockfile) {
        var repository = repositories.repositories().get(candidate.repository());
        if (repository == null) throw new IllegalStateException("Missing trusted repository: " + candidate.repository());
        var toolchain = runtime.toolchain(repository.verificationPlan());
        var requiredReports = runtime.requiredReports(repository.verificationPlan());
        // reconstruct checks the Git tree before any command can execute.
        Path source = candidates.reconstruct(sourceUrl, candidate);
        Path exported = null;
        try (var stepScope = docker.beginStep(executionId, stepId)) {
            var install = lockfile.map(pin -> pin.installMode(source)).orElse(InstallMode.FROZEN);
            var command = runtime.verificationCommand(repository.verificationPlan(), install);
            // New container storage; DockerWorkloads supplies no model/Jira/GitHub environment.
            // The checkout contains source only, without the coding workspace's target output.
            try (var workload = stepScope.createSeeded(repository.buildImage(), source,
                    runtime.environment(repository.verificationPlan()), "verification")) {
                lockfile.ifPresent(workload::seedLockfile);
                // A report left in the checkout must never stand in for this run's evidence.
                if (toolchain == Toolchain.YARN) {
                    workload.execute(List.of("rm", "-rf", "artifacts/jest-junit"), 60);
                }
                Instant started = Instant.now();
                Processes.Result observation;
                try {
                    observation = workload.executeKeepingTail(command, runtime.timeoutSeconds(), Processes.OUTPUT_LIMIT, null);
                } catch (IllegalStateException e) {
                    // Transport failure has no process exit status. Still preserve available reports.
                    observation = new Processes.Result(-1, Objects.toString(e.getMessage(), "Command failed without diagnostic"));
                }
                Instant finished = Instant.now();
                TestEvidence evidence = null;
                boolean invalidEvidence = false;
                String output = observation.diagnostics();
                exported = CandidateFreezer.temporary("factory-dev-verification-");
                workload.stop();
                workload.export(exported);
                try {
                    evidence = switch (toolchain) {
                        case MAVEN -> SurefireEvidence.inspect(exported, started);
                        case YARN -> JestJunitEvidence.inspect(exported, started);
                    };
                } catch (IllegalStateException e) {
                    invalidEvidence = true;
                    output += System.lineSeparator() + "No usable test evidence: " + e.getMessage();
                }
                if (evidence != null && evidence.reportCount() == 0) evidence = null;
                var executed = evidence == null ? Map.<String, Integer>of() : evidence.executedByReport();
                var missingReports = requiredReports.stream().filter(path -> executed.getOrDefault(path, 0) < 1).toList();
                if (!missingReports.isEmpty()) output += System.lineSeparator()
                        + "Required Maven suites missing or entirely skipped: " + String.join(", ", missingReports);
                if (evidence == null || evidence.testCount() == 0) {
                    output += System.lineSeparator() + "Trusted verification produced no fresh executed "
                            + switch (toolchain) { case MAVEN -> "Surefire"; case YARN -> "Jest"; } + " tests";
                }
                String result = observation.exitCode() == 0 && evidence != null && evidence.reportCount() > 0
                        && evidence.testCount() > 0 && evidence.failureCount() == 0
                        && evidence.errorCount() == 0 && missingReports.isEmpty() ? "PASS" : "FAIL";
                var failure = VerificationFailure.classify(toolchain, result, output, invalidEvidence,
                        evidence == null || evidence.testCount() == 0 || !missingReports.isEmpty(),
                        evidence == null ? null : evidence.failureCount(), evidence == null ? null : evidence.errorCount());
                return new VerificationReceipt(executionId.toString(), candidate.repository(), candidate.baseSha(),
                        candidate.treeSha(), candidate.patchSha256(), repository.verificationPlan(),
                        repository.buildImage(), command, workload.name(), started, finished,
                        observation.exitCode(), evidence == null ? 0 : evidence.reportCount() - evidence.failsafeReportCount(),
                        evidence == null ? null : evidence.testCount(),
                        evidence == null ? null : evidence.failureCount(),
                        evidence == null ? null : evidence.errorCount(), result,
                        safeOutput(output), failure,
                        evidence == null ? 0 : evidence.failsafeReportCount(), requiredReports, missingReports, toolchain,
                        lockfile.map(PinnedLockfile::sha256).orElse(null));
            }
        } finally {
            CandidateFreezer.cleanup(source);
            CandidateFreezer.cleanup(exported);
        }
    }

    private static String safeOutput(String output) {
        String redacted = MavenBaselineOutput.sanitize(output.replaceAll(
                "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]+", "$1 [REDACTED]"));
        return redacted.substring(Math.max(0, redacted.length() - 16000));
    }
}
