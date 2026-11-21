package org.folio.factory.devfactory.verification;

import java.nio.file.Path;
import java.util.Map;

/** Maven report families required by the operator's verification plan. */
public enum MavenTestSuite {
    SUREFIRE("surefire", "surefire-reports"),
    FAILSAFE("failsafe", "failsafe-reports");

    private final String configurationName;
    private final String reportDirectory;

    MavenTestSuite(String configurationName, String reportDirectory) {
        this.configurationName = configurationName;
        this.reportDirectory = reportDirectory;
    }

    public static boolean isSuiteRequirement(String requirement) {
        return fromRequirement(requirement) != null;
    }

    static MavenTestSuite fromRequirement(String requirement) {
        for (MavenTestSuite suite : values()) {
            if (suite.configurationName.equals(requirement)) {
                return suite;
            }
        }
        return null;
    }

    boolean hasExecutedReports(Map<String, Integer> executedByReport) {
        boolean found = false;
        for (Map.Entry<String, Integer> report : executedByReport.entrySet()) {
            Path directory = Path.of(report.getKey()).getParent();
            if (directory == null || !reportDirectory.equals(directory.getFileName().toString())) {
                continue;
            }
            if (report.getValue() < 1) {
                return false;
            }
            found = true;
        }
        return found;
    }
}
