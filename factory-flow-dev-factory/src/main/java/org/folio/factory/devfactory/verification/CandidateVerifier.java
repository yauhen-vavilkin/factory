package org.folio.factory.devfactory.verification;

import lombok.RequiredArgsConstructor;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.Toolchain;

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

    /**
     * The source comes from Factory configuration or its authorized PR adapter, never from model output. The
     * caller bounds the verification command by the time it may still take.
     */
    public VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate,
                                      String sourceUrl, int timeoutSeconds) {
        return verify(executionId, stepId, candidate, sourceUrl, timeoutSeconds, Optional.empty());
    }

    /**
     * A pinned lockfile is seeded as the workspace's yarn.lock. The install is frozen against it unless the
     * candidate changes package.json, where pinned versions are kept and only new dependencies are resolved.
     */
    public VerificationReceipt verify(UUID executionId, String stepId, Candidate candidate,
                                      String sourceUrl, int timeoutSeconds, Optional<PinnedLockfile> lockfile) {
        var repository = requireRepository(candidate);
        // reconstruct checks the Git tree before any command can execute.
        Path source = candidates.reconstruct(sourceUrl, candidate);
        Path exported = null;
        try (var stepScope = docker.beginStep(executionId, stepId)) {
            var plan = plan(repository, lockfile.map(pin -> pin.installMode(source)).orElse(InstallMode.FROZEN));
            // New container storage; DockerWorkloads supplies no model/Jira/GitHub environment.
            // The checkout contains source only, without the coding workspace's target output.
            try (var workload = stepScope.createSeeded(repository.buildImage(), source,
                    runtime.environment(repository.verificationPlan()), "verification")) {
                prepare(workload, plan.toolchain(), lockfile);
                var run = runCommand(workload, plan.command(), timeoutSeconds);
                exported = CandidateFreezer.temporary("factory-dev-verification-");
                workload.stop();
                workload.export(exported);
                var findings = inspectEvidence(exported, run, plan);
                return receipt(executionId, candidate, plan, workload.name(), run, findings, lockfile);
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

    private Plan plan(DevFactoryProperties.Repository repository, InstallMode install) {
        var id = repository.verificationPlan();
        return new Plan(repository, runtime.toolchain(id), runtime.verificationCommand(id, install),
                runtime.requiredReports(id));
    }

    /** The pinned lockfile goes in, and a report left in the checkout never stands in for this run's evidence. */
    private static void prepare(DockerWorkloads.Workload workload, Toolchain toolchain,
                                Optional<PinnedLockfile> lockfile) {
        lockfile.ifPresent(workload::seedLockfile);
        if (toolchain == Toolchain.YARN) {
            workload.execute(List.of("rm", "-rf", "artifacts/jest-junit"), 60, Processes.OUTPUT_LIMIT, null);
        }
    }

    /** A verbose test suite is not a failure, so only the tail of its output is kept. */
    private static Run runCommand(DockerWorkloads.Workload workload, List<String> command, int timeoutSeconds) {
        var started = Instant.now();
        Processes.Result observation;
        try {
            observation = workload.executeKeepingTail(command, timeoutSeconds, Processes.OUTPUT_LIMIT, null);
        } catch (IllegalStateException e) {
            // Transport failure has no process exit status. Still preserve available reports.
            observation = new Processes.Result(-1,
                    Objects.toString(e.getMessage(), "Command failed without diagnostic"));
        }
        return new Run(observation, started, Instant.now());
    }

    private static Findings inspectEvidence(Path exported, Run run, Plan plan) {
        var output = run.observation().diagnostics();
        Optional<TestEvidence> evidence;
        try {
            evidence = readEvidence(exported, run, plan);
        } catch (IllegalStateException e) {
            return findings(null, true, plan,
                    output + System.lineSeparator() + "No usable test evidence: " + e.getMessage());
        }
        return findings(evidence.orElse(null), false, plan, output);
    }

    /** Empty when the run wrote no report of the plan's toolchain. */
    private static Optional<TestEvidence> readEvidence(Path exported, Run run, Plan plan) {
        TestEvidence evidence = switch (plan.toolchain()) {
            case MAVEN -> SurefireEvidence.inspect(exported, run.started());
            case YARN -> JestJunitEvidence.inspect(exported, run.started());
        };
        return evidence.reportCount() == 0 ? Optional.empty() : Optional.of(evidence);
    }

    private static Findings findings(TestEvidence evidence, boolean invalidEvidence, Plan plan, String output) {
        var requiredReports = plan.requiredReports();
        var missingReports = evidence == null ? requiredReports : evidence.missingRequiredReports(requiredReports);
        var text = new StringBuilder(output);
        if (!missingReports.isEmpty()) {
            text.append(System.lineSeparator())
                    .append("Required Maven suites missing or containing entirely skipped reports: ")
                    .append(String.join(", ", missingReports));
        }
        if (evidence == null || evidence.testCount() == 0) {
            text.append(System.lineSeparator()).append("Trusted verification produced no fresh executed ")
                    .append(plan.toolchain() == Toolchain.YARN ? "Jest" : "Surefire").append(" tests");
        }
        return new Findings(evidence, invalidEvidence, missingReports, text.toString());
    }

    private static VerificationReceipt receipt(UUID executionId, Candidate candidate, Plan plan,
                                               String workloadName, Run run, Findings findings,
                                               Optional<PinnedLockfile> lockfile) {
        int exitCode = run.observation().exitCode();
        var result = findings.passed(exitCode) ? "PASS" : "FAIL";
        var failure = VerificationFailure.classify(plan.toolchain(), result, findings.output(),
                findings.invalidEvidence(), findings.insufficient(), findings.failureCount(), findings.errorCount());
        return new VerificationReceipt(executionId.toString(), candidate.repository(), candidate.baseSha(),
                candidate.treeSha(), candidate.patchSha256(), plan.repository().verificationPlan(),
                plan.repository().buildImage(), plan.command(), workloadName, run.started(), run.finished(),
                exitCode, findings.surefireReportCount(), findings.testCount(), findings.failureCount(),
                findings.errorCount(), result, safeOutput(findings.output()), failure,
                findings.failsafeReportCount(), plan.requiredReports(), findings.missingReports(), plan.toolchain(),
                lockfile.map(PinnedLockfile::sha256).orElse(null));
    }

    private static String safeOutput(String output) {
        String redacted = MavenBaselineOutput.redactCredentials(output);
        return redacted.substring(Math.max(0, redacted.length() - 16000));
    }

    private record Plan(DevFactoryProperties.Repository repository, Toolchain toolchain, List<String> command,
                        List<String> requiredReports) {
    }

    private record Run(Processes.Result observation, Instant started, Instant finished) {
    }

    /** What the exported reports show; {@code evidence} is null when no usable report exists. */
    private record Findings(TestEvidence evidence, boolean invalidEvidence, List<String> missingReports,
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
