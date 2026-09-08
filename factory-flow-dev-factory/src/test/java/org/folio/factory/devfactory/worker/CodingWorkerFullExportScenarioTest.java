package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FTQ-02 full-change-export scenarios against a real local sandbox and a real
 * git repository. The harness is scripted (its decision tree is T18's concern);
 * each scenario mutates the sandbox repo exactly like a model turn would and
 * then asserts the externally observable export: the patch content, the
 * recorded base revision in report.md, the artifacts durably written to the
 * workDir, and — the consumer check — a fresh checkout of the recorded base
 * reproducing the whole change via {@code git apply}.
 */
class CodingWorkerFullExportScenarioTest {

  private static final long EXEC_TIMEOUT_SEC = 60L;
  private static final String WORKSPACE_SNAPSHOT_ARTIFACT = "workspace-snapshot.tar.gz.b64";

  private final FrontmatterCodec codec = new FrontmatterCodec();

  @TempDir
  Path root;

  @Test
  @org.junit.jupiter.api.Tag("eval-pack")
  void untrackedNewFileOnlyIsExportedAndReproducedInConsumerCheckout() throws Exception {
    Scenario scenario = scenario("T19-A", (s, handle) -> exec(s.sandbox(), handle,
        "cd repo && printf 'public class NewUtil {}\\n' > NewUtil.java"));

    AgentResult result = scenario.worker().execute(scenario.context());

    String patch = result.outputs().get("patch.diff");
    assertThat(patch)
        .contains("diff --git a/NewUtil.java b/NewUtil.java")
        .contains("new file mode 100644")
        .contains("--- /dev/null")
        .contains("+++ b/NewUtil.java")
        .contains("+public class NewUtil {}");

    Path consumer = consumerCheckout(scenario.sourceRepo(), baseRevision(result), patch, "T19-A");
    assertThat(Files.readString(consumer.resolve("NewUtil.java")))
        .isEqualTo("public class NewUtil {}\n");
  }

  @Test
  void mixedTrackedAndUntrackedChangesAreFullyExported() throws Exception {
    Scenario scenario = scenario("T19-B", (s, handle) -> {
      exec(s.sandbox(), handle,
          "cd repo && printf '# Demo\\n\\nBaseline.\\nT19 change.\\n' > README.md");
      exec(s.sandbox(), handle,
          "cd repo && printf 'public class NewUtil {}\\n' > NewUtil.java");
    });

    AgentResult result = scenario.worker().execute(scenario.context());

    String patch = result.outputs().get("patch.diff");
    assertThat(patch)
        .contains("diff --git a/README.md b/README.md")
        .contains("+T19 change.")
        .contains("diff --git a/NewUtil.java b/NewUtil.java")
        .contains("new file mode 100644")
        .contains("+public class NewUtil {}");

    Path consumer = consumerCheckout(scenario.sourceRepo(), baseRevision(result), patch, "T19-B");
    assertThat(Files.readString(consumer.resolve("README.md")))
        .isEqualTo("# Demo\n\nBaseline.\nT19 change.\n");
    assertThat(Files.readString(consumer.resolve("NewUtil.java")))
        .isEqualTo("public class NewUtil {}\n");
  }

  @Test
  void committedAndUncommittedChangesAreAllAnchoredAtRecordedBase() throws Exception {
    Scenario scenario = scenario("T19-C", (s, handle) -> {
      SandboxService sandbox = s.sandbox();
      exec(sandbox, handle, "cd repo && git config user.name model && "
          + "git config user.email model@factory.invalid");
      exec(sandbox, handle,
          "cd repo && printf '# Demo\\n\\nBaseline.\\ncommitted change.\\n' > README.md "
              + "&& git add -A && git commit -qm model-change");
      exec(sandbox, handle,
          "cd repo && printf 'public class NewUtil {}\\n' > NewUtil.java");
    });

    AgentResult result = scenario.worker().execute(scenario.context());

    String patch = result.outputs().get("patch.diff");
    assertThat(patch)
        .contains("diff --git a/README.md b/README.md")
        .contains("+committed change.")
        .contains("diff --git a/NewUtil.java b/NewUtil.java")
        .contains("new file mode 100644");

    Path consumer = consumerCheckout(scenario.sourceRepo(), baseRevision(result), patch, "T19-C");
    assertThat(Files.readString(consumer.resolve("README.md")))
        .contains("committed change.");
    assertThat(Files.readString(consumer.resolve("NewUtil.java")))
        .isEqualTo("public class NewUtil {}\n");
  }

  @Test
  void binaryNewFileIsExportedAsGitBinaryPatchAndSurvivesConsumerApply() throws Exception {
    Scenario scenario = scenario("T19-D", (s, handle) -> exec(s.sandbox(), handle,
        "cd repo && printf 'A\\000B\\377C\\000T19' > icon.bin"));

    AgentResult result = scenario.worker().execute(scenario.context());

    assertThat(result.outputs().get("patch.diff"))
        .contains("diff --git a/icon.bin b/icon.bin")
        .contains("new file mode 100644")
        .contains("GIT binary patch")
        .endsWith("\n\n");

    Path consumer = consumerCheckout(scenario.sourceRepo(), baseRevision(result),
        result.outputs().get("patch.diff"), "T19-D");
    assertThat(Files.mismatch(consumer.resolve("icon.bin"),
        expectedIcon())).isEqualTo(-1L);
  }

  @Test
  void stagedRenameIsExportedAsRename() throws Exception {
    Scenario scenario = scenario("T19-E", (s, handle) ->
        exec(s.sandbox(), handle, "cd repo && git mv App.java Renamed.java"));

    AgentResult result = scenario.worker().execute(scenario.context());

    assertThat(result.outputs().get("patch.diff"))
        .contains("diff --git a/App.java b/Renamed.java")
        .contains("rename from App.java")
        .contains("rename to Renamed.java");

    Path consumer = consumerCheckout(scenario.sourceRepo(), baseRevision(result),
        result.outputs().get("patch.diff"), "T19-E");
    assertThat(consumer.resolve("App.java")).doesNotExist();
    assertThat(Files.readString(consumer.resolve("Renamed.java"))).isEqualTo("class App {}\n");
  }

  @Test
  @org.junit.jupiter.api.Tag("eval-pack")
  void noOpRunStillProducesExplicitNoChangesPatch() throws Exception {
    Scenario scenario = scenario("T19-F", (s, handle) -> { });

    AgentResult result = scenario.worker().execute(scenario.context());

    assertThat(result.outputs().get("patch.diff")).isEqualTo("(no changes)\n");
  }

  @Test
  void exportFailureIsInfrastructureFailureAndStillTearsDownSandbox() throws Exception {
    Scenario scenario = scenario("T19-G", (s, handle) -> {
      // S04 blind-spot fix: a unique coding write exists before .git is
      // destroyed — the run has unique bytes no diff can recover.
      exec(s.sandbox(), handle,
          "cd repo && printf 'g-unique-coding-write\\n' > unique.txt");
      exec(s.sandbox(), handle, "cd repo && rm -rf .git");
    });

    assertThatThrownBy(() -> scenario.worker().execute(scenario.context()))
        .isInstanceOf(AgentExecutionException.class)
        .hasMessageContaining("sandbox");
    assertThat(scenario.workspaceDir()).doesNotExist();
    // T25: the sandbox teardown is only honest because an incomplete
    // EXPORT_FAILED bundle (no patch.diff entry) was published first — it is
    // the preserved copy of this attempt.
    RecoveryBundleStore.RecoveryBundle bundle = scenario.bundle().orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("EXPORT_FAILED");
    assertThat(bundle.artifactNames()).doesNotContain("patch.diff");
    assertThat(bundle.artifactNames())
        .as("the unique worktree bytes ride the bundle as one snapshot artifact")
        .contains(WORKSPACE_SNAPSHOT_ARTIFACT);
    assertThat(decodeSnapshotMember(bundle.directory(), "unique.txt"))
        .as("the bundled snapshot must decode back to the unique coding bytes")
        .isEqualTo("g-unique-coding-write\n");
    for (String name : bundle.artifactNames()) {
      assertThat(bundle.artifactSha256(name))
          .isEqualTo(ArtifactStore.sha256(Files.readString(bundle.directory().resolve(name))));
    }
  }

  @Test
  void persistenceFailureRetainsSandboxWorkspaceAndWorkDirForRecovery() throws Exception {
    Scenario scenario = scenario("T19-H", (s, handle) ->
        exec(s.sandbox(), handle,
            "cd repo && printf 'public class NewUtil {}\\n' > NewUtil.java"));
    Path workDirAsRegularFile = Files.createFile(root.resolve("T19-H-workdir-as-file"));
    scenario.workDir = workDirAsRegularFile;

    assertThatThrownBy(() -> scenario.worker().execute(scenario.context()))
        .isInstanceOf(AgentExecutionException.class)
        .hasMessageContaining("persist");
    assertThat(scenario.workspaceDir())
        .as("sandbox holding the model's unique change must be retained on persistence failure")
        .exists();
    assertThat(workDirAsRegularFile).exists();
    // T25: the retained workspace is now complemented by an incomplete
    // PERSIST_FAILED bundle carrying exactly the outputs that actually made
    // it to disk (here: none — the workDir itself was the obstruction) with
    // digests for whatever does appear.
    RecoveryBundleStore.RecoveryBundle bundle = scenario.bundle().orElseThrow();
    assertThat(bundle.completeness()).isEqualTo("INCOMPLETE");
    assertThat(bundle.failureReason()).isEqualTo("PERSIST_FAILED");
    assertThat(bundle.artifactNames())
        .as("nothing was written into the obstructed workDir, so nothing can be bundled")
        .isEmpty();
    for (String name : bundle.artifactNames()) {
      assertThat(bundle.artifactSha256(name))
          .isEqualTo(ArtifactStore.sha256(Files.readString(bundle.directory().resolve(name))));
    }
  }

  @Test
  void patchRejectedByConsumerCheckoutOfDivergedBase() throws Exception {
    Scenario scenario = scenario("T19-I", (s, handle) -> {
      exec(s.sandbox(), handle,
          "cd repo && printf '# Demo\\n\\nBaseline diverged.\\n' > README.md");
      exec(s.sandbox(), handle,
          "cd repo && printf 'public class NewUtil {}\\n' > NewUtil.java");
    });
    AgentResult result = scenario.worker().execute(scenario.context());
    String patch = result.outputs().get("patch.diff");
    String base = baseRevision(result);
    git(scenario.sourceRepo(), "checkout", "-q", "main");
    Files.writeString(scenario.sourceRepo().resolve("README.md"),
        "# Demo\n\nBaseline CONFLICTING with the exported change.\n");
    git(scenario.sourceRepo(), "add", "README.md");
    git(scenario.sourceRepo(), "commit", "-qm", "diverge-base");

    Path consumer = root.resolve("consumer-T19-I");
    git(root, "clone", "-q", scenario.sourceRepo().toString(), consumer.toString());
    git(consumer, "checkout", "-q", "HEAD");
    Files.write(consumer.resolve("incoming.diff"), patch.getBytes(StandardCharsets.UTF_8));

    int checkExit = runGit(consumer, "apply", "--check", "incoming.diff");
    assertThat(checkExit)
        .as("patch anchored at %s must not apply to a diverged base", base)
        .isNotZero();
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

  private String baseRevision(AgentResult result) throws IOException {
    JsonNode metadata = codec.parse(result.outputs().get("report.md")).metadata();
    String baseRevision = metadata.path("base_revision").asString("");
    assertThat(baseRevision)
        .as("report.md frontmatter must record the pinned base revision")
        .matches("[0-9a-f]{40,64}");
    return baseRevision;
  }

  private Path consumerCheckout(Path sourceRepo, String baseRevision, String patch, String tag)
      throws Exception {
    Path consumer = root.resolve("consumer-" + tag);
    git(root, "clone", "-q", sourceRepo.toString(), consumer.toString());
    git(consumer, "checkout", "-q", baseRevision);
    Path patchFile = consumer.resolve("incoming.diff");
    Files.write(patchFile, patch.getBytes(StandardCharsets.UTF_8));
    git(consumer, "apply", "--check", "incoming.diff");
    git(consumer, "apply", "incoming.diff");
    return consumer;
  }

  private static Path expectedIcon() throws IOException {
    Path expected = Files.createTempFile("t19-icon", ".bin");
    Files.write(expected, new byte[] {'A', 0, 'B', (byte) 0xFF, 'C', 0, 'T', '1', '9'});
    return expected;
  }

  private static void git(Path dir, String... args) throws Exception {
    GitResult result = runGitCapturing(dir, args);
    assertThat(result.exitCode())
        .as("git %s in %s failed%nstderr: %s", String.join(" ", args), dir, result.stderr())
        .isZero();
  }

  private static int runGit(Path dir, String... args) throws Exception {
    return runGitCapturing(dir, args).exitCode();
  }

  private static GitResult runGitCapturing(Path dir, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process p = new ProcessBuilder(command).directory(dir.toFile()).start();
    p.getInputStream().readAllBytes();
    String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    return new GitResult(p.waitFor(), stderr);
  }

  private record GitResult(int exitCode, String stderr) { }

  private Path createSourceRepo(String name) throws Exception {
    Path dir = root.resolve(name);
    Files.createDirectories(dir);
    git(dir, "init", "-b", "main");
    git(dir, "config", "user.name", "T19 Scenario");
    git(dir, "config", "user.email", "t19-scenario@factory.invalid");
    Files.writeString(dir.resolve("README.md"), "# Demo\n\nBaseline.\n");
    Files.writeString(dir.resolve("App.java"), "class App {}\n");
    git(dir, "add", "README.md", "App.java");
    git(dir, "commit", "-qm", "baseline");
    return dir.toAbsolutePath();
  }

  private final class Scenario {

    private final String taskId;
    private final UUID executionId = UUID.randomUUID();
    private final BiConsumer<Scenario, SandboxHandle> modelTurns;
    private final SandboxService sandbox;
    private final Path sourceRepo;
    private final CodingHarness harness = org.mockito.Mockito.mock(CodingHarness.class);
    private final RecoveryBundleStore recoveryStore =
        new RecoveryBundleStore(root.resolve("recovery"));
    private Path workDir;

    Scenario(String taskId, BiConsumer<Scenario, SandboxHandle> modelTurns) {
      try {
        this.taskId = taskId;
        this.modelTurns = modelTurns;
        this.sourceRepo = createSourceRepo("src-" + taskId);
        this.sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
            root.resolve("sbx-root"), Duration.ZERO, null), Clock.systemUTC());
        this.workDir = root.resolve("work-" + taskId);
        when(harness.run(any(), any(TaskContract.class), any())).thenAnswer(invocation -> {
          modelTurns.accept(this, invocation.getArgument(0));
          return new HarnessReport(3, HarnessReport.Outcome.COMPLETED,
              HarnessReport.StopReason.COMPLETED, 2, 128L, 0, 0L, 0L,
              TaskOutcome.SUCCEEDED, TaskOutcome.Reason.CHANGES_DELIVERED, "");
        });
      } catch (Exception e) {
        throw new IllegalStateException("scenario setup failed for " + taskId, e);
      }
    }

    CodingWorker worker() {
      return new CodingWorker(sandbox, harness, codec, recoveryStore);
    }

    java.util.Optional<RecoveryBundleStore.RecoveryBundle> bundle() {
      return recoveryStore.find(executionId, "coding", 1);
    }

    SandboxService sandbox() {
      return sandbox;
    }

    Path sourceRepo() {
      return sourceRepo;
    }

    Path workspaceDir() {
      // T22 R4/T25 S09: the workspace is owned by the execution, keyed
      // UNIFORMLY per attempt (this suite runs attempt-1 contexts).
      return workspaceDir(1);
    }

    Path workspaceDir(int attempt) {
      return root.resolve("sbx-root").resolve(
          "sbx-" + executionId + "-attempt-" + attempt);
    }

    AgentContext context() throws IOException {
      JsonNode payload = JsonMapper.builder().build().readTree("""
          {"taskId":"%s",
           "repoUrl":"%s",
           "baseBranch":"main",
           "branch":"dev/%s",
           "goal":"T19 scenario change"}
          """.formatted(taskId, sourceRepo.toString().replace("\\", "\\\\"), taskId));
      return new AgentContext(executionId, "coding", Map.of(), payload,
          Map.of("workDir", workDir.toString()),
          List.of("patch.diff", "report.md", "trajectory.jsonl"));
    }
  }
}
