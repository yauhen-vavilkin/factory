package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.candidate.Candidate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Persist only as a Factory artifact, outside every workload's writable filesystem. */
public record VerificationReceipt(String executionId, String repository, String baseSha, String treeSha,
                                  String patchSha256, String planId, String image, List<String> argv,
                                  String workload, Instant startedAt, Instant finishedAt, int exitCode,
                                  int surefireReportCount, Integer testCount, Integer failureCount, Integer errorCount,
                                  String result, String output, VerificationFailure failureKind,
                                  int failsafeReportCount, List<String> requiredReports,
                                  List<String> missingRequiredReports) {
    /** Historical Surefire-only receipts remain readable, but cannot opt into automatic repair. */
    public VerificationReceipt(String executionId, String repository, String baseSha, String treeSha,
                               String patchSha256, String planId, String image, List<String> argv,
                               String workload, Instant startedAt, Instant finishedAt, int exitCode,
                               int surefireReportCount, Integer testCount, Integer failureCount, Integer errorCount,
                               String result, String output) {
        this(executionId, repository, baseSha, treeSha, patchSha256, planId, image, argv, workload,
                startedAt, finishedAt, exitCode, surefireReportCount, testCount, failureCount, errorCount,
                result, output, null, 0, List.of(), List.of());
    }
    public VerificationReceipt {
        Objects.requireNonNull(executionId);
        argv = List.copyOf(argv);
        requiredReports = requiredReports == null ? List.of() : List.copyOf(requiredReports);
        missingRequiredReports = missingRequiredReports == null ? List.of() : List.copyOf(missingRequiredReports);
        failureKind = failureKind == null ? ("PASS".equals(result) ? VerificationFailure.NONE : VerificationFailure.UNKNOWN)
                : failureKind;
        if (surefireReportCount < 0 || failsafeReportCount < 0 || ((long) surefireReportCount + failsafeReportCount == 0
                ? testCount != null || failureCount != null || errorCount != null
                : testCount == null || failureCount == null || errorCount == null
                || testCount < 0 || failureCount < 0 || errorCount < 0
                || (long) failureCount + errorCount > testCount)) {
            throw new IllegalArgumentException("Invalid test evidence count");
        }
        if (!requiredReports.containsAll(missingRequiredReports))
            throw new IllegalArgumentException("Missing reports must belong to the trusted plan");
        if (!Objects.equals(result, exitCode == 0 && (long) surefireReportCount + failsafeReportCount > 0 && testCount > 0
                && failureCount == 0 && errorCount == 0 && missingRequiredReports.isEmpty() ? "PASS" : "FAIL")) {
            throw new IllegalArgumentException("Verification result must reflect exit code and fresh executed tests");
        }
        if ("PASS".equals(result) != (failureKind == VerificationFailure.NONE)
                || failureKind.repairable() && (testCount == null || failureCount == null || failureCount < 1
                || errorCount == null || errorCount != 0 || !missingRequiredReports.isEmpty())) {
            throw new IllegalArgumentException("Failure classification must agree with verification evidence");
        }
    }

    /** Delivery must call this against its current execution and frozen candidate. */
    public void requireVerified(String currentExecutionId, Candidate candidate) {
        if (!"PASS".equals(result) || exitCode != 0 || (long) surefireReportCount + failsafeReportCount < 1
                || !missingRequiredReports.isEmpty() || failureKind != VerificationFailure.NONE
                || testCount == null || failureCount == null || errorCount == null || testCount < 1
                || failureCount != 0 || errorCount != 0
                || !Objects.equals(executionId, currentExecutionId)
                || !Objects.equals(repository, candidate.repository())
                || !Objects.equals(baseSha, candidate.baseSha())
                || !Objects.equals(treeSha, candidate.treeSha())
                || !Objects.equals(patchSha256, candidate.patchSha256())) {
            throw new IllegalStateException("Candidate has no matching successful verification receipt");
        }
    }
}
