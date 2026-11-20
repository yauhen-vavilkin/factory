package org.folio.factory.devfactory.verification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Executed-test evidence read from a verification workspace, whatever the toolchain. */
record TestEvidence(int reportCount, int testCount, int failureCount, int errorCount,
                    int failsafeReportCount, Map<String, Integer> executedByReport) {

    static TestEvidence none() {
        return new TestEvidence(0, 0, 0, 0, 0, Map.of());
    }

    List<String> missingRequiredReports(List<String> requirements) {
        List<String> missing = new ArrayList<>();
        for (String requirement : requirements) {
            if (!satisfies(requirement)) {
                missing.add(requirement);
            }
        }
        return List.copyOf(missing);
    }

    private boolean satisfies(String requirement) {
        MavenTestSuite suite = MavenTestSuite.fromRequirement(requirement);
        return suite != null && suite.hasExecutedReports(executedByReport);
    }
}
