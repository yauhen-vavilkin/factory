package org.folio.factory.app;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.folio.factory.core.domain.ExecutionStatus.COMPLETED;

/**
 * R10 end-to-end scenario: a task file dropped into the dev-factory inbox is
 * picked up by {@code FileInboxTrigger}, routed to the {@code dev-factory}
 * flow and driven to COMPLETED by the real engine — a scripted ChatModel codes
 * in the local sandbox (create → patch → diff → teardown-once) and the four
 * declared artifacts land in the ArtifactStore with the expected content.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none",
        "factory.engine.poll-interval-ms=250", "factory.inbox.poll-interval-ms=250"})
@Import(DevFactoryScenarioLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class DevFactoryEndToEndScenarioTest {

    private static final String TASK_ID = "TASK-E2E-1";
    private static final String WORKSPACE_DIR = "sbx-" + TASK_ID;

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
    @Autowired ArtifactStore artifactStore;
    @Autowired AuditLog auditLog;
    @Autowired FrontmatterCodec frontmatterCodec;
    @Autowired PipelineExecutionRepository executions;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void taskFileInInboxRunsDevFactoryToCompletion() throws Exception {
        Path sourceRepo = createSourceRepo(sourceRoot.resolve("source-repo"));
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            workspaceRoot.register(watcher, ENTRY_CREATE, ENTRY_DELETE);

            Files.writeString(inbox.resolve(TASK_ID + ".yaml"), taskFile(sourceRepo));

            UUID executionId = awaitSingleDevFactoryExecution();

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                PipelineExecution execution = stateManager.get(executionId);
                assertThat(execution.getStatus())
                        .as("dev-factory execution did not complete; error message: %s",
                                execution.getErrorMessage())
                        .isEqualTo(COMPLETED);
            });

            // The claim move happens after route() returns inside the same inbox
            // poll, so it must be awaited even though the execution already exists.
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    Files.exists(inbox.resolve("processed").resolve(TASK_ID + ".yaml")));

            assertInboxClaim();
            assertArtifacts(executionId, sourceRepo);
            assertExactlyOnce(executionId, watcher);
        }
    }

    private UUID awaitSingleDevFactoryExecution() {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(devFactoryExecutions()).hasSize(1));
        return devFactoryExecutions().get(0).getId();
    }

    private List<PipelineExecution> devFactoryExecutions() {
        return executions.findAllByOrderByCreatedAtDesc().stream()
                .filter(execution -> execution.getFlowId().equals("dev-factory"))
                .toList();
    }

    private void assertInboxClaim() {
        assertThat(Files.notExists(inbox.resolve(TASK_ID + ".yaml")))
                .as("claimed task file must be gone from the top-level inbox").isTrue();
        assertThat(Files.notExists(inbox.resolve("failed").resolve(TASK_ID + ".yaml")))
                .as("task file must not have been claimed to failed/").isTrue();
    }

    private void assertArtifacts(UUID executionId, Path sourceRepo) {
        Artifact patchArtifact = latest(executionId, "patch.diff", "coding");
        assertThat(patchArtifact.getContent())
                .contains("diff --git a/README.md b/README.md")
                .contains("+++ b/README.md")
                .contains("+T16 scenario change.")
                .doesNotContain("(no changes)")
                .doesNotContain("[status]")
                .doesNotContain("[diff]");

        Artifact reportArtifact = latest(executionId, "report.md", "coding");
        JsonNode reportMetadata = frontmatterCodec.parse(reportArtifact.getContent()).metadata();
        assertThat(reportMetadata.path("task_id").asString()).isEqualTo(TASK_ID);
        assertThat(reportMetadata.path("repo_url").asString()).isEqualTo(sourceRepo.toString());
        assertThat(reportMetadata.path("branch").asString()).isEqualTo("task/" + TASK_ID);
        assertThat(reportMetadata.path("outcome").asString()).isEqualTo("COMPLETED");
        assertThat(reportMetadata.path("stop_reason").asString()).isEqualTo("COMPLETED");
        assertThat(reportMetadata.path("task_outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(reportMetadata.path("task_outcome_reason").asString())
                .isEqualTo("CHANGES_DELIVERED");
        assertThat(reportMetadata.path("steps").asInt()).isEqualTo(4);
        assertThat(reportMetadata.path("files_changed").asInt()).isEqualTo(1);
        assertThat(reportMetadata.path("diff_size_bytes").asLong()).isPositive();
        assertThat(reportMetadata.path("format_errors").asInt()).isZero();

        Artifact trajectoryArtifact = latest(executionId, "trajectory.jsonl", "coding");
        List<String> turns = trajectoryArtifact.getContent().lines().toList();
        assertThat(turns).hasSize(5);
        assertThat(turns.get(0)).contains("\"tool\":\"read\"").contains("\"ok\":true");
        assertThat(turns.get(1)).contains("\"tool\":\"apply_patch\"").contains("\"ok\":true");
        assertThat(turns.get(2)).contains("\"tool\":\"git_diff\"").contains("\"ok\":true");
        assertThat(turns.get(3)).contains("\"tool\":\"final\"");
        assertThat(turns.get(4))
                .contains("\"outcome\":\"COMPLETED\"")
                .contains("\"stop_reason\":\"COMPLETED\"")
                .contains("\"task_outcome\":\"SUCCEEDED\"")
                .contains("\"task_outcome_reason\":\"CHANGES_DELIVERED\"")
                .contains("\"steps\":4")
                .contains("\"files_changed\":1")
                .contains("\"format_errors\":0");

        Artifact summaryArtifact = latest(executionId, "delivery-summary.md", "finalize");
        Frontmatter summary = frontmatterCodec.parse(summaryArtifact.getContent());
        JsonNode summaryMetadata = summary.metadata();
        assertThat(summaryMetadata.path("flow_id").asString()).isEqualTo("dev-factory");
        assertThat(summaryMetadata.path("task_id").asString()).isEqualTo(TASK_ID);
        assertThat(summaryMetadata.path("repo_url").asString()).isEqualTo(sourceRepo.toString());
        assertThat(summaryMetadata.path("branch").asString()).isEqualTo("task/" + TASK_ID);
        assertThat(summaryMetadata.path("outcome").asString()).isEqualTo("COMPLETED");
        assertThat(summaryMetadata.path("stop_reason").asString()).isEqualTo("COMPLETED");
        assertThat(summaryMetadata.path("task_outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(summaryMetadata.path("task_outcome_reason").asString())
                .isEqualTo("CHANGES_DELIVERED");
        assertThat(summaryMetadata.path("artifact_count").asInt())
                .as("the summary cannot list itself").isEqualTo(3);
        String summaryBody = summary.body();
        assertThat(summaryBody)
                .contains("- `patch.diff` v1 — sha256:"
                        + ArtifactStore.sha256(patchArtifact.getContent()))
                .contains("- `report.md` v1 — sha256:"
                        + ArtifactStore.sha256(reportArtifact.getContent()))
                .contains("- `trajectory.jsonl` v1 — sha256:"
                        + ArtifactStore.sha256(trajectoryArtifact.getContent()));
        assertThat(summaryBody.lines().filter(line -> line.startsWith("- `")).count())
                .as("inventory must list exactly the three pre-existing artifacts")
                .isEqualTo(3);
        assertThat(summaryBody).doesNotContain("`delivery-summary.md`");

        // The trigger payload is persisted as jsonb, so its text form comes back
        // in PostgreSQL's canonical rendering (reordered keys, "key": value
        // spacing) — assert on the parsed mapping, not on a serialised literal.
        JsonNode triggerPayload = json.readTree(stateManager.get(executionId).getTriggerPayload());
        assertThat(triggerPayload.path("taskId").asString())
                .as("inbox mapping must have reached the trigger payload").isEqualTo(TASK_ID);
        assertThat(auditLog.forExecution(executionId).stream()
                .map(AuditEvent::getEventType).toList())
                .containsSubsequence(AuditEventType.EXECUTION_STARTED,
                        AuditEventType.STEP_COMPLETED, AuditEventType.STEP_COMPLETED,
                        AuditEventType.EXECUTION_COMPLETED);
    }

    private Artifact latest(UUID executionId, String name, String createdBy) {
        Artifact artifact = artifactStore.getLatest(executionId, name).orElseThrow();
        assertThat(artifact.getContentType()).isEqualTo("text/markdown");
        assertThat(artifact.getCreatedBy()).isEqualTo(createdBy);
        assertThat(artifact.getVersion()).isEqualTo(1);
        assertThat(artifact.getSha256()).isEqualTo(ArtifactStore.sha256(artifact.getContent()));
        return artifact;
    }

    private void assertExactlyOnce(UUID executionId, WatchService watcher) throws InterruptedException {
        int creates = 0;
        int deletes = 0;
        WatchKey key;
        while ((key = watcher.poll(2, TimeUnit.SECONDS)) != null) {
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == OVERFLOW) {
                    fail("WatchService OVERFLOW while observing the workspace root: %s", event);
                }
                if (!WORKSPACE_DIR.equals(event.context().toString())) {
                    continue;
                }
                if (event.kind() == ENTRY_CREATE) {
                    creates++;
                }
                if (event.kind() == ENTRY_DELETE) {
                    deletes++;
                }
            }
            key.reset();
        }
        assertThat(creates).as("sandbox workspace creations").isEqualTo(1);
        assertThat(deletes).as("sandbox workspace deletions").isEqualTo(1);
        assertThat(Files.notExists(workspaceRoot.resolve(WORKSPACE_DIR)))
                .as("sandbox workspace must be torn down").isTrue();
        assertThat(devFactoryExecutions())
                .as("no duplicate dev-factory execution appeared during the run").hasSize(1);
    }

    private Path createSourceRepo(Path dir) throws Exception {
        Files.createDirectories(dir);
        runGit(dir, "init", "-b", "main");
        runGit(dir, "config", "user.name", "T16 Scenario");
        runGit(dir, "config", "user.email", "t16-scenario@factory.invalid");
        Files.writeString(dir.resolve("README.md"),
                "# Demo repository\n\nBaseline content for the T16 scenario.\n");
        runGit(dir, "add", "README.md");
        runGit(dir, "commit", "-m", "baseline");
        return dir.toAbsolutePath();
    }

    private static void runGit(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command).directory(dir.toFile()).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor())
                .as("git %s in %s failed%nstdout=%s%nstderr=%s",
                        String.join(" ", args), dir, out, err)
                .isZero();
    }

    private static String taskFile(Path sourceRepo) {
        return """
                id: TASK-E2E-1
                repo: %s
                base: main
                branch: task/TASK-E2E-1
                goal: |
                  Append the line "T16 scenario change." to README.md and finish with a short report.
                acceptance:
                  - patch.diff modifies README.md
                constraints:
                  allow_paths:
                    - README.md
                notes: Scenario task file for the T16 end-to-end test.
                """.formatted(sourceRepo.toAbsolutePath());
    }
}
