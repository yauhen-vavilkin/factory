package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.candidate.Candidate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Persist only as a Factory artifact, outside every workload's writable filesystem. */
public record VerificationReceipt(String executionId, String repository, String baseSha, String treeSha,
                                  String patchSha256, String planId, String image, List<String> argv,
                                  String workload, Instant startedAt, Instant finishedAt, int exitCode,
                                  String result, String output) {
    public VerificationReceipt {
        Objects.requireNonNull(executionId);
        argv = List.copyOf(argv);
        if (!Objects.equals(result, exitCode == 0 ? "PASS" : "FAIL")) {
            throw new IllegalArgumentException("Verification result must reflect actual exit code");
        }
    }

    /** Delivery must call this against its current execution and frozen candidate. */
    public void requireVerified(String currentExecutionId, Candidate candidate) {
        if (!"PASS".equals(result) || exitCode != 0
                || !Objects.equals(executionId, currentExecutionId)
                || !Objects.equals(repository, candidate.repository())
                || !Objects.equals(baseSha, candidate.baseSha())
                || !Objects.equals(treeSha, candidate.treeSha())
                || !Objects.equals(patchSha256, candidate.patchSha256())) {
            throw new IllegalStateException("Candidate has no matching successful verification receipt");
        }
    }
}
