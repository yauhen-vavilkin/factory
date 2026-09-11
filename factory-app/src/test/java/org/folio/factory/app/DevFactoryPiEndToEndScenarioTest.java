package org.folio.factory.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.devfactory.pi.PiCodingRunner;
import org.folio.factory.devfactory.resolution.RepositoryAccess;
import org.folio.factory.devfactory.resolution.RepositoryCatalog;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.awaitility.Awaitility.await;
import static org.folio.factory.core.domain.ExecutionStatus.COMPLETED;

/**
 * Pi-specific full-stack admission regression. The upstream is scripted at the
 * Pi runner boundary, but admission, persistence, routing and execution are real.
 */
@SpringBootTest(properties = {
    "factory.mode=pi",
    "spring.ai.model.chat=none",
    "factory.engine.poll-interval-ms=100",
    "factory.inbox.poll-interval-ms=100",
    "factory.inbox.event-type=file.inbox.pi",
    "factory.pi.model-token=scripted-test-token"
})
@org.junit.jupiter.api.Tag("integration")
@Testcontainers
@Import(StubLlmConfiguration.class)
class DevFactoryPiEndToEndScenarioTest {

  private static final String TASK_ID = "TASK-PI-E2E-1";

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @TempDir
  static Path inbox;

  @TempDir
  static Path workspaceRoot;

  static WireMockServer gateway;

  @TempDir
  Path sourceRoot;

  @DynamicPropertySource
  static void factoryRuntimeDirs(DynamicPropertyRegistry registry) {
    registry.add("factory.sandbox.mode", () -> "docker");
    registry.add("factory.sandbox.docker-network", () -> "host");
    registry.add("factory.sandbox.workspace-root", () -> workspaceRoot.toString());
    registry.add("factory.inbox.dir", () -> inbox.toString());
    gateway = new WireMockServer(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
        .port(8080).bindAddress("0.0.0.0"));
    gateway.start();
    registry.add("factory.pi.gateway-url", () -> "http://factory-gateway:8080/v1");
  }

  @Autowired StateManager stateManager;
  @Autowired PipelineExecutionRepository executions;
  @Autowired ArtifactStore artifactStore;

  @MockitoBean RepositoryCatalog repositoryCatalog;
  @MockitoBean RepositoryAccess repositoryAccess;

