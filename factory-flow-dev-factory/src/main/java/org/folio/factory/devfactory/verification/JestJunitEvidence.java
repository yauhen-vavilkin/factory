package org.folio.factory.devfactory.verification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import org.w3c.dom.Element;

/** Jest proof: only a fresh jest-junit report at the trusted path counts as executed tests. */
final class JestJunitEvidence {
    static final String REPORT = "artifacts/jest-junit/junit.xml";
    private static final int MAX_REPORT_BYTES = 8 * 1024 * 1024;

    private JestJunitEvidence() {}

    static TestEvidence inspect(Path workspace, Instant verificationStarted) {
        Path report = workspace;
        for (Path part : Path.of(REPORT)) {
            report = report.resolve(part);
            if (Files.isSymbolicLink(report)) throw new IllegalStateException("Unsafe Jest report file");
            if (!Files.exists(report, LinkOption.NOFOLLOW_LINKS)) return new TestEvidence(0, 0, 0, 0, 0, java.util.Map.of());
        }
        try {
            if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Unsafe Jest report file");
            if (Files.getLastModifiedTime(report, LinkOption.NOFOLLOW_LINKS).toInstant()
                    .isBefore(verificationStarted.minusSeconds(2))) {
                return new TestEvidence(0, 0, 0, 0, 0, java.util.Map.of());
            }
            byte[] xml;
            try (var input = Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS)) {
                xml = input.readNBytes(MAX_REPORT_BYTES + 1);
            }
            if (xml.length > MAX_REPORT_BYTES) throw new IllegalStateException("Jest report exceeds evidence limit");
            Element root = JunitXml.parse(xml, "Jest");
            if (!"testsuites".equals(root.getTagName())) throw new IllegalStateException("Invalid Jest report root");
            int tests = 0;
            int failures = 0;
            int errors = 0;
            for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element suite)) continue;
                if (!"testsuite".equals(suite.getTagName()) || suite.getElementsByTagName("testsuite").getLength() != 0) {
                    throw new IllegalStateException("Invalid Jest report suite");
                }
                int suiteTests = JunitXml.counter(suite, "tests", "Jest");
                int suiteSkipped = JunitXml.counter(suite, "skipped", "Jest");
                int suiteFailures = JunitXml.counter(suite, "failures", "Jest");
                int suiteErrors = JunitXml.counter(suite, "errors", "Jest");
                if (suiteSkipped > suiteTests) throw new IllegalStateException("Invalid Jest counters");
                int suiteProblems = Math.addExact(suiteFailures, suiteErrors);
                // A suite that fails to load reports tests="0" errors="1": it still counts as one executed, failed unit.
                tests = Math.addExact(tests, Math.max(suiteTests - suiteSkipped, suiteProblems));
                failures = Math.addExact(failures, suiteFailures);
                errors = Math.addExact(errors, suiteErrors);
            }
            return new TestEvidence(1, tests, failures, errors, 0, java.util.Map.of(REPORT, tests));
        } catch (IOException | UncheckedIOException | ArithmeticException e) {
            throw new IllegalStateException("Cannot inspect fresh Jest evidence", e);
        }
    }
}
