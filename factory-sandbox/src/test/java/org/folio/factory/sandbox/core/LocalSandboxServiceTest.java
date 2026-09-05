package org.folio.factory.sandbox.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.exception.SandboxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSandboxServiceTest {

  private static final String MAIN_TIP_SUBJECT = "chore: initial commit on main";
  private static final String BASE_X_TIP_SUBJECT = "feat: base x tip";

  @TempDir
  Path root;

  @TempDir
  Path sources;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));

  @Test
  void createProducesFullCloneWorkspace() throws Exception {
    String repoUrl = sourceRepo(sources.resolve("repo-1"));
    LocalSandboxService service = newService(root, Duration.ZERO);

    SandboxHandle handle = service.create(new SandboxSpec("t-1", repoUrl, "main", "task/t-1"));

    assertThat(handle.sandboxId()).isEqualTo("sbx-t-1");
    Path workspace = Path.of(handle.containerId());
    assertThat(Files.isDirectory(workspace)).isTrue();
    assertThat(Files.isDirectory(workspace.resolve("repo/.git"))).isTrue();

    CommandResult shallow = service.exec(handle, "cd repo && git rev-parse --is-shallow-repository", 60L);

    assertThat(shallow.exitCode()).isZero();
    assertThat(shallow.stdout().trim()).isEqualTo("false");
  }

  @Test
  void createFromNonDefaultBaseStartsAtBaseTip() throws Exception {
    String repoUrl = sourceRepo(sources.resolve("repo-2"));
    LocalSandboxService service = newService(root, Duration.ZERO);

    SandboxHandle handle = service.create(new SandboxSpec("t-2", repoUrl, "feature/base-x", "task/t-1"));

    CommandResult head = service.exec(handle, "cd repo && git rev-parse --abbrev-ref HEAD", 60L);
    CommandResult subject = service.exec(handle, "cd repo && git log -1 --format=%s", 60L);

    assertThat(head.exitCode()).isZero();
    assertThat(head.stdout().trim()).isEqualTo("task/t-1");
    assertThat(subject.exitCode()).isZero();
    assertThat(subject.stdout().trim()).isEqualTo(BASE_X_TIP_SUBJECT);
  }

  @Test
  void execRunsInWorkspaceDirectory() throws Exception {
    String repoUrl = sourceRepo(sources.resolve("repo-3"));
    LocalSandboxService service = newService(root, Duration.ZERO);
    SandboxHandle handle = service.create(new SandboxSpec("t-3", repoUrl, "main", "task/t-1"));
    Path workspace = Path.of(handle.containerId());

    CommandResult pwd = service.exec(handle, "pwd", 60L);
    CommandResult branch = service.exec(handle, "cd repo && git rev-parse --abbrev-ref HEAD", 60L);
    CommandResult mixed = service.exec(handle, "echo out; echo err 1>&2; exit 3", 60L);

    assertThat(pwd.exitCode()).isZero();
    assertThat(pwd.stdout().trim()).isEqualTo(workspace.toRealPath().toString());
    assertThat(branch.stdout().trim()).isEqualTo("task/t-1");
    assertThat(mixed.exitCode()).isEqualTo(3);
    assertThat(mixed.stdout()).contains("out");
    assertThat(mixed.stderr()).contains("err");
    assertThat(mixed.durationMs()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void execTimeoutReturnsMinusOneWithTimeoutMarker() throws Exception {
    String repoUrl = sourceRepo(sources.resolve("repo-4"));
    LocalSandboxService service = newService(root, Duration.ZERO);
    SandboxHandle handle = service.create(new SandboxSpec("t-4", repoUrl, "main", "task/t-1"));

    CommandResult result = service.exec(handle, "sleep 30", 1L);

    assertThat(result.exitCode()).isEqualTo(-1);
    assertThat(result.stderr()).contains("timed out");
    assertThat(result.durationMs()).isGreaterThanOrEqualTo(0L);
    assertThat(result.durationMs()).isLessThan(15_000L);
  }

  @Test
  void teardownRemovesWorkspaceAndSecondTeardownIsNoOp() throws Exception {
    String repoUrl = sourceRepo(sources.resolve("repo-5"));
    LocalSandboxService service = newService(root, Duration.ZERO);
    SandboxHandle handle = service.create(new SandboxSpec("t-5", repoUrl, "main", "task/t-1"));
    Path workspace = Path.of(handle.containerId());

    service.teardown(handle);

    assertThat(Files.exists(workspace)).isFalse();
    assertThatCode(() -> service.teardown(handle)).doesNotThrowAnyException();
  }

  @Test
  void teardownWithRetentionMarksDirectoryAndCreateSweepsExpired() throws Exception {
    String repoUrl = sourceRepo(sources.resolve("repo-6"));
    LocalSandboxService service = newService(root, Duration.ofSeconds(60));
    SandboxHandle first = service.create(new SandboxSpec("t-a", repoUrl, "main", "task/t-a"));
    Path retained = Path.of(first.containerId());

    long teardownEpoch = clock.millis();
    service.teardown(first);

    assertThat(Files.isDirectory(retained)).isTrue();
    Path marker = retained.resolve(".factory-retained");
    assertThat(Files.isRegularFile(marker)).isTrue();
    assertThat(Long.parseLong(Files.readString(marker).trim())).isEqualTo(teardownEpoch);

    clock.advance(Duration.ofSeconds(61));
    SandboxHandle second = service.create(new SandboxSpec("t-b", repoUrl, "main", "task/t-b"));

    assertThat(Files.exists(retained)).isFalse();
    assertThat(Files.isDirectory(Path.of(second.containerId()))).isTrue();
  }

  @Test
  void createReplacesStaleWorkspaceLeftover() throws Exception {
    String repoUrl = sourceRepo(sources.resolve("repo-7"));
    LocalSandboxService service = newService(root, Duration.ZERO);
    Path staleDir = Files.createDirectories(root.resolve("sbx-t-7/repo/.git/objects"));
    Path staleArtifact = staleDir.resolve("stale.txt");
    Files.writeString(staleArtifact, "crash leftover");

    SandboxHandle handle = service.create(new SandboxSpec("t-7", repoUrl, "main", "task/t-7"));

    assertThat(Files.isDirectory(Path.of(handle.containerId()).resolve("repo/.git"))).isTrue();
    assertThat(Files.exists(staleArtifact)).isFalse();
  }

  @Test
  void createWithNonexistentRepoUrlThrowsCarryingGitStderr() {
    LocalSandboxService service = newService(root, Duration.ZERO);
    String missing = sources.resolve("no-such-repo").toAbsolutePath().toString();

    assertThatThrownBy(() -> service.create(new SandboxSpec("t-8", missing, "main", "task/t-8")))
        .isInstanceOf(SandboxException.class)
        .hasMessageContaining("fatal:")
        .hasMessageContaining("does not exist");
  }

  @Test
  void failedCloneLeavesNoWorkspaceDirectory() {
    LocalSandboxService service = newService(root, Duration.ZERO);
    String repoUrl = sources.resolve("no-such-repo").toAbsolutePath().toString();

    assertThatThrownBy(() -> service.create(new SandboxSpec("t-cleanup", repoUrl, "main", "task/t-cleanup")))
        .isInstanceOf(SandboxException.class);

    assertThat(Files.notExists(root.resolve("sbx-t-cleanup"))).isTrue();
  }

  @Test
  void createSanitizesTaskIdIntoWorkspaceDirectoryName() throws Exception {
    String repoUrl = sourceRepo(sources.resolve("repo-9"));
    LocalSandboxService service = newService(root, Duration.ZERO);

    SandboxHandle handle = service.create(new SandboxSpec("task/1", repoUrl, "main", "task/t-9"));

    Path sanitized = root.resolve("sbx-task_1");
    assertThat(Files.isDirectory(sanitized)).isTrue();
    assertThat(Files.isDirectory(sanitized.resolve("repo/.git"))).isTrue();
    assertThat(Files.isDirectory(Path.of(handle.containerId()).resolve("repo/.git"))).isTrue();
  }

  private LocalSandboxService newService(Path workspaceRoot, Duration retention) {
    SandboxProperties properties = new SandboxProperties("local", null, null, workspaceRoot, retention, null);
    return new LocalSandboxService(properties, clock);
  }

  private String sourceRepo(Path dir) throws Exception {
    Files.createDirectories(dir);
    runGit(dir, "init", "-b", "main");
    runGit(dir, "config", "user.name", "Factory Test");
    runGit(dir, "config", "user.email", "factory-test@example.invalid");
    runGit(dir, "config", "commit.gpgsign", "false");
    Files.writeString(dir.resolve("README.md"), "# base\n");
    runGit(dir, "add", "README.md");
    runGit(dir, "commit", "-m", MAIN_TIP_SUBJECT);
    runGit(dir, "checkout", "-b", "feature/base-x");
    Files.writeString(dir.resolve("README.md"), "# base-x\n");
    runGit(dir, "add", "README.md");
    runGit(dir, "commit", "-m", BASE_X_TIP_SUBJECT);
    runGit(dir, "checkout", "main");
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
    assertThat(process.exitValue())
        .as("git %s in %s failed:%n%s", List.of(args), cwd, output)
        .isZero();
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
