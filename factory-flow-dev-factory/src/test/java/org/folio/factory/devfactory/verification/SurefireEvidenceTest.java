package org.folio.factory.devfactory.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SurefireEvidenceTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {
            "<testsuite tests='1' skipped='0' failures='0' errors='0'>",
            "<testsuite tests='1' skipped='0' errors='0'/>",
            "<testsuite tests='1' skipped='0' failures='broken' errors='0'/>",
            "<testsuite tests='1' skipped='0' failures='-1' errors='0'/>",
            "<testsuite tests='1' skipped='0' failures='2147483648' errors='0'/>",
            "<testsuite tests='1' skipped='1' failures='1' errors='0'/>",
            "<testsuite tests='1' skipped='0' failures='1' errors='1'/>",
            "<testsuites tests='1' skipped='0' failures='0' errors='0'/>",
            "<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><testsuite tests='1' skipped='0' failures='0' errors='0'>&x;</testsuite>",
            "<!DOCTYPE testsuite SYSTEM 'https://example.invalid/report.dtd'><testsuite tests='1' skipped='0' failures='0' errors='0'/>"
    })
    void invalidFreshReportInvalidatesAllEvidence(String xml) throws Exception {
        report("valid", "<testsuite tests='1' skipped='0' failures='0' errors='0'/>");
        report("invalid", xml);
        assertThatThrownBy(() -> SurefireEvidence.inspect(workspace, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tests", "skipped", "failures", "errors"})
    void everyCounterIsRequired(String missing) throws Exception {
        report("missing", "<testsuite tests='1' skipped='0' failures='0' errors='0'/>"
                .replaceAll(" " + missing + "='[0-9]+'", ""));
        assertThatThrownBy(() -> SurefireEvidence.inspect(workspace, Instant.now()))
                .hasMessageContaining("Missing or invalid Surefire counter");
    }

    @Test
    void singleQuotedAttributesPreserveRealFailureCounts() throws Exception {
        report("single", "<testsuite tests = '4' skipped='1' failures = '1' errors='1'/>");
        var evidence = SurefireEvidence.inspect(workspace, Instant.now());
        assertThat(evidence.reportCount()).isEqualTo(1);
        assertThat(evidence.testCount()).isEqualTo(3);
        assertThat(evidence.failureCount()).isEqualTo(1);
        assertThat(evidence.errorCount()).isEqualTo(1);
    }

    @Test
    void staleReportsDoNotSupplyEvidence() throws Exception {
        Path report = report("stale", "<testsuite tests='1' skipped='0' failures='0' errors='0'/>");
        Files.setLastModifiedTime(report, FileTime.from(Instant.now().minusSeconds(60)));
        assertThat(SurefireEvidence.inspect(workspace, Instant.now()).reportCount()).isZero();
    }

    @Test
    void oversizedAndSymlinkReportsAreRejected() throws Exception {
        Path report = report("unsafe", " ".repeat(2 * 1024 * 1024 + 1));
        assertThatThrownBy(() -> SurefireEvidence.inspect(workspace, Instant.now()))
                .hasMessageContaining("exceeds evidence limit");
        Files.delete(report);
        Files.createSymbolicLink(report, workspace.resolve("outside.xml"));
        assertThatThrownBy(() -> SurefireEvidence.inspect(workspace, Instant.now()))
                .hasMessageContaining("Unsafe Surefire report");
    }

    private Path report(String name, String xml) throws Exception {
        Path reports = Files.createDirectories(workspace.resolve("module/target/surefire-reports"));
        return Files.writeString(reports.resolve("TEST-" + name + ".xml"), xml);
    }

    @Test
    void malformedFailsafeReportInvalidatesPassingUnitEvidence() throws Exception {
        report("unit", "<testsuite tests='1' skipped='0' failures='0' errors='0'/>");
        Path integration = Files.createDirectories(workspace.resolve("target/failsafe-reports"));
        Files.writeString(integration.resolve("TEST-integration.xml"),
                "<!DOCTYPE testsuite SYSTEM 'file:///etc/passwd'><testsuite tests='1' skipped='0' failures='0' errors='0'/>");
        assertThatThrownBy(() -> SurefireEvidence.inspect(workspace, Instant.now()))
                .hasMessageContaining("Invalid or unsafe Surefire XML");
    }

    @Test
    void requiredIntegrationCannotBeReplacedByPassingUnitTests() throws Exception {
        report("unit", "<testsuite tests='3' skipped='0' failures='0' errors='0'/>");

        var evidence = SurefireEvidence.inspect(workspace, Instant.now());

        assertThat(evidence.missingRequiredReports(List.of("surefire", "failsafe")))
                .containsExactly("failsafe");
    }

    @ParameterizedTest
    @CsvSource({"surefire,surefire-reports", "failsafe,failsafe-reports"})
    void suiteRequirementsAcceptNewAndRenamedClassesAcrossModules(String suite, String directory) throws Exception {
        Path firstModule = Files.createDirectories(workspace.resolve("first/target/" + directory));
        Path secondModule = Files.createDirectories(workspace.resolve("nested/second/target/" + directory));
        String passing = "<testsuite tests='3' skipped='1' failures='0' errors='0'/>";
        Files.writeString(firstModule.resolve("TEST-renamed.xml"), passing);
        Files.writeString(secondModule.resolve("TEST-new.xml"), passing);

        var evidence = SurefireEvidence.inspect(workspace, Instant.now());

        assertThat(evidence.missingRequiredReports(List.of(suite))).isEmpty();
        assertThat(evidence.testCount()).isEqualTo(4);
    }

    @ParameterizedTest
    @CsvSource({"surefire,surefire-reports", "failsafe,failsafe-reports"})
    void aPassingReportCannotHideAnotherEntirelySkippedReport(String suite, String directory) throws Exception {
        Path reports = Files.createDirectories(workspace.resolve("target/" + directory));
        Files.writeString(reports.resolve("TEST-passing.xml"),
                "<testsuite tests='2' skipped='0' failures='0' errors='0'/>");
        Files.writeString(reports.resolve("TEST-skipped.xml"),
                "<testsuite tests='2' skipped='2' failures='0' errors='0'/>");

        var evidence = SurefireEvidence.inspect(workspace, Instant.now());

        assertThat(evidence.missingRequiredReports(List.of(suite))).containsExactly(suite);
    }

    @Test
    void staleIntegrationReportsDoNotSatisfyRequiredSuite() throws Exception {
        report("unit", "<testsuite tests='1' skipped='0' failures='0' errors='0'/>");
        Path reports = Files.createDirectories(workspace.resolve("target/failsafe-reports"));
        Path stale = Files.writeString(reports.resolve("TEST-integration.xml"),
                "<testsuite tests='1' skipped='0' failures='0' errors='0'/>");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(60)));

        var evidence = SurefireEvidence.inspect(workspace, Instant.now());

        assertThat(evidence.missingRequiredReports(List.of("surefire", "failsafe")))
                .containsExactly("failsafe");
    }
}
