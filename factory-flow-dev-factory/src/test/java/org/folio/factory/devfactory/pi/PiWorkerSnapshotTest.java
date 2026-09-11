package org.folio.factory.devfactory.pi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.junit.jupiter.api.Test;

class PiWorkerSnapshotTest {

  @Test
  void stagedOnlyEditIsExportedAgainstBaseWithoutGitUsageText() throws Exception {
    Path repo = Files.createTempDirectory("pi-snapshot-");
    try {
      run(repo, "git", "init", "-q");
      run(repo, "git", "config", "user.email", "test@example.invalid");
      run(repo, "git", "config", "user.name", "test");
      Files.writeString(repo.resolve("README.md"), "base\n");
      run(repo, "git", "add", "README.md");
      run(repo, "git", "commit", "-qm", "base");
      String base = run(repo, "git", "rev-parse", "HEAD").trim();
      Files.writeString(repo.resolve("README.md"), "staged\n");
      run(repo, "git", "add", "README.md");

      SandboxHandle handle = new SandboxHandle("snapshot", "ignored");
      SandboxService service = new ShellBackedSandbox(repo);
      PiWorker worker = new PiWorker("pi-coding-worker", service, mock(PiCodingRunner.class), "", "gateway");
      CommandResult result = worker.snapshot(handle, base);

      assertThat(result.exitCode()).isZero();
      assertThat(result.stdout()).contains("diff --git").doesNotContain("usage: git diff");
      assertThat(result.stdout().getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(0);
    } finally {
      deleteTree(repo);
    }
  }

  private static String run(Path dir, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new IllegalStateException(output);
    return output;
  }

  private static void deleteTree(Path root) throws Exception {
    try (var paths = Files.walk(root)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try { Files.deleteIfExists(path); } catch (Exception e) { throw new RuntimeException(e); }
      });
    }
  }

  private static final class ShellBackedSandbox implements SandboxService {
    private final Path repo;
    ShellBackedSandbox(Path repo) { this.repo = repo; }
    @Override public SandboxHandle create(org.folio.factory.sandbox.api.SandboxSpec spec) {
      return new SandboxHandle("snapshot", "ignored");
    }
    @Override public CommandResult exec(SandboxHandle handle, String command, long timeoutSec) {
      String actual = command.replaceFirst("^cd repo && ", "cd " + java.util.regex.Matcher.quoteReplacement(repo.toString()) + " && ");
      try {
        long start = System.nanoTime();
        Process process = new ProcessBuilder("/bin/sh", "-c", actual).redirectErrorStream(false).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new CommandResult(exit, stdout, stderr, (System.nanoTime() - start) / 1_000_000L);
      } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public void teardown(SandboxHandle handle) { }
  }
}
