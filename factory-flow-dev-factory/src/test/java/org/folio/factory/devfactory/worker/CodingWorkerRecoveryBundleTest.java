package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.worker.recovery.RecoveryBundleStore;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.core.LocalSandboxService;
import org.folio.factory.sandbox.core.SandboxProperties;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.folio.factory.sandbox.harness.HarnessReport;
import org.folio.factory.sandbox.harness.TaskContract;
import org.folio.factory.sandbox.harness.TaskOutcome;
import org.folio.factory.sandbox.harness.Trajectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T25 R1–R3: on the default no-workDir route every terminal path of the
 * coding step publishes a digest-manifested, attempt-keyed recovery bundle
 * under the recovery root BEFORE any destructive cleanup. The bundle is the
 * only cross-attempt preserved copy: the temp workDir is deleted and the
 * sandbox workspace is attempt-scoped (T25 S09 — every attempt keys
 * sbx-<exec>-attempt-<n>), so one attempt's create never sweeps another
 * attempt's workspace.
 */
class CodingWorkerRecoveryBundleTest {

  private static final long EXEC_TIMEOUT_SEC = 60L;
  private static final List<String> DECLARED_OUTPUTS =
      List.of("patch.diff", "report.md", "trajectory.jsonl");
  private static final String WORKSPACE_SNAPSHOT_ARTIFACT = "workspace-snapshot.tar.gz.b64";

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @TempDir
  Path root;

  @Test
  void defaultPathSuccessPublishesCompleteBundleAndLeavesNoWorkspaceOrTempDir()
      throws Exception {
    Scenario scenario = scenario("T25-S", (s, handle) ->
        exec(s.sandbox(), handle, "cd repo && printf 'public class NewUtil {}\\n' > NewUtil.java"));

    AgentResult result = scenario.worker().execute(scenario.context());

    assertThat(scenario.workspaceDir())
        .as("successful default-path run tears the sandbox workspace down")
        .doesNotExist();
    assertThat(scenario.capturedWorkDir())
        .as("successful default-path run deletes the temp workDir")
        .doesNotExist();

    Object locator = result.metrics().get("recovery_locator");
    assertThat(locator).isInstanceOf(String.class);
    assertThat(Path.of((String) locator)).exists();

    RecoveryBundleStore.RecoveryBundle bundle = scenario.bundle(1).orElseThrow();
    assertThat(bundle.directory()).isEqualTo(Path.of((String) locator));
    assertThat(bundle.manifest().path("schema").asString()).isEqualTo("devfactory/recovery-v1");
    assertThat(bundle.manifest().path("execution_id").asString())
        .isEqualTo(scenario.executionId().toString());
    assertThat(bundle.manifest().path("step_id").asString()).isEqualTo("coding");
    assertThat(bundle.manifest().path("attempt").asInt()).isEqualTo(1);
    assertThat(bundle.manifest().path("task_id").asString()).isEqualTo("T25-S");
    assertThat(bundle.manifest().path("base_revision").asString()).matches("[0-9a-f]{40,64}");
    assertThat(Instant.parse(bundle.manifest().path("created_at").asString()))
        .as("created_at must be ISO-8601").isNotNull();
    assertThat(bundle.completeness()).isEqualTo("COMPLETE");
    assertThat(bundle.failureReason()).isNull();
    assertThat(bundle.artifactNames())
        .containsExactlyInAnyOrderElementsOf(DECLARED_OUTPUTS);

    for (String name : DECLARED_OUTPUTS) {
      Path file = bundle.directory().resolve(name);
      String bytes = Files.readString(file, StandardCharsets.UTF_8);
      assertThat(bytes).isEqualTo(result.outputs().get(name));
      assertThat(bundle.artifactSha256(name))
          .as("manifest digest for %s must match the bundled file bytes", name)
          .isEqualTo(ArtifactStore.sha256(bytes));
      assertThat(bundle.artifactSizeBytes(name))
          .isEqualTo(Files.size(file));
    }
  }

