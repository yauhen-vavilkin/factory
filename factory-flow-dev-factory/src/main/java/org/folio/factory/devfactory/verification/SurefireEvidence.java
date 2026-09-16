package org.folio.factory.devfactory.verification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Pattern;

/** Small Maven/Surefire proof: only fresh TEST-*.xml reports count as executed tests. */
record SurefireEvidence(int reportCount, int testCount, int failureCount, int errorCount) {
    private static final Pattern SUITE = Pattern.compile("<testsuite\\b([^>]*)>");
    private static final long MAX_REPORT_BYTES = 2L * 1024 * 1024;

    static SurefireEvidence inspect(Path workspace, Instant verificationStarted) {
        int reports = 0;
        int tests = 0;
        int failures = 0;
        int errors = 0;
        Instant oldestAccepted = verificationStarted.minusSeconds(2);
        try (var paths = Files.walk(workspace)) {
            for (Path report : paths.filter(Files::isRegularFile).toList()) {
                Path relative = workspace.relativize(report);
                if (!isSurefireReport(relative)
                        || Files.getLastModifiedTime(report).toInstant().isBefore(oldestAccepted)) continue;
                if (Files.size(report) > MAX_REPORT_BYTES) {
                    throw new IllegalStateException("Surefire report exceeds evidence limit: " + relative);
                }
                var matcher = SUITE.matcher(Files.readString(report));
                if (!matcher.find()) continue;
                String attributes = matcher.group(1);
                int suiteTests = attribute(attributes, "tests");
                int suiteSkipped = attribute(attributes, "skipped");
                int suiteFailures = attribute(attributes, "failures");
                int suiteErrors = attribute(attributes, "errors");
                if (suiteSkipped > suiteTests) throw new IllegalStateException("Invalid Surefire counters: " + relative);
                reports++;
                tests = Math.addExact(tests, suiteTests - suiteSkipped);
                failures = Math.addExact(failures, suiteFailures);
                errors = Math.addExact(errors, suiteErrors);
            }
            return new SurefireEvidence(reports, tests, failures, errors);
        } catch (IOException | ArithmeticException e) {
            throw new IllegalStateException("Cannot inspect fresh Surefire evidence", e);
        }
    }

    private static int attribute(String attributes, String name) {
        var matcher = Pattern.compile("(?:^|\\s)" + name + "=\"(\\d+)\"").matcher(attributes);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static boolean isSurefireReport(Path relative) {
        String name = relative.getFileName().toString();
        if (!name.startsWith("TEST-") || !name.endsWith(".xml")) return false;
        for (int i = 0; i + 1 < relative.getNameCount(); i++) {
            if (relative.getName(i).toString().equals("target")
                    && relative.getName(i + 1).toString().equals("surefire-reports")) return true;
        }
        return false;
    }
}
