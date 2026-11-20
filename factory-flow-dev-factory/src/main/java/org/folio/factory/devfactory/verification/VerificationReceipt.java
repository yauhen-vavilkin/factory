package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.runtime.Toolchain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Persist only as a Factory artifact, outside every workload's writable filesystem.
 * {@code surefireReportCount} is the primary test-report count of every toolchain (the name is historical).
 * {@code lockfileSha256} is null when no lockfile was pinned.
 */
public record VerificationReceipt(String executionId, String repository, String baseSha, String treeSha,
                                  String patchSha256, String planId, String image, List<String> argv,
                                  String workload, Instant startedAt, Instant finishedAt, int exitCode,
                                  int surefireReportCount, Integer testCount, Integer failureCount, Integer errorCount,
                                  String result, String output, VerificationFailure failureKind,
                                  int failsafeReportCount, List<String> requiredReports,
                                  List<String> missingRequiredReports, Toolchain toolchain, String lockfileSha256) {
    public VerificationReceipt {
        Objects.requireNonNull(executionId);
        argv = List.copyOf(argv);
        // Receipts written before toolchains existed are Maven receipts.
        toolchain = toolchain == null ? Toolchain.MAVEN : toolchain;
        requireLockfileDigest(lockfileSha256);
        requiredReports = requiredReports == null ? List.of() : List.copyOf(requiredReports);
        missingRequiredReports = missingRequiredReports == null ? List.of() : List.copyOf(missingRequiredReports);
        failureKind = failureKind == null ? defaultFailureKind(result) : failureKind;
        if (invalidCounts(surefireReportCount, failsafeReportCount, testCount, failureCount, errorCount)) {
            throw new IllegalArgumentException("Invalid test evidence count");
        }
        if (!requiredReports.containsAll(missingRequiredReports)) {
            throw new IllegalArgumentException("Missing reports must belong to the trusted plan");
        }
        boolean passed = exitCode == 0 && (long) surefireReportCount + failsafeReportCount > 0
                && testCount > 0 && failureCount == 0 && errorCount == 0 && missingRequiredReports.isEmpty();
        if (!Objects.equals(result, passed ? "PASS" : "FAIL")) {
            throw new IllegalArgumentException("Verification result must reflect exit code and fresh executed tests");
        }
        if (classificationDisagrees(result, failureKind, testCount, failureCount, errorCount,
                missingRequiredReports)) {
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

    /**
     * Why the checks did not pass, in the words of the task pages but without the test count, which the page shows
     * next to it: "Checks failed: the code does not compile." Blank for checks that passed.
     */
    public String failureReason() {
        if ("PASS".equals(result)) {
            return "";
        }
        if (failureKind == VerificationFailure.CANDIDATE_COMPILE) {
            return "Checks failed: the code does not compile.";
        }
        // Tests that failed are the cause even when the build environment looks broken too.
        if (testCount != null && testCount > 0 && failureCount + errorCount > 0) {
            return "Checks failed: some tests failed.";
        }
        return switch (failureKind) {
            case ENVIRONMENT -> "Checks could not run: the build environment failed (Docker, network, disk space"
                    + " or time limit).";
            case INVALID_EVIDENCE -> "Checks could not run: the test reports could not be read.";
            case INSUFFICIENT_EVIDENCE -> "Checks could not run: no test reports were written.";
            default -> "Checks failed: the build failed with exit code " + exitCode + ".";
        };
    }

    private static void requireLockfileDigest(String sha256) {
        if (sha256 != null && !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid lockfile digest");
        }
    }

    private static VerificationFailure defaultFailureKind(String result) {
        return "PASS".equals(result) ? VerificationFailure.NONE : VerificationFailure.UNKNOWN;
    }

    /** Counts are absent exactly when there is no report; otherwise failures and errors fit in the tests. */
    private static boolean invalidCounts(int surefireReports, int failsafeReports,
                                         Integer tests, Integer failures, Integer errors) {
        if (surefireReports < 0 || failsafeReports < 0) {
            return true;
        }
        if ((long) surefireReports + failsafeReports == 0) {
            return tests != null || failures != null || errors != null;
        }
        return tests == null || failures == null || errors == null || tests < 0 || failures < 0 || errors < 0
                || (long) failures + errors > tests;
    }

    /** Only a PASS has no failure kind, and a repairable failure needs failing tests and no errors. */
    private static boolean classificationDisagrees(String result, VerificationFailure failureKind, Integer tests,
                                                   Integer failures, Integer errors, List<String> missingReports) {
        if ("PASS".equals(result) != (failureKind == VerificationFailure.NONE)) {
            return true;
        }
        return failureKind.repairable() && (tests == null || failures == null || failures < 1
                || errors == null || errors != 0 || !missingReports.isEmpty());
    }
}
