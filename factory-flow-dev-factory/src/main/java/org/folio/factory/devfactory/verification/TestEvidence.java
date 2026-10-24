package org.folio.factory.devfactory.verification;

/** Executed-test evidence read from a verification workspace, whatever the toolchain. */
record TestEvidence(int reportCount, int testCount, int failureCount, int errorCount,
                    int failsafeReportCount, java.util.Map<String, Integer> executedByReport) {
}