  @Test
  void exportFailurePublishesIncompleteExportFailedBundleAndStillTearsDownWorkspace()
      throws Exception {
    Scenario scenario = scenario("T25-EF", (s, handle) -> {
      // S04 blind-spot fix: a unique coding write exists before .git is
      // destroyed — the run has unique bytes no diff can recover.
      exec(s.sandbox(), handle,
          "cd repo && printf 'ef-unique-coding-write\\n' > unique.txt");
      exec(s.sandbox(), handle, "cd repo && rm -rf .git");
    });

    assertThatThrownBy(() -> scenario.worker().execute(scenario.context()))
        .isInstanceOf(AgentExecutionException.class)
        .hasMessageContaining("git diff failed");
    assertThat(scenario.workspaceDir())
        .as("teardown proceeds after the bundle became the preserved copy")
        .doesNotExist();

    RecoveryBundleStore.RecoveryBundle bundle = scenario.bundle(1).orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("EXPORT_FAILED");
    assertThat(bundle.artifactNames())
        .as("no patch.diff can exist when the export itself failed")
        .doesNotContain("patch.diff");
    assertThat(bundle.artifactNames())
        .as("the harness-written trajectory is preserved")
        .contains(Trajectory.FILE_NAME);
    assertThat(bundle.artifactNames())
        .as("the unique worktree bytes ride the bundle as one snapshot artifact")
        .contains(WORKSPACE_SNAPSHOT_ARTIFACT);
    assertThat(decodeSnapshotMember(bundle.directory(), "unique.txt"))
        .as("the bundled snapshot must decode back to the unique coding bytes")
        .isEqualTo("ef-unique-coding-write\n");
    assertBundledDigestsMatch(bundle);
  }

  /**
   * S04 fail-closed variant of the new capture path: when the snapshot
   * capture itself fails on a terminal path (here: the worktree checkout is
   * gone, so the capture exec cannot run), no bundle is published and BOTH
   * producer locations — the sandbox workspace and the temp workDir — are
   * retained for recovery.
   */
  @Test
  void exportFailureWhoseSnapshotCaptureFailsRetainsWorkspaceAndTempState() throws Exception {
    Scenario scenario = scenario("T25-CF", (s, handle) -> {
      exec(s.sandbox(), handle,
          "cd repo && printf 'cf-unique-coding-write\\n' > unique.txt");
      exec(s.sandbox(), handle, "rm -rf repo");
    });

    assertThatThrownBy(() -> scenario.worker().execute(scenario.context()))
        .isInstanceOf(AgentExecutionException.class)
        .hasMessageContaining("snapshot");
    assertThat(scenario.workspaceDir())
        .as("a failed capture means no preservation — the workspace must be retained")
        .exists();
    assertThat(scenario.capturedWorkDir())
        .as("the temp workDir must also be retained when the capture failed")
        .exists();
    assertThat(scenario.bundle(1))
        .as("nothing may be published as 'preserved' when the capture failed")
        .isEmpty();
  }

  @Test
  void partialPersistPublishesIncompleteSubsetBundleAndRetainsWorkspace() throws Exception {
    Scenario scenario = scenario("T25-PP", (s, handle) -> {
      exec(s.sandbox(), handle, "cd repo && printf 'public class NewUtil {}\\n' > NewUtil.java");
      // T19-H's obstruction shape, aimed at one declared output name inside
      // the run's real workDir: a non-writable path where report.md should go.
      try {
        Files.createDirectory(s.capturedWorkDir().resolve("report.md"));
      } catch (IOException e) {
        throw new IllegalStateException("could not create the persist obstruction", e);
      }
    });

    assertThatThrownBy(() -> scenario.worker().execute(scenario.context()))
        .isInstanceOf(AgentExecutionException.class)
        .hasMessageContaining("persist");
    assertThat(scenario.workspaceDir())
        .as("sandbox holding the model's unique change must be retained")
        .exists();

    RecoveryBundleStore.RecoveryBundle bundle = scenario.bundle(1).orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("PERSIST_FAILED");
    assertThat(bundle.artifactNames())
        .as("the obstructed output name must not appear as a bundled artifact")
        .doesNotContain("report.md");
    assertThat(bundle.artifactNames())
        .contains(Trajectory.FILE_NAME);
    assertThat(bundle.artifactNames())
        .as("bundle may only carry declared outputs that exist as regular files")
        .isSubsetOf(DECLARED_OUTPUTS);
    assertBundledDigestsMatch(bundle);
  }

