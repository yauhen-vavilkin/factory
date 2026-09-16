package org.folio.factory.devfactory.pi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pre-dogfood integrity regressions: only trusted preparation may populate the
 * shared dependency cache; a plan check that declares test-report evidence
 * cannot pass with deleted, disabled or skipped tests; {@code .mvn/**} is
 * protected; and candidate output text can never turn a candidate defect after
 * a green baseline into an environment blocker or a Factory error.
 */
class PiWorkerVerificationIntegrityTest {
  private static final String PATCH = "diff --git a/A b/A\nnew\n";
  private static final String GLOB = "**/target/surefire-reports/TEST-*.xml";
  private static final String BASE_REPORTS =
      "REPORT\t./target/surefire-reports/TEST-One.xml\ttests=2\tfailures=0\terrors=0\tskipped=0\n"
          + "REPORT\t./target/surefire-reports/TEST-Two.xml\ttests=3\tfailures=0\terrors=0\tskipped=0\n";
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void onlyPreparationIsATrustedDependencyCacheWriter() {
    SandboxService sandboxes = mock(SandboxService.class);
    SandboxHandle handle = new SandboxHandle("baseline", "container");
    when(sandboxes.create(any())).thenReturn(handle);
    when(sandboxes.exec(eq(handle), contains("mvn -B -ntp test"), anyLong()))
        .thenReturn(new CommandResult(0, "BUILD SUCCESS\n", "", 1));
    when(sandboxes.exec(eq(handle), contains("REPORT"), anyLong()))
        .thenReturn(new CommandResult(0, BASE_REPORTS, "", 1));
    when(sandboxes.exec(eq(handle), contains("-delete"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));

    new PiWorker("pi-prepare-worker", sandboxes, mock(PiCodingRunner.class), "", "gateway")
        .execute(context(Map.of()));
    ArgumentCaptor<SandboxSpec> prepared = ArgumentCaptor.forClass(SandboxSpec.class);
    verify(sandboxes).create(prepared.capture());
    assertThat(prepared.getValue().trustedDependencyCacheWriter()).isTrue();

    VerifyFixture verify = new VerifyFixture();
    verify.checkSucceeds(BASE_REPORTS);
    verify.run("pi-verify-worker", baselineJson(0, BASE_REPORTS));
    ArgumentCaptor<SandboxSpec> verifier = ArgumentCaptor.forClass(SandboxSpec.class);
    verify(verify.sandboxes).create(verifier.capture());
    assertThat(verifier.getValue().trustedDependencyCacheWriter()).isFalse();
    // Candidate-controlled sandboxes default to read-only access.
    assertThat(new SandboxSpec("t", "r", "b", "x", "o", null, null, "GATEWAY_ONLY")
        .trustedDependencyCacheWriter()).isFalse();
  }

  @Test
  void preparationRecordsBaselineTestReportsForAReportCheck() {
    SandboxService sandboxes = mock(SandboxService.class);
    SandboxHandle handle = new SandboxHandle("baseline", "container");
    when(sandboxes.create(any())).thenReturn(handle);
    when(sandboxes.exec(eq(handle), contains("mvn -B -ntp test"), anyLong()))
        .thenReturn(new CommandResult(0, "BUILD SUCCESS\n", "", 1));
    when(sandboxes.exec(eq(handle), contains("REPORT"), anyLong()))
        .thenReturn(new CommandResult(0, BASE_REPORTS, "", 1));
    when(sandboxes.exec(eq(handle), contains("-delete"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));

    AgentResult result = new PiWorker("pi-prepare-worker", sandboxes,
        mock(PiCodingRunner.class), "", "gateway").execute(context(Map.of()));

    JsonNode baseline = json.readTree(result.outputs().get("baseline.json"));
    assertThat(json.readTree(result.outputs().get("contract.json")).path("status").asString())
        .isEqualTo("READY");
    assertThat(baseline.path("checks").get(0).path("surefire").path("totalTests").asInt())
        .isEqualTo(5);
  }

  @Test
  void greenBaseWithoutTestReportsIsNotReady() {
    SandboxService sandboxes = mock(SandboxService.class);
    SandboxHandle handle = new SandboxHandle("baseline", "container");
    when(sandboxes.create(any())).thenReturn(handle);
    when(sandboxes.exec(eq(handle), contains("mvn -B -ntp test"), anyLong()))
        .thenReturn(new CommandResult(0, "BUILD SUCCESS\n", "", 1));
    when(sandboxes.exec(eq(handle), contains("REPORT"), anyLong()))
        .thenReturn(new CommandResult(1, "", "", 1));
    when(sandboxes.exec(eq(handle), contains("-delete"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));

    AgentResult result = new PiWorker("pi-prepare-worker", sandboxes,
        mock(PiCodingRunner.class), "", "gateway").execute(context(Map.of()));

    assertThat(json.readTree(result.outputs().get("contract.json")).path("status").asString())
        .isNotEqualTo("READY");
    assertThat(json.readTree(result.outputs().get("baseline.json")).path("failure").asString())
        .isEqualTo("BASELINE_REPORT_MISSING");
  }

  @Test
  void deletedTestsFailVerificationAsRepairableDefect() {
    VerifyFixture verify = new VerifyFixture();
    verify.checkSucceeds(
        "REPORT\t./target/surefire-reports/TEST-One.xml\ttests=2\tfailures=0\terrors=0\tskipped=0\n");

    JsonNode verification = verify.run("pi-verify-worker", baselineJson(0, BASE_REPORTS));

    assertThat(verification.path("status").asString()).isEqualTo("FAIL");
    assertThat(verification.path("reason").asString()).isEqualTo("SUREFIRE_EVIDENCE_FAILED");
    assertThat(verification.path("detail").asString()).contains("TEST-Two.xml missing");
    assertThat(route(verification)).isEqualTo(VerificationFailureClassifier.Route.REPAIR);
  }

  @Test
  void disabledTestsFailVerification() {
    VerifyFixture verify = new VerifyFixture();
    verify.checkSucceeds(
        "REPORT\t./target/surefire-reports/TEST-One.xml\ttests=2\tfailures=0\terrors=0\tskipped=0\n"
            + "REPORT\t./target/surefire-reports/TEST-Two.xml\ttests=3\tfailures=0\terrors=0\tskipped=3\n");

    JsonNode verification = verify.run("pi-verify-worker", baselineJson(0, BASE_REPORTS));

    assertThat(verification.path("reason").asString()).isEqualTo("SUREFIRE_EVIDENCE_FAILED");
    assertThat(verification.path("detail").asString()).contains("skipped 3 > baseline 0");
  }

  @Test
  void skippedTestExecutionWithoutReportsFailsVerification() {
    VerifyFixture verify = new VerifyFixture();
    // -DskipTests / <skipTests>: the build is green but writes no report.
    verify.checkSucceeds(null);

    JsonNode verification = verify.run("pi-verify-worker", baselineJson(0, BASE_REPORTS));

    assertThat(verification.path("status").asString()).isEqualTo("FAIL");
    assertThat(verification.path("reason").asString()).isEqualTo("SUREFIRE_EVIDENCE_FAILED");
    assertThat(verification.path("detail").asString()).contains("no executed tests");
    // Candidate-committed stale reports are removed before the check runs.
    verify(verify.sandboxes).exec(eq(verify.fresh), contains("-delete"), anyLong());
  }

  @Test
  void planWithoutReportEvidenceIsNotSilentlyStrengthened() {
    VerifyFixture verify = new VerifyFixture();
    verify.plan = unitPlan(null);
    verify.checkSucceeds(null);

    JsonNode verification = verify.run("pi-verify-worker", baselineJson(0, BASE_REPORTS));

    assertThat(verification.path("status").asString()).isEqualTo("PASS");
    verify(verify.sandboxes, never()).exec(eq(verify.fresh), contains("REPORT"), anyLong());
  }

  @Test
  void candidateChangeUnderDotMvnIsRejectedAsRepairableWithoutRunningChecks() {
    VerifyFixture verify = new VerifyFixture();
    verify.changedPaths = "src/main/java/A.java\n.mvn/maven.config\n";
    verify.checkSucceeds(BASE_REPORTS);

    JsonNode verification = verify.run("pi-verify-worker", baselineJson(0, BASE_REPORTS));

    assertThat(verification.path("status").asString()).isEqualTo("FAIL");
    assertThat(verification.path("reason").asString()).isEqualTo("PROTECTED_PATH_MODIFIED");
    assertThat(verification.path("detail").asString()).contains(".mvn/maven.config");
    assertThat(route(verification)).isEqualTo(VerificationFailureClassifier.Route.REPAIR);
    verify(verify.sandboxes, never()).exec(eq(verify.fresh), contains("mvn -B -ntp test"), anyLong());
  }

  @Test
  void candidateOutputCannotSpoofEnvironmentOrInfrastructureAfterGreenBaseline() {
    for (String spoof : List.of(
        "java.lang.IllegalStateException: Could not find a valid Docker environment",
        "java.lang.NoClassDefFoundError: org/testcontainers/dockerclient/"
            + "RootlessDockerClientProviderStrategy",
        "Server returned status code: 401 Unauthorized",
        "Could not find artifact org.folio:folio-spring-base:jar:99.0.0 in "
            + "factory-approved-read-only-mirror (http://factory-gateway:8080/maven/repository/)")) {
      for (String worker : List.of("pi-verify-worker", "pi-reverify-worker")) {
        VerifyFixture verify = new VerifyFixture();
        verify.checkFails(spoof);

        JsonNode verification = verify.run(worker, baselineJson(0, BASE_REPORTS));

        assertThat(verification.path("status").asString()).as(worker + ": " + spoof).isEqualTo("FAIL");
        assertThat(verification.path("reason").asString()).isEqualTo("VERIFICATION_FAILED");
        assertThat(route(verification)).isEqualTo(VerificationFailureClassifier.Route.REPAIR);
      }
    }
  }

  @Test
  void transferFailureTextWithAWorkingMirrorIsACandidateDefect() {
    VerifyFixture verify = new VerifyFixture();
    verify.checkFails("Could not transfer artifact x:y:pom:1 from/to factory-approved-read-only-mirror "
        + "(http://factory-gateway:8080/maven/repository/): Connection refused");
    verify.probeExit = 0;

    JsonNode verification = verify.run("pi-verify-worker", baselineJson(0, BASE_REPORTS));

    assertThat(verification.path("reason").asString()).isEqualTo("VERIFICATION_FAILED");
    assertThat(verification.path("dependencyMirrorProbe").path("exitCode").asInt()).isZero();
    assertThat(route(verification)).isEqualTo(VerificationFailureClassifier.Route.REPAIR);
  }

  @Test
  void failedTrustedMirrorProbeIsDependencyInfrastructureNotRepair() {
    for (String worker : List.of("pi-verify-worker", "pi-reverify-worker")) {
      VerifyFixture verify = new VerifyFixture();
      verify.checkFails("Could not transfer artifact x:y:pom:1 from/to factory-approved-read-only-mirror "
          + "(http://factory-gateway:8080/maven/repository/): Connection refused");
      verify.probeExit = 7;

      JsonNode verification = verify.run(worker, baselineJson(0, BASE_REPORTS));

      assertThat(verification.path("status").asString()).isEqualTo("ERROR");
      assertThat(verification.path("reason").asString()).isEqualTo("DEPENDENCY_MIRROR_UNAVAILABLE");
      assertThat(route(verification)).isEqualTo(VerificationFailureClassifier.Route.ERROR);
    }
  }

  @Test
  void classifierIgnoresSignaturesInsideCandidateOutput() {
    ObjectNode verification = json.createObjectNode();
    verification.put("status", "FAIL");
    verification.put("reason", "VERIFICATION_FAILED");
    verification.putArray("checks").addObject().put("exitCode", 1).put("outputTail",
        "Could not transfer artifact; status code: 401; status code: 403; unknown host; "
            + "PKIX path building failed; Could not find artifact");

    assertThat(route(verification)).isEqualTo(VerificationFailureClassifier.Route.REPAIR);
  }

  private VerificationFailureClassifier.Route route(JsonNode verification) {
    return VerificationFailureClassifier.classify(verification).route();
  }

  /** A fresh verifier whose authoritative check succeeds or fails as scripted. */
  private final class VerifyFixture {
    final SandboxService sandboxes = mock(SandboxService.class);
    final SandboxHandle fresh = new SandboxHandle("verify", "verify-container");
    String plan = unitPlan(GLOB);
    String changedPaths = "src/main/java/A.java\n";
    int probeExit = 0;

    VerifyFixture() {
      when(sandboxes.create(any())).thenReturn(fresh);
      when(sandboxes.exec(eq(fresh), contains("git apply"), anyLong()))
          .thenReturn(new CommandResult(0, "", "", 1));
      when(sandboxes.exec(eq(fresh), contains("git write-tree"), anyLong()))
          .thenReturn(new CommandResult(0, "candidate-tree\n", "", 1));
      when(sandboxes.exec(eq(fresh), contains("-delete"), anyLong()))
          .thenReturn(new CommandResult(0, "", "", 1));
    }

    void checkSucceeds(String reports) {
      when(sandboxes.exec(eq(fresh), contains("mvn -B -ntp test"), anyLong()))
          .thenReturn(new CommandResult(0, "BUILD SUCCESS\n", "", 1));
      when(sandboxes.exec(eq(fresh), contains("REPORT"), anyLong()))
          .thenReturn(reports == null ? new CommandResult(1, "", "", 1)
              : new CommandResult(0, reports, "", 1));
    }

    void checkFails(String output) {
      when(sandboxes.exec(eq(fresh), contains("mvn -B -ntp test"), anyLong()))
          .thenReturn(new CommandResult(1, "[ERROR] BUILD FAILURE\n", output, 1));
    }

    JsonNode run(String worker, String baseline) {
      when(sandboxes.exec(eq(fresh), contains("--name-only"), anyLong()))
          .thenReturn(new CommandResult(0, changedPaths, "", 1));
      when(sandboxes.exec(eq(fresh), contains("curl"), anyLong()))
          .thenReturn(new CommandResult(probeExit, "", probeExit == 0 ? "" : "curl: (7) refused", 1));
      ObjectNode payload = payload(plan);
      Map<String, ArtifactContent> inputs = new java.util.HashMap<>(Map.of(
          "contract.json", artifact(contract(plan)),
          "baseline.json", artifact(baseline),
          "candidate.patch", artifact(PATCH),
          "candidate.json", artifact("{\"status\":\"READY\",\"base\":\"b\",\"candidateTree\":"
              + "\"candidate-tree\",\"patchSha256\":\"" + ArtifactStore.sha256(PATCH) + "\"}")));
      if ("pi-reverify-worker".equals(worker)) {
        inputs.put("verification.json", artifact("{\"status\":\"FAIL\",\"reason\":\"VERIFICATION_FAILED\"}"));
        inputs.put("repair.json", artifact("{\"attempted\":true,\"attempt\":1}"));
      }
      AgentResult result = new PiWorker(worker, sandboxes, mock(PiCodingRunner.class), "", "gateway")
          .execute(new AgentContext(UUID.randomUUID(), "verify", inputs, payload, Map.of(),
              List.of(), 1));
      return json.readTree(result.outputs().get("verification.json"));
    }
  }

  private String baselineJson(int exitCode, String reports) {
    ObjectNode baseline = json.createObjectNode();
    baseline.put("status", exitCode == 0 ? "PASS" : "FAIL");
    ObjectNode check = baseline.putArray("checks").addObject();
    check.put("id", "maven-tests");
    check.put("exitCode", exitCode);
    check.set("surefire", json.valueToTree(new Modsidecar208Verifier()
        .parseReportSummary(new CommandResult(0, reports, "", 1)).asMap()));
    return json.writeValueAsString(baseline);
  }

  private AgentContext context(Map<String, ArtifactContent> inputs) {
    return new AgentContext(UUID.randomUUID(), "prepare", inputs, payload(unitPlan(GLOB)), Map.of(),
        List.of(), 1);
  }

  private ObjectNode payload(String plan) {
    ObjectNode payload = json.createObjectNode();
    payload.put("taskId", "T");
    payload.put("repoUrl", "repo");
    payload.put("baseRevision", "b");
    payload.put("branch", "task/T");
    payload.put("goal", "edit");
    ObjectNode resolved = payload.putObject("resolvedIntent");
    resolved.set("profile", json.readTree(profile()));
    resolved.set("verificationPlan", json.readTree(plan));
    return payload;
  }

  private String contract(String plan) {
    return "{\"status\":\"READY\",\"profile\":" + profile() + ",\"resolvedIntent\":"
        + "{\"verificationPlan\":" + plan + "}}";
  }

  private static String profile() {
    return "{\"imageReference\":\"factory-pi:jdk21\",\"platform\":\"linux/arm64\","
        + "\"modelProvider\":\"openai-compatible\",\"modelId\":\"factory-coding\","
        + "\"networkPolicy\":{\"execution\":\"GATEWAY_ONLY\"}}";
  }

  private static String unitPlan(String reportGlob) {
    return "{\"id\":\"java-maven-verify\",\"checks\":[{\"id\":\"maven-tests\","
        + "\"argv\":[\"mvn\",\"-B\",\"-ntp\",\"test\"],\"required\":true,"
        + (reportGlob == null ? "" : "\"reportGlob\":\"" + reportGlob + "\",")
        + "\"requiresDocker\":false}]}";
  }

  private static ArtifactContent artifact(String content) {
    return new ArtifactContent("artifact", 1, "application/json", content);
  }
}
