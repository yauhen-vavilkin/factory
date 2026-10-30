package org.folio.factory.devfactory.verification;

import lombok.RequiredArgsConstructor;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.tasks.AttemptDeadline;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Independent acceptance authority. No coding output or task text supplies executable policy. */
@RequiredArgsConstructor
public class CandidateVerifier {
    private final DevFactoryProperties repositories;
    private final DevRuntimeProperties runtime;
    private final CandidateFreezer candidates;
    private final DockerWorkloads docker;

    public VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate) {
        return verify(executionId, stepId, candidate, Optional.empty());
    }

    /** Verification bounded by the task attempt's remaining whole-attempt budget. */
    public VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate,
                                      Optional<AttemptDeadline> deadline) {
        var repository = requireRepository(candidate);
        return verify(executionId, stepId, candidate,
                repositories.gitBaseUrl() + "/" + repository.sourceRepo() + ".git", deadline);
    }

    /** Source is supplied by Factory's authorized PR adapter, never by model output. */
    public VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate, String sourceUrl) {
        return verify(executionId, stepId, candidate, sourceUrl, Optional.empty());
    }

    private VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate,
                                       String sourceUrl, Optional<AttemptDeadline> deadline) {
        var plan = plan(requireRepository(candidate));
        int timeoutSeconds = stageTimeoutSeconds(deadline);
        // reconstruct checks the Git tree before any command can execute.
        Path source = candidates.reconstruct(sourceUrl, candidate);
        Path exported = null;
        try (var stepScope = docker.beginStep(executionId, stepId)) {
            // New container storage; DockerWorkloads supplies no model/Jira/GitHub environment.
            // The checkout contains source only, without the coding workspace's target output.
            try (var workload = stepScope.createSeeded(plan.repository().buildImage(), source,
                    runtime.mavenCacheVolume(), "verification")) {
                var run = runCommand(workload, plan.command(), timeoutSeconds);
                exported = CandidateFreezer.temporary("factory-dev-verification-");
                workload.stop();
                workload.export(exported);
                var findings = inspectEvidence(exported, run, plan.requiredReports());
                return receipt(executionId, candidate, plan, workload.name(), run, findings);
            }
        } finally {
            CandidateFreezer.cleanup(source);
            CandidateFreezer.cleanup(exported);
        }
    }

    private DevFactoryProperties.Repository requireRepository(Candidate candidate) {
        var repository = repositories.repositories().get(candidate.repository());
        if (repository == null) {
            throw new IllegalStateException("Missing trusted repository: " + candidate.repository());
        }
        return repository;
    }

    private Plan plan(DevFactoryProperties.Repository repository) {
        return new Plan(repository, runtime.command(repository.verificationPlan()),
                runtime.requiredReports(repository.verificationPlan()));
    }

    private static Run runCommand(DockerWorkloads.Workload workload, List<String> command, int timeoutSeconds) {
        var started = Instant.now();
        Processes.Result observation;
        try {
            observation = workload.execute(command, timeoutSeconds);
        } catch (IllegalStateException e) {
            // Transport failure has no process exit status. Still preserve available reports.
            observation = new Processes.Result(-1,
                    Objects.toString(e.getMessage(), "Command failed without diagnostic"));
        }
        return new Run(observation, started, Instant.now());
    }

    private static Findings inspectEvidence(Path exported, Run run, List<String> requiredReports) {
        SurefireEvidence evidence = null;
        var invalidEvidence = false;
        var output = run.observation().diagnostics();
        try {
            evidence = SurefireEvidence.inspect(exported, run.started());
        } catch (IllegalStateException e) {
            invalidEvidence = true;
            output += System.lineSeparator() + "No usable test evidence: " + e.getMessage();
        }
        if (evidence != null && evidence.reportCount() == 0) {
            evidence = null;
        }
        var missingReports = evidence == null ? requiredReports : evidence.missingRequiredReports(requiredReports);
        if (!missingReports.isEmpty()) {
            output += System.lineSeparator()
                    + "Required Maven suites missing or containing entirely skipped reports: "
                    + String.join(", ", missingReports);
        }
        if (evidence == null || evidence.testCount() == 0) {
            output += System.lineSeparator() + "Trusted verification produced no fresh executed Surefire tests";
        }
        return new Findings(evidence, invalidEvidence, missingReports, output);
    }

    private static VerificationReceipt receipt(UUID executionId, Candidate candidate, Plan plan,
                                               String workloadName, Run run, Findings findings) {
        int exitCode = run.observation().exitCode();
        var result = findings.passed(exitCode) ? "PASS" : "FAIL";
        var failure = VerificationFailure.classify(result, findings.output(), findings.invalidEvidence(),
                findings.insufficient(), findings.failureCount(), findings.errorCount());
        return new VerificationReceipt(executionId.toString(), candidate.repository(), candidate.baseSha(),
                candidate.treeSha(), candidate.patchSha256(), plan.repository().verificationPlan(),
                plan.repository().buildImage(), plan.command(), workloadName, run.started(), run.finished(),
                exitCode, findings.surefireReportCount(), findings.testCount(), findings.failureCount(),
                findings.errorCount(), result, safeOutput(findings.output()), failure,
                findings.failsafeReportCount(), plan.requiredReports(), findings.missingReports());
    }

    /** min(existing per-operation limit, remaining whole-attempt budget). */
    private int stageTimeoutSeconds(Optional<AttemptDeadline> deadline) {
        return deadline
                .map(budget -> budget.capSeconds(java.time.Clock.systemUTC(), runtime.timeoutSeconds()))
                .orElse(runtime.timeoutSeconds());
    }

    private static String safeOutput(String output) {
        String redacted = MavenBaselineOutput.sanitize(output.replaceAll(
                "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]+", "$1 [REDACTED]"));
        return redacted.substring(Math.max(0, redacted.length() - 16000));
    }

    private record Plan(DevFactoryProperties.Repository repository, List<String> command,
                        List<String> requiredReports) {
    }

    private record Run(Processes.Result observation, Instant started, Instant finished) {
    }

    /** What the exported reports show; {@code evidence} is null when no usable report exists. */
    private record Findings(SurefireEvidence evidence, boolean invalidEvidence, List<String> missingReports,
                            String output) {
        boolean passed(int exitCode) {
            return exitCode == 0 && evidence != null && evidence.reportCount() > 0 && evidence.testCount() > 0
                    && evidence.failureCount() == 0 && evidence.errorCount() == 0 && missingReports.isEmpty();
        }

        boolean insufficient() {
            return evidence == null || evidence.testCount() == 0 || !missingReports.isEmpty();
        }

        Integer testCount() {
            return evidence == null ? null : evidence.testCount();
        }

        Integer failureCount() {
            return evidence == null ? null : evidence.failureCount();
        }

        Integer errorCount() {
            return evidence == null ? null : evidence.errorCount();
        }

        int surefireReportCount() {
            return evidence == null ? 0 : evidence.reportCount() - evidence.failsafeReportCount();
        }

        int failsafeReportCount() {
            return evidence == null ? 0 : evidence.failsafeReportCount();
        }
    }
}