  @Test
  void attemptTwoPublishesDistinctDirectoryLeavingAttemptOneUnchanged() throws Exception {
    Scenario scenario = scenario("T25-A2", (s, handle) ->
        exec(s.sandbox(), handle, "cd repo && printf 'public class A1 {}\\n' > A1.java"));
    scenario.worker().execute(scenario.context());
    RecoveryBundleStore.RecoveryBundle first = scenario.bundle(1).orElseThrow();
    byte[] firstManifest = Files.readAllBytes(first.directory()
        .resolve(RecoveryBundleStore.MANIFEST_FILE));
    byte[] firstPatch = Files.readAllBytes(first.directory().resolve("patch.diff"));

    scenario.restub((s, handle) ->
        exec(s.sandbox(), handle, "cd repo && printf 'public class A2 {}\\n' > A2.java"));
    AgentResult second = scenario.worker().execute(scenario.context(2));

    RecoveryBundleStore.RecoveryBundle secondBundle = scenario.bundle(2).orElseThrow();
    assertThat(secondBundle.directory()).isNotEqualTo(first.directory());
    assertThat(secondBundle.directory()).exists();
    assertThat(secondBundle.manifest().path("attempt").asInt()).isEqualTo(2);
    assertThat(second.metrics().get("recovery_locator"))
        .isEqualTo(secondBundle.directory().toString());
    assertThat(Files.readAllBytes(first.directory()
        .resolve(RecoveryBundleStore.MANIFEST_FILE)))
        .as("a later attempt must never rewrite a prior attempt's manifest")
        .isEqualTo(firstManifest);
    assertThat(Files.readAllBytes(first.directory().resolve("patch.diff")))
        .isEqualTo(firstPatch);
  }

  @Test
  void workerThatCannotPublishRetainsWorkspaceAndTempState() throws Exception {
    Scenario scenario = scenario("T25-NP", (s, handle) ->
        exec(s.sandbox(), handle, "cd repo && printf 'public class NewUtil {}\\n' > NewUtil.java"));
    Path lockedRoot = Files.createDirectories(scenario.recoveryRoot());
    Files.setPosixFilePermissions(lockedRoot, PosixFilePermissions.fromString("r-xr-x---"));
    try {
      assertThatThrownBy(() -> scenario.worker().execute(scenario.context()))
          .isInstanceOf(AgentExecutionException.class)
          .hasMessageContaining("recovery");
      assertThat(scenario.workspaceDir())
          .as("without a published bundle no destructive cleanup may run")
          .exists();
      assertThat(scenario.capturedWorkDir())
          .as("temp workDir must also be retained when no bundle was published")
          .exists();
      assertThat(scenario.bundle(1)).isEmpty();
    } finally {
      Files.setPosixFilePermissions(lockedRoot, PosixFilePermissions.fromString("rwxr-x---"));
    }
  }

  @Test
  void storeRefusesToOverwriteNonEmptyAttemptDirectory() throws Exception {
    RecoveryBundleStore store = new RecoveryBundleStore(root.resolve("store-guard"));
    UUID executionId = UUID.randomUUID();
    Path firstDir = store.publish(executionId, "coding", 1, "T25-G", null,
        Map.of("patch.diff", "first\n"), true, null);
    byte[] firstManifest = Files.readAllBytes(firstDir.resolve(RecoveryBundleStore.MANIFEST_FILE));

    assertThatThrownBy(() -> store.publish(executionId, "coding", 1, "T25-G", null,
        Map.of("patch.diff", "second\n"), true, null))
        .isInstanceOf(IllegalStateException.class);

    assertThat(Files.readAllBytes(firstDir.resolve(RecoveryBundleStore.MANIFEST_FILE)))
        .isEqualTo(firstManifest);
    assertThat(firstDir.resolve("patch.diff")).hasContent("first\n");
  }

  @Test
  void discardRemovesOnlyItsOwnAttempt() throws Exception {
    RecoveryBundleStore store = new RecoveryBundleStore(root.resolve("store-discard"));
    UUID executionId = UUID.randomUUID();
    Path first = store.publish(executionId, "coding", 1, "T25-D", null,
        Map.of("patch.diff", "one\n"), true, null);
    Path second = store.publish(executionId, "coding", 2, "T25-D", null,
        Map.of("patch.diff", "two\n"), true, null);

    store.discard(executionId, "coding", 1);

    assertThat(first).doesNotExist();
    assertThat(second).exists();
    assertThat(store.find(executionId, "coding", 1)).isEmpty();
    assertThat(store.find(executionId, "coding", 2)).isPresent();
  }

