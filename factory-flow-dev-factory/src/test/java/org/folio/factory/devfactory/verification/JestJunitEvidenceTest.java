package org.folio.factory.devfactory.verification;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JestJunitEvidenceTest {
    private static final String OK = "<testsuites><testsuite tests='1' skipped='0' failures='0' errors='0'/></testsuites>";
    @TempDir Path workspace;

    @Test
    void passingRunCountsExecutedTestsAndNotSkippedOnes() throws Exception {
        recorded("passing-with-skips.xml");
        var evidence = JestJunitEvidence.inspect(workspace, Instant.now());
        assertThat(evidence.reportCount()).isEqualTo(1);
        assertThat(evidence.failsafeReportCount()).isZero();
        assertThat(evidence.testCount()).isEqualTo(7);
        assertThat(evidence.failureCount()).isZero();
        assertThat(evidence.errorCount()).isZero();
        assertThat(evidence.executedByReport()).isEqualTo(Map.of(JestJunitEvidence.REPORT, 7));
    }

    @Test
    void failedTestIsCounted() throws Exception {
        recorded("failing-test.xml");
        var evidence = JestJunitEvidence.inspect(workspace, Instant.now());
        assertThat(evidence.testCount()).isEqualTo(9);
        assertThat(evidence.failureCount()).isEqualTo(1);
        assertThat(evidence.errorCount()).isZero();
    }

    @Test
    void suiteThatFailsToLoadIsOneFailedUnitNotInvalidEvidence() throws Exception {
        recorded("suite-load-error.xml");
        var evidence = JestJunitEvidence.inspect(workspace, Instant.now());
        assertThat(evidence.reportCount()).isEqualTo(1);
        assertThat(evidence.errorCount()).isEqualTo(1);
        assertThat(evidence.failureCount()).isZero();
        assertThat(evidence.testCount()).isEqualTo(2);
    }

    @Test
    void failuresAndErrorsNeverExceedTheTestsThatReceiptsAllow() throws Exception {
        report("<testsuites><testsuite tests='0' skipped='0' failures='2' errors='3'/></testsuites>");
        var evidence = JestJunitEvidence.inspect(workspace, Instant.now());
        assertThat(evidence.testCount()).isGreaterThanOrEqualTo(evidence.failureCount() + evidence.errorCount());
    }

    @Test
    void missingReportIsNoEvidence() {
        var evidence = JestJunitEvidence.inspect(workspace, Instant.now());
        assertThat(evidence.reportCount()).isZero();
        assertThat(evidence.testCount()).isZero();
    }

    @Test
    void staleReportDoesNotSupplyEvidence() throws Exception {
        Files.setLastModifiedTime(report(OK), FileTime.from(Instant.now().minusSeconds(60)));
        assertThat(JestJunitEvidence.inspect(workspace, Instant.now()).reportCount()).isZero();
    }

    @Test
    void reportWithoutTestsOrWithOnlySkippedTestsExecutedNothing() throws Exception {
        report("<testsuites/>");
        assertThat(JestJunitEvidence.inspect(workspace, Instant.now()).testCount()).isZero();
        report("<testsuites><testsuite tests='3' skipped='3' failures='0' errors='0'/></testsuites>");
        var evidence = JestJunitEvidence.inspect(workspace, Instant.now());
        assertThat(evidence.reportCount()).isEqualTo(1);
        assertThat(evidence.testCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<testsuites><testsuite tests='1' skipped='0' failures='0' errors='0'>",
            "<testsuites><testsuite tests='1' skipped='0' errors='0'/></testsuites>",
            "<testsuites><testsuite tests='1' skipped='0' failures='broken' errors='0'/></testsuites>",
            "<testsuites><testsuite tests='1' skipped='0' failures='-1' errors='0'/></testsuites>",
            "<testsuites><testsuite tests='1' skipped='0' failures='2147483648' errors='0'/></testsuites>",
            "<testsuites><testsuite tests='1' skipped='2' failures='0' errors='0'/></testsuites>",
            "<testsuite tests='1' skipped='0' failures='0' errors='0'/>",
            "<testsuites><testcase name='x'/></testsuites>",
            "<testsuites><testsuite tests='1' skipped='0' failures='0' errors='0'>"
                    + "<testsuite tests='1' skipped='0' failures='0' errors='0'/></testsuite></testsuites>",
            "<!DOCTYPE testsuites [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><testsuites>&x;</testsuites>",
            "<!DOCTYPE testsuites SYSTEM 'https://example.invalid/report.dtd'><testsuites/>"
    })
    void invalidReportIsInvalidEvidence(String xml) throws Exception {
        report(xml);
        assertThatThrownBy(() -> JestJunitEvidence.inspect(workspace, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tests", "skipped", "failures", "errors"})
    void everyCounterIsRequired(String missing) throws Exception {
        report(OK.replaceAll(" " + missing + "='[0-9]+'", ""));
        assertThatThrownBy(() -> JestJunitEvidence.inspect(workspace, Instant.now()))
                .hasMessageContaining("Missing or invalid Jest counter: " + missing);
    }

    @Test
    void counterOverflowIsInvalidEvidence() throws Exception {
        String suite = "<testsuite tests='2147483647' skipped='0' failures='2147483647' errors='0'/>";
        report("<testsuites>" + suite + suite + "</testsuites>");
        assertThatThrownBy(() -> JestJunitEvidence.inspect(workspace, Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Jest evidence");
    }

    @Test
    void oversizedReportIsRejected() throws Exception {
        report(" ".repeat(8 * 1024 * 1024 + 1));
        assertThatThrownBy(() -> JestJunitEvidence.inspect(workspace, Instant.now()))
                .hasMessageContaining("exceeds evidence limit");
    }

    @Test
    void directoryInPlaceOfReportIsRejected() throws Exception {
        Files.createDirectories(workspace.resolve(JestJunitEvidence.REPORT));
        assertThatThrownBy(() -> JestJunitEvidence.inspect(workspace, Instant.now()))
                .hasMessageContaining("Unsafe Jest report");
    }

    @Test
    void symlinkedReportIsRejected() throws Exception {
        Path outside = Files.writeString(workspace.resolveSibling("outside-" + System.nanoTime() + ".xml"), OK);
        try {
            Path link = workspace.resolve(JestJunitEvidence.REPORT);
            Files.createDirectories(link.getParent());
            symlink(link, outside);
            assertThatThrownBy(() -> JestJunitEvidence.inspect(workspace, Instant.now()))
                    .hasMessageContaining("Unsafe Jest report");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void symlinkedReportDirectoryIsNotFollowedOutOfTheWorkspace() throws Exception {
        Path elsewhere = Files.createDirectories(workspace.resolveSibling("elsewhere-" + System.nanoTime()));
        try {
            Files.createDirectories(elsewhere.resolve("jest-junit"));
            Files.writeString(elsewhere.resolve("jest-junit/junit.xml"), OK);
            symlink(workspace.resolve("artifacts"), elsewhere);
            assertThatThrownBy(() -> JestJunitEvidence.inspect(workspace, Instant.now()))
                    .hasMessageContaining("Unsafe Jest report");
        } finally {
            try (var paths = Files.walk(elsewhere)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void symlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException | UnsupportedOperationException e) {
            Assumptions.abort("Symbolic links are not permitted on this system: " + e.getMessage());
        }
    }

    private Path report(String xml) throws Exception {
        Path report = workspace.resolve(JestJunitEvidence.REPORT);
        Files.createDirectories(report.getParent());
        return Files.writeString(report, xml);
    }

    private void recorded(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/jest-junit/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            Path report = workspace.resolve(JestJunitEvidence.REPORT);
            Files.createDirectories(report.getParent());
            Files.write(report, in.readAllBytes());
        }
    }
}
