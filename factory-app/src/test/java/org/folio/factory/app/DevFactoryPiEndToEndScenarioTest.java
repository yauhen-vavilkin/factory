package org.folio.factory.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.nio.charset.StandardCharsets;

import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.StateManager;
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
    "factory.mode=offline",
    "spring.ai.model.chat=none",
    "factory.engine.poll-interval-ms=100",
    "factory.inbox.poll-interval-ms=100",
    "factory.inbox.event-type=file.inbox.pi"
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

  @TempDir
  Path sourceRoot;

  @DynamicPropertySource
  static void factoryRuntimeDirs(DynamicPropertyRegistry registry) {
    registry.add("factory.sandbox.mode", () -> "local");
    registry.add("factory.sandbox.workspace-root", () -> workspaceRoot.toString());
    registry.add("factory.inbox.dir", () -> inbox.toString());
  }

  @Autowired StateManager stateManager;
  @Autowired PipelineExecutionRepository executions;

  @MockitoBean SandboxService sandboxService;
  @MockitoBean PiCodingRunner piCodingRunner;
  @MockitoBean RepositoryCatalog repositoryCatalog;
  @MockitoBean RepositoryAccess repositoryAccess;

  @Test
  void submittedTaskRunsThroughPostgresEngineAndPiFlow() throws Exception {
    Path sourceRepo = sourceRoot.resolve("source-repo");
    Files.createDirectories(sourceRepo);
    String base = "0123456789012345678901234567890123456789";
    when(repositoryCatalog.candidates(any(), any(), any()))
        .thenReturn(Set.of("folio-org/folio-module-sidecar"));
    when(repositoryCatalog.origin("folio-org/folio-module-sidecar"))
        .thenReturn(sourceRepo.toString());
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

    SandboxHandle coding = new SandboxHandle("pi-coding", "pi-coding-container");
    SandboxHandle verify = new SandboxHandle("pi-verify", "pi-verify-container");
    when(sandboxService.create(any())).thenReturn(coding, verify);
    when(piCodingRunner.run(any(), any(), anyString(), anyString(), any(), anyLong(), any()))
        .thenReturn(new PiCodingRunner.CodingAttempt(0, true,
            "{\"type\":\"agent_settled\"}\n", List.of(), 64, 0));
    String patch = "diff --git a/README.md b/README.md\n--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-baseline\n+Pi candidate\n";
    when(sandboxService.exec(any(), anyString(), anyLong())).thenAnswer(invocation -> {
      String command = invocation.getArgument(1, String.class);
      if (command.contains("git diff --binary")) return new CommandResult(0, patch, "", 1);
      if (command.contains("git rev-parse HEAD")) return new CommandResult(0, base, "", 1);
      return new CommandResult(0, "", "", 1);
    });

    Files.writeString(inbox.resolve(TASK_ID + ".yaml"), """
        schemaVersion: 1
        source:
          type: TEST
          id: TASK-PI-E2E-1
          project: factory
        repository: local-fixture
        baseRef: main
        profileId: java-maven-21
        verificationPlanId: scenario-readme-verify
        runKey: pi-e2e
        deliveryMode: LOCAL_ONLY
        goal: Append the Pi candidate line to README.md.
        acceptanceCriteria:
          - id: patch
            text: patch.diff modifies README.md
        constraints:
          allow_paths:
            - README.md
        """);

    UUID executionId = await().atMost(Duration.ofSeconds(30)).until(() ->
        executions.findAllByOrderByCreatedAtDesc().stream()
            .filter(execution -> execution.getFlowId().equals("dev-factory-pi"))
            .map(PipelineExecution::getId).findFirst().orElse(null),
        id -> id != null);
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        assertThat(stateManager.get(executionId).getStatus()).isEqualTo(COMPLETED));

    System.out.println("PI_E2E_TRIGGER file.inbox.pi -> dev-factory-pi -> PostgreSQL execution "
        + executionId);
    assertThat(executions.findById(executionId).orElseThrow().getFlowId())
        .isEqualTo("dev-factory-pi");
  }
}
