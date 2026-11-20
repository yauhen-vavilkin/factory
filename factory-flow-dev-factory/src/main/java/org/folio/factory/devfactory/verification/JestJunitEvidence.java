package org.folio.factory.devfactory.verification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Element;

/** Jest proof: only a fresh jest-junit report at the trusted path counts as executed tests. */
final class JestJunitEvidence {
    static final String REPORT = "artifacts/jest-junit/junit.xml";
    private static final int MAX_REPORT_BYTES = 8 * 1024 * 1024;
    private static final String KIND = "Jest";

    private JestJunitEvidence() {
    }

    static TestEvidence inspect(Path workspace, Instant verificationStarted) {
        try {
            return reportPath(workspace)
                    .flatMap(report -> readFreshReport(report, verificationStarted.minusSeconds(2)))
                    .map(JestJunitEvidence::evidence)
                    .orElseGet(TestEvidence::none);
        } catch (UncheckedIOException | ArithmeticException e) {
            throw new IllegalStateException("Cannot inspect fresh Jest evidence", e);
        }
    }

    /** Empty when a part of the trusted path is missing; a link anywhere on it is refused. */
    private static Optional<Path> reportPath(Path workspace) {
        Path report = workspace;
        for (Path part : Path.of(REPORT)) {
            report = report.resolve(part);
            if (Files.isSymbolicLink(report)) {
                throw new IllegalStateException("Unsafe Jest report file");
            }
            if (!Files.exists(report, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
        }
        return Optional.of(report);
    }

    /** The report bytes, or empty for a report written before this verification run. */
    private static Optional<byte[]> readFreshReport(Path report, Instant oldestAccepted) {
        try {
            if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Unsafe Jest report file");
            }
            if (Files.getLastModifiedTime(report, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(oldestAccepted)) {
                return Optional.empty();
            }
            byte[] xml;
            try (var input = Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS)) {
                xml = input.readNBytes(MAX_REPORT_BYTES + 1);
            }
            if (xml.length > MAX_REPORT_BYTES) {
                throw new IllegalStateException("Jest report exceeds evidence limit");
            }
            return Optional.of(xml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TestEvidence evidence(byte[] xml) {
        Element root = JunitXml.parse(xml, KIND);
        if (!"testsuites".equals(root.getTagName())) {
            throw new IllegalStateException("Invalid Jest report root");
        }
        var totals = new SuiteTotals();
        for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element suite) {
                totals.add(suite);
            }
        }
        return new TestEvidence(1, totals.tests, totals.failures, totals.errors, 0, Map.of(REPORT, totals.tests));
    }

    private static final class SuiteTotals {
        private int tests;
        private int failures;
        private int errors;

        void add(Element suite) {
            if (!"testsuite".equals(suite.getTagName()) || suite.getElementsByTagName("testsuite").getLength() != 0) {
                throw new IllegalStateException("Invalid Jest report suite");
            }
            int suiteTests = JunitXml.counter(suite, "tests", KIND);
            int suiteSkipped = JunitXml.counter(suite, "skipped", KIND);
            int suiteFailures = JunitXml.counter(suite, "failures", KIND);
            int suiteErrors = JunitXml.counter(suite, "errors", KIND);
            if (suiteSkipped > suiteTests) {
                throw new IllegalStateException("Invalid Jest counters");
            }
            int suiteProblems = Math.addExact(suiteFailures, suiteErrors);
            // A suite that fails to load reports tests="0" errors="1": it still counts as one executed, failed unit.
            tests = Math.addExact(tests, Math.max(suiteTests - suiteSkipped, suiteProblems));
            failures = Math.addExact(failures, suiteFailures);
            errors = Math.addExact(errors, suiteErrors);
        }
    }
}
