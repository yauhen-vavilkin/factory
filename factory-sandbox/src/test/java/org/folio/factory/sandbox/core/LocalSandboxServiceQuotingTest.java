package org.folio.factory.sandbox.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.exception.SandboxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSandboxServiceQuotingTest {

  private static final String MAIN_TIP_SUBJECT = "chore: initial commit on main";
  private static final String BASE_X_TIP_SUBJECT = "feat: base x tip";

  @TempDir
  Path root;

  @TempDir
  Path sources;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-04T00:00:00Z"));

  @Test
  void hostileRepoUrlIsInertSingleWord() throws Exception {
    Path marker = root.resolve("marker-1");
    String repoUrl = sourceRepo(sources.resolve("repo-q1")) + "'; touch " + marker + "; '";
    LocalSandboxService service = newService(root, Duration.ZERO);

    assertThatThrownBy(() -> service.create(new SandboxSpec("t-q1", repoUrl, "main", "task/t-q1")))
        .isInstanceOf(SandboxException.class);
    assertThat(Files.exists(marker)).isFalse();
  }

  @Test
  void hostileBranchIsInertSingleWord() throws Exception {
    String repoUrl = sourceRepo(sources.resolve("repo-q2"));
    Path marker = root.resolve("marker-2");
    String branch = "task/1'; touch " + marker + "; '";
    LocalSandboxService service = newService(root, Duration.ZERO);

    assertThatThrownBy(() -> service.create(new SandboxSpec("t-q2", repoUrl, "main", branch)))
        .isInstanceOf(SandboxException.class);
    assertThat(Files.exists(marker)).isFalse();
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
