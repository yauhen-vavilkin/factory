package org.folio.factory.devfactory.verification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Element;

/** Maven proof: only fresh Surefire/Failsafe TEST-*.xml reports count as executed tests. */
final class SurefireEvidence {
    private static final int MAX_REPORT_BYTES = 2 * 1024 * 1024;
    private static final String KIND = "Surefire";

    private SurefireEvidence() {
    }

    static TestEvidence inspect(Path workspace, Instant verificationStarted) {
        var oldestAccepted = verificationStarted.minusSeconds(2);
        var totals = new Totals();
        try (var paths = Files.walk(workspace)) {
            for (Path report : paths.toList()) {
                var relative = workspace.relativize(report);
                if (!isSurefireReport(relative)) {
                    continue;
                }
                readFreshReport(report, oldestAccepted)
                        .ifPresent(xml -> totals.add(relative, SuiteCounts.of(parse(xml))));
            }
            return totals.toEvidence();
        } catch (IOException | UncheckedIOException | ArithmeticException e) {
            throw new IllegalStateException("Cannot inspect fresh Surefire evidence", e);
        }
    }

    /** The report bytes, or empty for a report written before this verification run. */
    private static Optional<byte[]> readFreshReport(Path report, Instant oldestAccepted) throws IOException {
        if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Unsafe Surefire report file");
        }
        if (Files.getLastModifiedTime(report, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(oldestAccepted)) {
            return Optional.empty();
        }
        if (Files.size(report) > MAX_REPORT_BYTES) {
            throw new IllegalStateException("Surefire report exceeds evidence limit");
        }
        byte[] xml;
        try (var input = Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS)) {
            xml = input.readNBytes(MAX_REPORT_BYTES + 1);
        }
        if (xml.length > MAX_REPORT_BYTES) {
            throw new IllegalStateException("Surefire report exceeds evidence limit");
        }
        return Optional.of(xml);
    }

    private static Element parse(byte[] xml) {
        var root = JunitXml.parse(xml, KIND);
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

    private record SuiteCounts(int tests, int skipped, int failures, int errors) {
        static SuiteCounts of(Element suite) {
            var counts = new SuiteCounts(JunitXml.counter(suite, "tests", KIND),
                    JunitXml.counter(suite, "skipped", KIND), JunitXml.counter(suite, "failures", KIND),
                    JunitXml.counter(suite, "errors", KIND));
            if ((long) counts.skipped() + counts.failures() + counts.errors() > counts.tests()) {
                throw new IllegalStateException("Invalid Surefire counters");
            }
            return counts;
        }

        int executed() {
            return tests - skipped;
        }
    }

    private static final class Totals {
        private final Map<String, Integer> executedByReport = new LinkedHashMap<>();
        private int reports;
        private int tests;
        private int failures;
        private int errors;
        private int failsafeReports;

        void add(Path relative, SuiteCounts suite) {
            reports++;
            if (relative.getParent().getFileName().toString().equals("failsafe-reports")) {
                failsafeReports++;
            }
            executedByReport.put(relative.toString().replace('\\', '/'), suite.executed());
            tests = Math.addExact(tests, suite.executed());
            failures = Math.addExact(failures, suite.failures());
            errors = Math.addExact(errors, suite.errors());
        }

        TestEvidence toEvidence() {
            return new TestEvidence(reports, tests, failures, errors, failsafeReports,
                    Map.copyOf(executedByReport));
        }
    }
}
