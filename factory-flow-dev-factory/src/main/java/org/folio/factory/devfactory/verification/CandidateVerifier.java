package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.Processes;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

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

    public VerificationReceipt verify(String executionId, Candidate candidate) {
        var repository = repositories.repositories().get(candidate.repository());
        if (repository == null) throw new IllegalStateException("Missing trusted repository: " + candidate.repository());
        var command = runtime.command(repository.verificationPlan());
        var requiredReports = runtime.requiredReports(repository.verificationPlan());
        String sourceUrl = repositories.gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
        // reconstruct checks the Git tree before any command can execute.
        Path source = candidates.reconstruct(sourceUrl, candidate);
        Path exported = null;
        try {
            // New container storage; DockerWorkloads supplies no model/Jira/GitHub environment.
            // The checkout contains source only, without the coding workspace's target output.
            try (var workload = docker.createSeeded(repository.buildImage(), source, runtime.mavenCacheVolume())) {
                Instant started = Instant.now();
                Processes.Result observation;
                try {
                    observation = workload.execute(command, runtime.timeoutSeconds());
                } catch (IllegalStateException e) {
                    // Transport failure has no process exit status. Still preserve available reports.
                    observation = new Processes.Result(-1, Objects.toString(e.getMessage(), "Command failed without diagnostic"));
                }
                Instant finished = Instant.now();
                SurefireEvidence evidence = null;
                boolean invalidEvidence = false;
                String output = observation.diagnostics();
                exported = CandidateFreezer.temporary("factory-dev-verification-");
                workload.stop();
                workload.export(exported);
                try {
                    evidence = SurefireEvidence.inspect(exported, started);
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
                    output += System.lineSeparator() + "Trusted verification produced no fresh executed Surefire tests";
                }
                String result = observation.exitCode() == 0 && evidence != null && evidence.reportCount() > 0
                        && evidence.testCount() > 0 && evidence.failureCount() == 0
                        && evidence.errorCount() == 0 && missingReports.isEmpty() ? "PASS" : "FAIL";
                var failure = VerificationFailure.classify(result, output, invalidEvidence,
                        evidence == null || evidence.testCount() == 0 || !missingReports.isEmpty(),
                        evidence == null ? null : evidence.failureCount(), evidence == null ? null : evidence.errorCount());
                return new VerificationReceipt(executionId, candidate.repository(), candidate.baseSha(),
                        candidate.treeSha(), candidate.patchSha256(), repository.verificationPlan(),
                        repository.buildImage(), command, workload.name(), started, finished,
                        observation.exitCode(), evidence == null ? 0 : evidence.reportCount() - evidence.failsafeReportCount(),
                        evidence == null ? null : evidence.testCount(),
                        evidence == null ? null : evidence.failureCount(),
                        evidence == null ? null : evidence.errorCount(), result,
                        safeOutput(output), failure,
                        evidence == null ? 0 : evidence.failsafeReportCount(), requiredReports, missingReports);
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