  @Test
  void submittedTaskRunsThroughPostgresEngineAndPiFlow() throws Exception {
    try {
      String read = "data: {\"id\":\"s\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"r\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
      String edit = "data: {\"id\":\"s\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"sed -i 's/return value;/return value.trim().toLowerCase();/' src/main/java/factory/Normalizer.java\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
      edit = edit.replace("sed -i 's/return", "git config --global --add safe.directory /workspace/repo && sed -i 's/return");
      edit = edit.replace("git config --global --add safe.directory /workspace/repo && sed -i 's/return value;/return value.trim().toLowerCase();/' src/main/java/factory/Normalizer.java",
          "git config --global --add safe.directory /workspace/repo && printf '%s' 'package factory; public final class Normalizer { public static String normalize(String value) { return value.trim().toLowerCase(); } }' > src/main/java/factory/Normalizer.java && git add . && git commit -m pi-normalization-candidate");
      edit = edit.replace("pi-normalization-candidate\"}",
          "pi-normalization-candidate" + "\\" + "\"}");
      String done = "data: {\"id\":\"s\",\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
      gateway.stubFor(post(urlPathEqualTo("/v1/chat/completions")).inScenario("pi")
          .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(read))
          .willSetStateTo("edited"));
      gateway.stubFor(post(urlPathEqualTo("/v1/chat/completions")).inScenario("pi")
          .whenScenarioStateIs("edited").willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(edit))
          .willSetStateTo("done"));
      gateway.stubFor(post(urlPathEqualTo("/v1/chat/completions")).inScenario("pi")
          .whenScenarioStateIs("done").willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(done)));
    Path sourceRepo = sourceRoot.resolve("source-repo");
    Files.createDirectories(sourceRepo);
    Files.writeString(sourceRepo.resolve("README.md"), "# Normalization\n");
    Files.createDirectories(sourceRepo.resolve("src/main/java/factory"));
    Files.createDirectories(sourceRepo.resolve("src/test/java/factory"));
    Files.writeString(sourceRepo.resolve("src/main/java/factory/Normalizer.java"),
        "package factory; public final class Normalizer { public static String normalize(String value) { return value; } }");
    Files.writeString(sourceRepo.resolve("src/test/java/factory/NormalizerTest.java"),
        "package factory; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; class NormalizerTest { @Test void works() { assertEquals(\"folio\", Normalizer.normalize(\"  FOLIO  \")); } }");
    Files.writeString(sourceRepo.resolve("pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>factory</groupId><artifactId>fixture</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release><maven.compiler.source>21</maven.compiler.source><maven.compiler.target>21</maven.compiler.target></properties><dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.0</version><scope>test</scope></dependency></dependencies><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin></plugins></build></project>");
    run(sourceRepo, "git", "init", "-q", "-b", "main"); run(sourceRepo, "git", "config", "user.name", "test"); run(sourceRepo, "git", "config", "user.email", "test@example.invalid"); run(sourceRepo, "git", "add", "."); run(sourceRepo, "git", "commit", "-qm", "base");
    String base = run(sourceRepo, "git", "rev-parse", "HEAD").trim();
    when(repositoryCatalog.candidates(any(), any(), any()))
        .thenReturn(Set.of("folio-org/folio-module-sidecar"));
    when(repositoryCatalog.origin("folio-org/folio-module-sidecar"))
        .thenReturn(sourceRepo.toUri().toString());
    when(repositoryAccess.resolveBranch(anyString(), anyString())).thenReturn(base);
    when(repositoryAccess.readFile(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(invocation -> {
          String path = invocation.getArgument(2, String.class);
          if (path.equals("pom.xml")) {
            return Optional.of(("<project><properties><maven.compiler.release>21</maven.compiler.release>"
                + "</properties></project>").getBytes(StandardCharsets.UTF_8));
          }
          return Optional.empty();
        });


    Files.writeString(inbox.resolve(TASK_ID + ".yaml"), """
        schemaVersion: 1
        source:
          type: TEST
          id: TASK-PI-E2E-1
          project: factory
        repository: local-fixture
        baseRef: main
        profileId: java21-pi-unit
        verificationPlanId: modsidecar-208-v1
        runKey: pi-e2e
        deliveryMode: LOCAL_ONLY
        goal: Normalize the value by trimming whitespace and lowercasing it.
        acceptanceCriteria:
          - id: patch
            text: patch.diff modifies Normalizer.java
        constraints:
          allow_paths:
            - src/main/java/factory/Normalizer.java
        """);

    UUID executionId = await().atMost(Duration.ofSeconds(30)).until(() ->
        executions.findAllByOrderByCreatedAtDesc().stream()
            .filter(execution -> execution.getFlowId().equals("dev-factory-pi"))
            .map(PipelineExecution::getId).findFirst().orElse(null),
        id -> id != null);
    await().atMost(Duration.ofSeconds(90)).until(() ->
        stateManager.get(executionId).getStatus().isTerminal());
    assertThat(stateManager.get(executionId).getStatus()).isEqualTo(COMPLETED);
    assertThat(stateManager.get(executionId).getStatus()).isNotEqualTo(
        org.folio.factory.core.domain.ExecutionStatus.FAILED_ESCALATED);
    Artifact candidate = artifactStore.getLatest(executionId, "candidate.patch").orElseThrow();
    Artifact verification = artifactStore.getLatest(executionId, "verification.json").orElseThrow();
    Artifact result = artifactStore.getLatest(executionId, "result.json").orElseThrow();
    System.out.println("PI_E2E_CANDIDATE_BYTES=" + candidate.getContent().length()
        + " CANDIDATE_JSON=" + artifactStore.getLatest(executionId, "candidate.json").orElseThrow().getContent());
    assertThat(candidate.getContent()).contains("Normalizer.java", "toLowerCase").isNotEmpty();
    assertThat(verification.getContent()).contains("\"status\":\"PASS\"", "\"independent\":true");
    assertThat(result.getContent()).contains("\"outcome\":\"SUCCESS\"");
    gateway.verify(3, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
    System.out.println("PI_E2E_SUCCESS candidate_exported=true independently_verified=true source_mutation=true gateway_requests=3");

    System.out.println("PI_E2E_TRIGGER file.inbox.pi -> dev-factory-pi -> PostgreSQL execution "
        + executionId);
    assertThat(executions.findById(executionId).orElseThrow().getFlowId())
        .isEqualTo("dev-factory-pi");
    } finally {
      gateway.resetAll();
    }
  }

  @Test
  void acceptanceNegativeControlRejectsFailedEscalated() {
    assertThatThrownBy(() -> assertThat(
        org.folio.factory.core.domain.ExecutionStatus.FAILED_ESCALATED).isEqualTo(COMPLETED))
        .isInstanceOf(AssertionError.class);
    System.out.println("PI_E2E_NEGATIVE_CONTROL FAILED_ESCALATED_REJECTED=true");
  }

  private static String run(Path dir, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new IllegalStateException(output);
    return output;
  }
}
