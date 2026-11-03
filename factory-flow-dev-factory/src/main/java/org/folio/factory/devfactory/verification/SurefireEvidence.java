package org.folio.factory.devfactory.verification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import org.w3c.dom.Element;

/** Maven proof: only fresh Surefire/Failsafe TEST-*.xml reports count as executed tests. */
final class SurefireEvidence {
    private static final int MAX_REPORT_BYTES = 2 * 1024 * 1024;

    private SurefireEvidence() {}

    static TestEvidence inspect(Path workspace, Instant verificationStarted) {
        int reports = 0;
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int failsafeReports = 0;
        var executedByReport = new java.util.LinkedHashMap<String, Integer>();
        Instant oldestAccepted = verificationStarted.minusSeconds(2);
        try (var paths = Files.walk(workspace)) {
            for (Path report : paths.toList()) {
                Path relative = workspace.relativize(report);
                if (!isSurefireReport(relative)) continue;
                if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("Unsafe Surefire report file");
                }
                if (Files.getLastModifiedTime(report, LinkOption.NOFOLLOW_LINKS).toInstant()
                        .isBefore(oldestAccepted)) continue;
                if (Files.size(report) > MAX_REPORT_BYTES) {
                    throw new IllegalStateException("Surefire report exceeds evidence limit");
                }
                byte[] xml;
                try (var input = Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS)) {
                    xml = input.readNBytes(MAX_REPORT_BYTES + 1);
                }
                if (xml.length > MAX_REPORT_BYTES) throw new IllegalStateException("Surefire report exceeds evidence limit");
                Element suite = parse(xml);
                int suiteTests = JunitXml.counter(suite, "tests", "Surefire");
                int suiteSkipped = JunitXml.counter(suite, "skipped", "Surefire");
                int suiteFailures = JunitXml.counter(suite, "failures", "Surefire");
                int suiteErrors = JunitXml.counter(suite, "errors", "Surefire");
                if ((long) suiteSkipped + suiteFailures + suiteErrors > suiteTests) {
                    throw new IllegalStateException("Invalid Surefire counters");
                }
                reports++;
                if (relative.getParent().getFileName().toString().equals("failsafe-reports")) failsafeReports++;
                executedByReport.put(relative.toString().replace('\\', '/'), suiteTests - suiteSkipped);
                tests = Math.addExact(tests, suiteTests - suiteSkipped);
                failures = Math.addExact(failures, suiteFailures);
                errors = Math.addExact(errors, suiteErrors);
            }
            return new TestEvidence(reports, tests, failures, errors, failsafeReports, java.util.Map.copyOf(executedByReport));
        } catch (IOException | UncheckedIOException | ArithmeticException e) {
            throw new IllegalStateException("Cannot inspect fresh Surefire evidence", e);
        }
    }

    private static Element parse(byte[] xml) {
        var root = JunitXml.parse(xml, "Surefire");
        if (!"testsuite".equals(root.getTagName()) || root.getElementsByTagName("testsuite").getLength() != 0) {
            throw new IllegalStateException("Invalid Surefire report root");
        }
        return root;
    }

    private static boolean isSurefireReport(Path relative) {
        String name = relative.getFileName().toString();
        if (!name.startsWith("TEST-") || !name.endsWith(".xml")) return false;
        for (int i = 0; i + 1 < relative.getNameCount(); i++) {
            if (relative.getName(i).toString().equals("target")
                    && (relative.getName(i + 1).toString().equals("surefire-reports")
                    || relative.getName(i + 1).toString().equals("failsafe-reports"))
                    && i + 3 == relative.getNameCount()) return true;
        }
        return false;
    }
}
