package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;

import java.nio.file.Path;
import java.time.Instant;

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
        String sourceUrl = repositories.gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
        // reconstruct checks the Git tree before any command can execute.
        Path source = candidates.reconstruct(sourceUrl, candidate);
        Path exported = null;
        try {
            // New container storage; DockerWorkloads supplies no model/Jira/GitHub environment.
            // The checkout contains source only, without the coding workspace's target output.
            try (var workload = docker.create(repository.buildImage(), source)) {
                Instant started = Instant.now();
                var observation = workload.execute(command, runtime.timeoutSeconds());
                Instant finished = Instant.now();
                SurefireEvidence evidence = new SurefireEvidence(0, 0, 0, 0);
                String output = observation.diagnostics();
                if (observation.exitCode() == 0) {
                    exported = CandidateFreezer.temporary("factory-dev-verification-");
                    workload.stop();
                    workload.export(exported);
                    evidence = SurefireEvidence.inspect(exported, started);
                    if (evidence.testCount() == 0) {
                        output = output + System.lineSeparator()
                                + "Trusted verification produced no fresh executed Surefire tests";
                    }
                }
                String result = observation.exitCode() == 0 && evidence.reportCount() > 0
                        && evidence.testCount() > 0 && evidence.failureCount() == 0
                        && evidence.errorCount() == 0 ? "PASS" : "FAIL";
                return new VerificationReceipt(executionId, candidate.repository(), candidate.baseSha(),
                        candidate.treeSha(), candidate.patchSha256(), repository.verificationPlan(),
                        repository.buildImage(), command, workload.name(), started, finished,
                        observation.exitCode(), evidence.reportCount(), evidence.testCount(),
                        evidence.failureCount(), evidence.errorCount(), result,
                        output.substring(Math.max(0, output.length() - 16000)));
            }
        } finally {
            CandidateFreezer.cleanup(source);
            CandidateFreezer.cleanup(exported);
        }
    }
}