  /**
   * T25 S02: the core seam {@code discardAcknowledged} is attempt-scoped —
   * acknowledging (discarding) attempt 2 of a retry pair leaves attempt 1's
   * bundle bytes and manifest byte-identical, and absent bundles are a no-op.
   */
  @Test
  void discardAcknowledgedIsAttemptScopedAndLeavesPriorAttemptBytesIdentical() throws Exception {
    RecoveryBundleStore store = new RecoveryBundleStore(root.resolve("store-ack"));
    UUID executionId = UUID.randomUUID();
    Path first = store.publish(executionId, "coding", 1, "T25-ACK", null,
        Map.of("patch.diff", "attempt-one\n", "report.md", "one\n"), true, null);
    Path second = store.publish(executionId, "coding", 2, "T25-ACK", null,
        Map.of("patch.diff", "attempt-two\n"), true, null);
    byte[] firstManifest = Files.readAllBytes(first.resolve(RecoveryBundleStore.MANIFEST_FILE));
    byte[] firstPatch = Files.readAllBytes(first.resolve("patch.diff"));
    byte[] firstReport = Files.readAllBytes(first.resolve("report.md"));

    store.discardAcknowledged(executionId, "coding", 2);

    assertThat(second)
        .as("the acknowledged attempt's bundle is gone")
        .doesNotExist();
    assertThat(store.find(executionId, "coding", 2)).isEmpty();
    assertThat(store.find(executionId, "coding", 1)).isPresent();
    assertThat(Files.readAllBytes(first.resolve(RecoveryBundleStore.MANIFEST_FILE)))
        .as("prior attempt's manifest must stay byte-identical")
        .isEqualTo(firstManifest);
    assertThat(Files.readAllBytes(first.resolve("patch.diff"))).isEqualTo(firstPatch);
    assertThat(Files.readAllBytes(first.resolve("report.md"))).isEqualTo(firstReport);

    store.discardAcknowledged(executionId, "coding", 2);
    store.discardAcknowledged(executionId, "coding", 3);
    assertThat(first)
        .as("discarding absent bundles must be a no-op")
        .exists();
  }

  private static void assertBundledDigestsMatch(RecoveryBundleStore.RecoveryBundle bundle)
      throws Exception {
    for (String name : bundle.artifactNames()) {
      assertThat(bundle.artifactSha256(name))
          .isEqualTo(ArtifactStore.sha256(
              Files.readString(bundle.directory().resolve(name), StandardCharsets.UTF_8)));
    }
  }

  /**
   * S04 byte-level recovery check: base64-decode the bundled workspace
   * snapshot, untar it and read one member back — the snapshot must round-trip
   * the sandbox worktree's unique bytes.
   */
  private static String decodeSnapshotMember(Path bundleDir, String memberName) throws Exception {
    byte[] tarGz = Base64.getMimeDecoder().decode(
        Files.readString(bundleDir.resolve(WORKSPACE_SNAPSHOT_ARTIFACT), StandardCharsets.UTF_8));
    Path tarFile = Files.createTempFile("t25-snapshot", ".tar.gz");
    Files.write(tarFile, tarGz);
    Path extractDir = Files.createTempDirectory("t25-snapshot-extract");
    Process extract = new ProcessBuilder("tar", "xzf", tarFile.toString(),
        "-C", extractDir.toString()).redirectErrorStream(true).start();
    extract.getInputStream().readAllBytes();
    assertThat(extract.waitFor())
        .as("bundled workspace snapshot must extract as a tar.gz")
        .isZero();
    return Files.readString(extractDir.resolve(memberName), StandardCharsets.UTF_8);
  }

  private Scenario scenario(String taskId, BiConsumer<Scenario, SandboxHandle> modelTurns) {
    return new Scenario(taskId, modelTurns);
  }

  private static void exec(SandboxService sandbox, SandboxHandle handle, String command) {
    var result = sandbox.exec(handle, command, EXEC_TIMEOUT_SEC);
    assertThat(result.ok())
        .as("scenario arrange command failed: %s%nstdout=%s%nstderr=%s",
            command, result.stdout(), result.stderr())
        .isTrue();
  }

