package org.folio.factory.devfactory.pi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.folio.factory.devfactory.profile.TrustedProfileCatalog;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Regression coverage for Milestone 1 honest readiness and authoritative
 * verification: the resolved verification plan is the gate (no hidden
 * stronger command), a required Docker capability the sandbox cannot provide
 * blocks execution before any coding spend, and environment failures are
 * never classified as coding failures.
 */
class PiWorkerReadinessVerificationTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void trustedCatalogDeclaresExecutableUnitPlanAndHonestItPlan() {
    TrustedProfileCatalog catalog = new TrustedProfileCatalog();
    var unit = catalog.verificationPlan(TrustedProfileCatalog.JAVA_MAVEN_VERIFY).orElseThrow();
    assertThat(unit.checks()).hasSize(1);
    assertThat(unit.checks().getFirst().argv())
        .containsExactly("mvn", "-B", "-ntp", "test");
    assertThat(unit.checks().getFirst().requiresDocker()).isFalse();

    var it = catalog.verificationPlan(TrustedProfileCatalog.JAVA_MAVEN_VERIFY_IT).orElseThrow();
    assertThat(it.checks()).hasSize(1);
    assertThat(it.checks().getFirst().argv())
        .containsExactly("mvn", "-B", "-ntp", "clean", "verify");
    assertThat(it.checks().getFirst().requiresDocker()).isTrue();
  }

  @Test
  void prepareBlocksWhenRequiredDockerCapabilityIsUnavailable() {
    SandboxService sandboxes = mock(SandboxService.class);
    SandboxHandle handle = new SandboxHandle("baseline", "container");
    when(sandboxes.create(any())).thenReturn(handle);
    when(sandboxes.exec(eq(handle), contains("docker"), anyLong()))
        .thenReturn(new CommandResult(0, "DOCKER=UNAVAILABLE\n", "", 1));
    ObjectNode payload = genericPayload(itPlanJson());

    AgentResult result = new PiWorker("pi-prepare-worker", sandboxes,
        mock(PiCodingRunner.class), "", "gateway").execute(context(payload, Map.of()));

    var contract = json.readTree(result.outputs().get("contract.json"));
    var baseline = json.readTree(result.outputs().get("baseline.json"));
    assertThat(contract.path("status").asString()).isEqualTo("BLOCKED_ENVIRONMENT");
    assertThat(baseline.path("status").asString()).isEqualTo("BLOCKED_ENVIRONMENT");
    assertThat(baseline.path("detail").asString()).contains("maven-verify-it").contains("Docker");
    assertThat(result.metrics()).containsEntry("blocked_environment", true);
    // No verification check ran and nothing beyond the probe executed.
    verify(sandboxes, never()).exec(eq(handle), contains("mvn"), anyLong());
    verify(sandboxes).teardown(handle);
  }

  @Test
  void prepareRunsBaselineAndReadiesWhenChecksPass() {
    SandboxService sandboxes = mock(SandboxService.class);
    SandboxHandle handle = new SandboxHandle("baseline", "container");
    when(sandboxes.create(any())).thenReturn(handle);
    when(sandboxes.exec(eq(handle), contains("mvn -B -ntp test"), anyLong()))
        .thenReturn(new CommandResult(0, "BUILD SUCCESS\n", "", 1));
    ObjectNode payload = genericPayload(unitPlanJson());

    AgentResult result = new PiWorker("pi-prepare-worker", sandboxes,
        mock(PiCodingRunner.class), "", "gateway").execute(context(payload, Map.of()));

    var contract = json.readTree(result.outputs().get("contract.json"));
    var baseline = json.readTree(result.outputs().get("baseline.json"));
    assertThat(contract.path("status").asString()).isEqualTo("READY");
    assertThat(baseline.path("status").asString()).isEqualTo("PASS");
    assertThat(baseline.path("checks").get(0).path("exitCode").asInt()).isZero();
    assertThat(result.outputs().get("baseline.log")).contains("maven-tests exit=0");
    // A unit plan declares no Docker capability, so no probe ran.
    verify(sandboxes, never()).exec(eq(handle), contains("docker"), anyLong());
  }

  @Test
  void prepareRecordsRunnableRedBaseAsBaseNotGreenNotBlocked() {
    SandboxService sandboxes = mock(SandboxService.class);
    SandboxHandle handle = new SandboxHandle("baseline", "container");
    when(sandboxes.create(any())).thenReturn(handle);
    when(sandboxes.exec(eq(handle), contains("mvn -B -ntp test"), anyLong()))
        .thenReturn(new CommandResult(1, "Tests run: 3, Failures: 1\n", "", 1));
    ObjectNode payload = genericPayload(unitPlanJson());

    AgentResult result = new PiWorker("pi-prepare-worker", sandboxes,
        mock(PiCodingRunner.class), "", "gateway").execute(context(payload, Map.of()));

    var contract = json.readTree(result.outputs().get("contract.json"));
    var baseline = json.readTree(result.outputs().get("baseline.json"));
    assertThat(contract.path("status").asString()).isEqualTo("READY");
    assertThat(baseline.path("status").asString()).isEqualTo("FAIL");
    assertThat(baseline.path("failure").asString()).isEqualTo("BASE_NOT_GREEN");
  }

  @Test
  void verifyExecutesResolvedPlanInsteadOfHardcodedStrongerCommand() {
    SandboxService sandboxes = mock(SandboxService.class);
    SandboxHandle fresh = new SandboxHandle("verify", "container");
    when(sandboxes.create(any())).thenReturn(fresh);
    when(sandboxes.exec(eq(fresh), contains("git apply"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));
    when(sandboxes.exec(eq(fresh), contains("mvn -B -ntp test"), anyLong()))
        .thenReturn(new CommandResult(0, "BUILD SUCCESS\n", "", 1));
    ObjectNode payload = genericPayload(unitPlanJson());

    AgentResult result = verifyWorker(sandboxes, payload, "diff --git a/A b/A\n");

    var verification = json.readTree(result.outputs().get("verification.json"));
    assertThat(verification.path("status").asString()).isEqualTo("PASS");
    assertThat(verification.path("planId").asString()).isEqualTo("java-maven-verify");
    assertThat(verification.path("checks").get(0).path("command").asString())
        .isEqualTo("mvn -B -ntp test");
    // The authoritative unit gate ran; a blanket stronger command never did.
    verify(sandboxes, never()).exec(eq(fresh), contains("mvn -B -ntp verify"), anyLong());
    verify(sandboxes, never()).exec(eq(fresh), contains("clean verify"), anyLong());
  }

  @Test
  void verifyFailsLoudlyWhenResolvedPlanCarriesNoChecks() {
    SandboxService sandboxes = mock(SandboxService.class);
    SandboxHandle fresh = new SandboxHandle("verify", "container");
    when(sandboxes.create(any())).thenReturn(fresh);
    when(sandboxes.exec(eq(fresh), contains("git apply"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));
    ObjectNode payload = genericPayload("{\"id\":\"empty\",\"checks\":[]}");

    AgentResult result = verifyWorker(sandboxes, payload, "diff --git a/A b/A\n");

    var verification = json.readTree(result.outputs().get("verification.json"));
    assertThat(verification.path("status").asString()).isEqualTo("FAIL");
    assertThat(verification.path("reason").asString()).isEqualTo("VERIFICATION_PLAN_MISSING");
    // No silent substitution of any hardcoded verification command.
    verify(sandboxes, never()).exec(eq(fresh), contains("mvn"), anyLong());
  }

  @Test
  void verifyClassifiesTestcontainersFailureAsBlockedEnvironment() {
    SandboxService sandboxes = mock(SandboxService.class);
    SandboxHandle fresh = new SandboxHandle("verify", "container");
    when(sandboxes.create(any())).thenReturn(fresh);
    when(sandboxes.exec(eq(fresh), contains("git apply"), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));
    when(sandboxes.exec(eq(fresh), contains("mvn"), anyLong()))
        .thenReturn(new CommandResult(1, "Tests run: 15, Errors: 15\n",
            "java.lang.NoClassDefFoundError: org/testcontainers/dockerclient/"
                + "RootlessDockerClientProviderStrategy\n", 1));
    ObjectNode payload = genericPayload(unitPlanJson());

    AgentResult result = verifyWorker(sandboxes, payload, "diff --git a/A b/A\n");

    var verification = json.readTree(result.outputs().get("verification.json"));
    assertThat(verification.path("status").asString()).isEqualTo("BLOCKED_ENVIRONMENT");
    assertThat(verification.path("reason").asString()).isEqualTo("REQUIRED_CHECK_CANNOT_RUN");
  }

  @Test
  void verifyPropagatesPreparationBlockWithZeroProviderCalls() {
    ObjectNode payload = genericPayload(unitPlanJson());
    AgentContext context = new AgentContext(UUID.randomUUID(), "verify", Map.of(
        "candidate.patch", new ArtifactContent("candidate.patch", 1, "text/plain", ""),
        "candidate.json", new ArtifactContent("candidate.json", 1, "application/json",
            "{\"status\":\"FAILED\",\"stage\":\"PREPARE\",\"reason\":\"BLOCKED_ENVIRONMENT\","
                + "\"retained\":false,\"base\":\"b\"}")),
        payload, Map.of(), List.of(), 1);
    SandboxService sandboxes = mock(SandboxService.class);

    AgentResult result = new PiWorker("pi-verify-worker", sandboxes,
        mock(PiCodingRunner.class), "", "gateway").execute(context);

    var verification = json.readTree(result.outputs().get("verification.json"));
    assertThat(verification.path("status").asString()).isEqualTo("BLOCKED_ENVIRONMENT");
    assertThat(verification.path("stage").asString()).isEqualTo("PREPARE");
    // Nothing was attempted: no sandbox, no checks.
    verify(sandboxes, never()).create(any());
  }

  @Test
  void codingSkipsProviderAndSandboxWhenContractIsBlocked() {
    SandboxService sandboxes = mock(SandboxService.class);
    PiCodingRunner runner = mock(PiCodingRunner.class);
    ObjectNode payload = genericPayload(itPlanJson());
    AgentContext context = new AgentContext(UUID.randomUUID(), "coding", Map.of(
        "contract.json", new ArtifactContent("contract.json", 1, "application/json",
            "{\"status\":\"BLOCKED_ENVIRONMENT\",\"profile\":{\"imageReference\":\"factory-pi:jdk21\","
                + "\"platform\":\"linux/arm64\",\"modelProvider\":\"factory-zai\","
                + "\"modelId\":\"glm-5.3-flash\",\"networkPolicy\":{\"execution\":\"GATEWAY_ONLY\"}}}")),
        payload, Map.of(), List.of(), 1);

    AgentResult result = new PiWorker("pi-coding-worker", sandboxes, runner, "", "gateway")
        .execute(context);

    var candidate = json.readTree(result.outputs().get("candidate.json"));
    assertThat(candidate.path("status").asString()).isEqualTo("FAILED");
    assertThat(candidate.path("reason").asString()).isEqualTo("BLOCKED_ENVIRONMENT");
    assertThat(result.metrics()).containsEntry("provider_calls", 0);
    assertThat(json.readTree(result.outputs().get("usage.json")).path("provider_calls").asInt())
        .isZero();
    verify(runner, never()).run(any(), any(), anyString(), anyString(), any(), anyLong(), any());
    verify(sandboxes, never()).create(any());
  }

  @Test
  void finalizationReportsBlockedEnvironmentOutcome() {
    AgentContext context = new AgentContext(UUID.randomUUID(), "finalize", Map.of(
        "verification.json", new ArtifactContent("verification.json", 1, "application/json",
            "{\"status\":\"BLOCKED_ENVIRONMENT\",\"stage\":\"PREPARE\","
                + "\"reason\":\"BLOCKED_ENVIRONMENT\"}"),
        "usage.json", new ArtifactContent("usage.json", 1, "application/json",
            "{\"provider_calls\":0}")),
        json.createObjectNode(), Map.of(), List.of());

    AgentResult result = new PiWorker("pi-finalize-worker", mock(SandboxService.class),
        mock(PiCodingRunner.class), "", "gateway").execute(context);

    var node = json.readTree(result.outputs().get("result.json"));
    assertThat(node.path("outcome").asString()).isEqualTo("BLOCKED_ENVIRONMENT");
    assertThat(node.path("failure").path("reason").asString()).isEqualTo("BLOCKED_ENVIRONMENT");
    assertThat(node.path("failure").path("stage").asString()).isEqualTo("PREPARE");
    assertThat(node.path("workflowOperational").asBoolean()).isTrue();
  }

  private AgentResult verifyWorker(SandboxService sandboxes, ObjectNode payload, String patch) {
    when(sandboxes.exec(org.mockito.ArgumentMatchers.any(), contains("git write-tree"), anyLong()))
        .thenReturn(new CommandResult(0, "candidate-tree\n", "", 1));
    when(sandboxes.exec(org.mockito.ArgumentMatchers.any(), contains("--name-only"), anyLong()))
        .thenReturn(new CommandResult(0, "src/main/java/A.java\n", "", 1));
    AgentContext context = new AgentContext(UUID.randomUUID(), "verify", Map.of(
        "candidate.patch", new ArtifactContent("candidate.patch", 1, "text/plain", patch),
        "candidate.json", new ArtifactContent("candidate.json", 1, "application/json",
            "{\"status\":\"READY\",\"base\":\"b\",\"candidateTree\":\"candidate-tree\","
                + "\"patchSha256\":\"" + ArtifactStore.sha256(patch) + "\"}")),
        payload, Map.of(), List.of(), 1);
    return new PiWorker("pi-verify-worker", sandboxes, mock(PiCodingRunner.class), "", "gateway")
        .execute(context);
  }

  private AgentContext context(ObjectNode payload, Map<String, ArtifactContent> inputs) {
    return new AgentContext(UUID.randomUUID(), "prepare", inputs, payload, Map.of(), List.of(), 1);
  }

  /** Generic (non-MODSIDECAR-208) trigger payload with the given plan JSON. */
  private ObjectNode genericPayload(String planJson) {
    ObjectNode payload = json.createObjectNode();
    payload.put("taskId", "T");
    payload.put("repoUrl", "repo");
    payload.put("baseRevision", "b");
    payload.put("branch", "task/T");
    payload.put("goal", "edit");
    ObjectNode resolved = payload.putObject("resolvedIntent");
    ObjectNode profile = resolved.putObject("profile");
    profile.put("imageReference", "factory-pi:jdk21");
    profile.put("platform", "linux/arm64");
    profile.put("modelProvider", "factory-zai");
    profile.put("modelId", "glm-5.3-flash");
    profile.putObject("networkPolicy").put("execution", "GATEWAY_ONLY");
    resolved.set("verificationPlan", json.readTree(planJson));
    return payload;
  }

  private String unitPlanJson() {
    return "{\"id\":\"java-maven-verify\",\"checks\":[{\"id\":\"maven-tests\","
        + "\"argv\":[\"mvn\",\"-B\",\"-ntp\",\"test\"],\"required\":true,"
        + "\"requiresDocker\":false}]}";
  }

  private String itPlanJson() {
    return "{\"id\":\"java-maven-verify-it\",\"checks\":[{\"id\":\"maven-verify-it\","
        + "\"argv\":[\"mvn\",\"-B\",\"-ntp\",\"clean\",\"verify\"],\"required\":true,"
        + "\"requiresDocker\":true}]}";
  }
}
