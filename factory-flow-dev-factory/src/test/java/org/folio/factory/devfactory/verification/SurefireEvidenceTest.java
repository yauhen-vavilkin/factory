package org.folio.factory.devfactory.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
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
        assertThat(SurefireEvidence.inspect(workspace, Instant.now()))
                .isEqualTo(new SurefireEvidence(1, 3, 1, 1));
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
}