  private Path createSourceRepo(String name) throws Exception {
    Path dir = root.resolve(name);
    Files.createDirectories(dir);
    git(dir, "init", "-b", "main");
    git(dir, "config", "user.name", "T25 Scenario");
    git(dir, "config", "user.email", "t25-scenario@factory.invalid");
    Files.writeString(dir.resolve("README.md"), "# Demo\n\nBaseline.\n");
    Files.writeString(dir.resolve("App.java"), "class App {}\n");
    git(dir, "add", "README.md", "App.java");
    git(dir, "commit", "-qm", "baseline");
    return dir.toAbsolutePath();
  }

  private static void git(Path dir, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process p = new ProcessBuilder(command).directory(dir.toFile()).start();
    p.getInputStream().readAllBytes();
    String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor())
        .as("git %s in %s failed%nstderr: %s", String.join(" ", args), dir, stderr)
        .isZero();
  }

  /**
   * Real local sandbox + scripted harness mock (the T19 scenario pattern),
   * with the trigger config carrying NO workDir so the worker runs the
   * shipped default temp-workDir route, and the recovery root under the
   * test's temp dir.
   */
  private final class Scenario {

    private final String taskId;
    private final UUID executionId = UUID.randomUUID();
    private final SandboxService sandbox;
    private final Path sourceRepo;
    private final CodingHarness harness = org.mockito.Mockito.mock(CodingHarness.class);
    private final Path recoveryRoot = root.resolve("recovery");
    private final RecoveryBundleStore store = new RecoveryBundleStore(recoveryRoot);
    private Path capturedWorkDir;

    Scenario(String taskId, BiConsumer<Scenario, SandboxHandle> modelTurns) {
      try {
        this.taskId = taskId;
        this.sourceRepo = createSourceRepo("src-" + taskId);
        this.sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
            root.resolve("sbx-root"), Duration.ZERO, null), Clock.systemUTC());
        stubHarness(modelTurns);
      } catch (Exception e) {
        throw new IllegalStateException("scenario setup failed for " + taskId, e);
      }
    }

    private void stubHarness(BiConsumer<Scenario, SandboxHandle> turns) {
      when(harness.run(any(), any(TaskContract.class), any())).thenAnswer(invocation -> {
        SandboxHandle handle = invocation.getArgument(0);
        Path workDir = invocation.getArgument(2);
        capturedWorkDir = workDir;
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve(Trajectory.FILE_NAME),
            "{\"ts\":\"2026-09-08T10:00:00Z\",\"step\":1,\"tool\":\"final\",\"ok\":true}\n");
        turns.accept(this, handle);
        return new HarnessReport(3, HarnessReport.Outcome.COMPLETED,
            HarnessReport.StopReason.COMPLETED, 2, 128L, 0, 0L, 0L,
            TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED, "");
      });
    }

    void restub(BiConsumer<Scenario, SandboxHandle> turns) {
      stubHarness(turns);
    }

    CodingWorker worker() {
      return new CodingWorker(sandbox, harness, codec, store);
    }

    SandboxService sandbox() {
      return sandbox;
    }

    Path recoveryRoot() {
      return recoveryRoot;
    }

    Path workspaceDir() {
      return workspaceDir(1);
    }

    Path workspaceDir(int attempt) {
      // T25 S09: attempt-scoped workspace ownership, UNIFORM per attempt —
      // every attempt (including the first) keys sbx-<exec>-attempt-<n>.
      return root.resolve("sbx-root").resolve(
          "sbx-" + executionId + "-attempt-" + attempt);
    }

    Path capturedWorkDir() {
      return capturedWorkDir;
    }

    UUID executionId() {
      return executionId;
    }

    Optional<RecoveryBundleStore.RecoveryBundle> bundle(int attempt) {
      return store.find(executionId, "coding", attempt);
    }

    AgentContext context() {
      // 6-arg compatibility constructor: attempt defaults to 1.
      return new AgentContext(executionId, "coding", Map.of(), triggerPayload(),
          Map.of(), DECLARED_OUTPUTS);
    }

    AgentContext context(int attempt) {
      return new AgentContext(executionId, "coding", Map.of(), triggerPayload(),
          Map.of(), DECLARED_OUTPUTS, attempt);
    }

    private JsonNode triggerPayload() {
      return JsonMapper.builder().build().readTree("""
          {"taskId":"%s",
           "repoUrl":"%s",
           "baseBranch":"main",
           "branch":"dev/%s",
           "goal":"T25 scenario change"}
          """.formatted(taskId, sourceRepo.toString().replace("\\", "\\\\"), taskId));
    }
  }
}
