package org.folio.factory.devfactory.pi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.folio.factory.sandbox.api.CommandResult;
import org.junit.jupiter.api.Test;

class Modsidecar208VerifierTest {

  @Test
  void parsesPerReportSurefireIdentity() {
    Modsidecar208Verifier.ReportSummary summary = new Modsidecar208Verifier().parseReportSummary(
        new CommandResult(0,
            "REPORT\t./target/surefire-reports/TEST-One.xml\ttests=2\tfailures=0\terrors=0\tskipped=0\n"
                + "REPORT\t./target/surefire-reports/TEST-Two.xml\ttests=3\tfailures=0\terrors=0\tskipped=1\n",
            "", 1));

    assertThat(summary.valid()).isTrue();
    assertThat(summary.totalTests()).isEqualTo(5);
    assertThat(summary.skipped()).isEqualTo(1);
    assertThat(summary.testsByReport()).containsExactlyInAnyOrderEntriesOf(Map.of(
        "./target/surefire-reports/TEST-One.xml", 2,
        "./target/surefire-reports/TEST-Two.xml", 3));
  }

  @Test
  void candidateMustRetainBaselineSuitesAndTestCounts() {
    Modsidecar208Verifier.ReportSummary baseline = new Modsidecar208Verifier.ReportSummary(
        true, 5, 0, 0, 0, Map.of("TEST-One.xml", 2, "TEST-Two.xml", 3));

    assertThat(new Modsidecar208Verifier.ReportSummary(true, 6, 0, 0, 0,
        Map.of("TEST-One.xml", 2, "TEST-Two.xml", 4, "TEST-Three.xml", 0))
        .preserves(baseline)).isTrue();
    assertThat(new Modsidecar208Verifier.ReportSummary(true, 3, 0, 0, 0,
        Map.of("TEST-One.xml", 3)).preserves(baseline)).isFalse();
    assertThat(new Modsidecar208Verifier.ReportSummary(true, 5, 0, 0, 1,
        Map.of("TEST-One.xml", 2, "TEST-Two.xml", 3)).preserves(baseline)).isFalse();
  }

  @Test
  void malformedReportIsNotAcceptedAsEvidence() {
    Modsidecar208Verifier.ReportSummary summary = new Modsidecar208Verifier().parseReportSummary(
        new CommandResult(0, "REPORT\tbroken\n", "", 1));

    assertThat(summary.valid()).isFalse();
    assertThat(summary.totalTests()).isZero();
  }

  @Test
  void duplicateReportIdentityIsNotAcceptedAsEvidence() {
    Modsidecar208Verifier.ReportSummary summary = new Modsidecar208Verifier().parseReportSummary(
        new CommandResult(0,
            "REPORT\tTEST-One.xml\ttests=2\tfailures=0\terrors=0\tskipped=0\n"
                + "REPORT\tTEST-One.xml\ttests=2\tfailures=0\terrors=0\tskipped=0\n",
            "", 1));

    assertThat(summary.valid()).isFalse();
  }
}
