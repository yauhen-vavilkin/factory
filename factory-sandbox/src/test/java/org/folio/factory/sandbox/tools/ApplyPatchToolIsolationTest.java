package org.folio.factory.sandbox.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.core.LocalSandboxService;
import org.folio.factory.sandbox.core.SandboxProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplyPatchToolIsolationTest {

  private static final String OWNER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String OWNER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final Pattern PATCH_PATH = Pattern.compile("/tmp/factory-patch\\S*");

  @Mock
  private SandboxService sandboxService;

  /**
   * T23 R5: each handle stages its patch under its own path, so two handles
   * never share one staging file. Pre-fix both handles staged through the
   * single shared /tmp/factory-patch.diff.
   */
  @Test
  void stagingPathIsUniquePerHandleAndSanitized() {
    when(sandboxService.exec(any(SandboxHandle.class), anyString(), anyLong()))
        .thenReturn(new CommandResult(0, "", "", 1));
    ApplyPatchTool tool = new ApplyPatchTool(sandboxService);
    SandboxHandle handleA = new SandboxHandle("sbx-" + OWNER_A + "/raw", "ca");
    SandboxHandle handleB = new SandboxHandle("sbx-" + OWNER_B, "cb");

    tool.apply(handleA, newFileDiff("a.txt", "content-a"));
    tool.apply(handleB, newFileDiff("b.txt", "content-b"));

    ArgumentCaptor<SandboxHandle> handles = ArgumentCaptor.forClass(SandboxHandle.class);
    ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
    verify(sandboxService, times(6)).exec(handles.capture(), commands.capture(), anyLong());
    Map<String, List<String>> byHandle = new LinkedHashMap<>();
    for (int i = 0; i < handles.getAllValues().size(); i++) {
      byHandle.computeIfAbsent(handles.getAllValues().get(i).sandboxId(), key -> new ArrayList<>())
          .add(commands.getAllValues().get(i));
    }
    String pathA = solePatchPath(byHandle.get("sbx-" + OWNER_A + "/raw"));
    String pathB = solePatchPath(byHandle.get("sbx-" + OWNER_B));
    assertNotEquals(pathA, pathB, "each handle must stage through its own patch path");
    assertTrue(pathA.contains("sbx-" + OWNER_A + "_raw"), pathA);
    String fileNameA = pathA.substring(pathA.lastIndexOf('/') + 1);
    assertEquals(fileNameA.replaceAll("[^A-Za-z0-9._-]", "_"),
        fileNameA, "path must be shell-safe for the sanitized sandbox id");
  }

  /**
   * T23 R5 concurrency shape: two real local-sandbox executions (distinct
   * ownerId workspaces) interleave apply() staging round by round. With the
   * legacy shared /tmp staging path, worker B's staged diff overwrites
   * worker A's between A's --check and apply, so one side observes a foreign
   * diff (wrong content applied or check/apply failure). Per-handle paths
   * must keep every apply clean and every workspace free of the other
   * side's files.
   */
  @Test
  void concurrentAppliesOnTwoLocalHandlesDoNotCrossContaminate(
      @TempDir Path root, @TempDir Path sources) throws Exception {
    String repoUrl = sourceRepo(sources.resolve("src"));
    LocalSandboxService service = new LocalSandboxService(
        new SandboxProperties("local", null, null, root, Duration.ZERO, null),
        Clock.systemUTC());
    SandboxHandle handleA = service.create(
        new SandboxSpec("t-iso", repoUrl, "main", "task/t-iso", OWNER_A));
    SandboxHandle handleB = service.create(
        new SandboxSpec("t-iso", repoUrl, "main", "task/t-iso", OWNER_B));
    ApplyPatchTool tool = new ApplyPatchTool(service);
    int rounds = 16;
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<List<ToolResult>> sideA = pool.submit(
          () -> applyRounds(tool, handleA, "a", rounds, barrier));
      Future<List<ToolResult>> sideB = pool.submit(
          () -> applyRounds(tool, handleB, "b", rounds, barrier));
      for (ToolResult result : sideA.get(180, TimeUnit.SECONDS)) {
        assertTrue(result.ok(), "side a apply failed: " + result.error());
      }
      for (ToolResult result : sideB.get(180, TimeUnit.SECONDS)) {
        assertTrue(result.ok(), "side b apply failed: " + result.error());
      }
      assertRepoHoldsOnlyOwnRounds(Path.of(handleA.containerId()).resolve("repo"), "a", rounds);
      assertRepoHoldsOnlyOwnRounds(Path.of(handleB.containerId()).resolve("repo"), "b", rounds);
    } finally {
      pool.shutdownNow();
    }
  }

  private static List<ToolResult> applyRounds(ApplyPatchTool tool, SandboxHandle handle,
      String side, int rounds, CyclicBarrier barrier) throws Exception {
    List<ToolResult> results = new ArrayList<>();
    for (int i = 0; i < rounds; i++) {
      barrier.await(60, TimeUnit.SECONDS);
      results.add(tool.apply(handle, newFileDiff("round-" + side + "-" + i + ".txt",
          "content-" + side + "-" + i)));
    }
    return results;
  }

  private static void assertRepoHoldsOnlyOwnRounds(Path repo, String side, int rounds)
      throws Exception {
    List<String> foreign = new ArrayList<>();
    int own = 0;
    try (var files = Files.list(repo)) {
      for (Path file : files.filter(path -> path.getFileName().toString().startsWith("round-"))
          .toList()) {
        String name = file.getFileName().toString();
        if (name.startsWith("round-" + side + "-")) {
          own++;
          assertEquals("content-" + side + "-"
              + name.substring(("round-" + side + "-").length(), name.length() - ".txt".length()),
              Files.readString(file, UTF_8).stripTrailing(), file.toString());
        } else {
          foreign.add(name);
        }
      }
    }
    assertEquals(List.of(), foreign, "workspace must not contain the other execution's patches");
    assertEquals(rounds, own, "every round of this side's patches must be present");
  }

  private static String solePatchPath(List<String> commands) {
    List<String> paths = new ArrayList<>();
    for (String command : commands) {
      Matcher matcher = PATCH_PATH.matcher(command);
      assertTrue(matcher.find(), command);
      paths.add(matcher.group());
    }
    assertEquals(3, paths.size(), String.valueOf(commands));
    for (String path : paths) {
      assertEquals(paths.get(0), path, "stage/check/apply must use one path per handle");
    }
    return paths.get(0);
  }

  private static String newFileDiff(String path, String content) {
    return "diff --git a/" + path + " b/" + path + "\n"
        + "new file mode 100644\n"
        + "--- /dev/null\n"
        + "+++ b/" + path + "\n"
        + "@@ -0,0 +1 @@\n"
        + "+" + content + "\n";
  }

  private static String sourceRepo(Path dir) throws Exception {
    Files.createDirectories(dir);
    runGit(dir, "init", "-b", "main");
    runGit(dir, "config", "user.name", "Factory Test");
    runGit(dir, "config", "user.email", "factory-test@example.invalid");
    runGit(dir, "config", "commit.gpgsign", "false");
    Files.writeString(dir.resolve("README.md"), "# base\n");
    runGit(dir, "add", "README.md");
    runGit(dir, "commit", "-m", "chore: initial commit on main");
    return dir.toAbsolutePath().toString();
  }

  private static void runGit(Path cwd, String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command)
        .directory(cwd.toFile())
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), UTF_8);
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      throw new IllegalStateException("git timed out: " + command + System.lineSeparator() + output);
    }
    assertEquals(0, process.exitValue(),
        "git " + List.of(args) + " in " + cwd + " failed:" + System.lineSeparator() + output);
  }
}
