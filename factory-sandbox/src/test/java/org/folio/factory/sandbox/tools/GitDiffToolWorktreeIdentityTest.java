package org.folio.factory.sandbox.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.core.LocalSandboxService;
import org.folio.factory.sandbox.core.SandboxProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T24 B1: the working-tree content identity must cover the DELIVERED
 * CONTENT — untracked and staged files, binary contents, and content
 * committed during the run — not the truncated display diff. These tests
 * run a real git repository through the real {@link LocalSandboxService}
 * executor and reproduce the collisions the attempt-1 display-diff identity
 * was rejected for: each pair of states below yields an IDENTICAL
 * {@code git_diff} display (so the attempt-1 digest collided) while the
 * content identity must differ.
 */
class GitDiffToolWorktreeIdentityTest {

  /** Local-sandbox handles carry the workspace path as the container id. */
  private SandboxHandle handle;

  @TempDir
  Path root;

  private Path repo;
  private GitDiffTool tool;

  @BeforeEach
  void setUp() throws Exception {
    repo = root.resolve("ws").resolve("repo");
    Files.createDirectories(repo);
    handle = new SandboxHandle("sbx-t24-identity", root.resolve("ws").toAbsolutePath().toString());
    SandboxService sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
        root.resolve("ws"), Duration.ZERO, null), Clock.systemUTC());
    tool = new GitDiffTool(sandbox);
    git("init", "-b", "main");
    git("config", "user.name", "T24 Identity Test");
    git("config", "user.email", "t24-identity@factory.invalid");
    Files.writeString(repo.resolve("README.md"), "baseline\n");
    git("add", "README.md");
    git("commit", "-qm", "baseline");
  }

  /** Review collision (1): untracked fixture edited after a green check. */
  @Test
  void untrackedContentEditChangesIdentityWhileDisplayCollides() throws Exception {
    Files.writeString(repo.resolve("fixture.txt"), "state A\n");
    String stateA = identity();
    String displayA = display();
    Files.writeString(repo.resolve("fixture.txt"), "state B\n");
    String stateB = identity();
    String displayB = display();

    assertThat(stateB).isNotEqualTo(stateA);
    // The attempt-1 display identity collided: identical status, empty diff.
    assertThat(displayB).isEqualTo(displayA);
    assertThat(displayA).contains("?? fixture.txt");
    // Content identity is content-addressed: restoring the bytes restores it.
    Files.writeString(repo.resolve("fixture.txt"), "state A\n");
    assertThat(identity()).isEqualTo(stateA);
  }

  /**
   * Review collision (2): the file is staged and then edited AND re-staged —
   * porcelain stays {@code M } with an empty unstaged diff in both states, so
   * the attempt-1 display identity collided while the delivered bytes moved.
   */
  @Test
  void stagedThenRestagedEditChangesIdentityWhileDisplayCollides() throws Exception {
    Files.writeString(repo.resolve("README.md"), "staged one\n");
    git("add", "README.md");
    String stagedOne = identity();
    String displayOne = display();
    Files.writeString(repo.resolve("README.md"), "staged two\n");
    git("add", "README.md");
    String stagedTwo = identity();
    String displayTwo = display();

    assertThat(stagedTwo).isNotEqualTo(stagedOne);
    assertThat(displayTwo).isEqualTo(displayOne);
    assertThat(displayOne).contains("M  README.md");
    assertThat(displayOne).contains("(no changes)");
  }

  /**
   * Review collision (3): a stable giant a.txt diff sits before the relevant
   * z.txt change; the 50 KiB display truncation cuts inside the a.txt
   * section, so both states share an identical truncated display while the
   * delivered z.txt bytes differ.
   */
  @Test
  void deepByteChangeBeyondDisplayTruncationChangesIdentity() throws Exception {
    Files.writeString(repo.resolve("a.txt"), "base line\n");
    Files.writeString(repo.resolve("z.txt"), "v0\n");
    git("add", "a.txt", "z.txt");
    git("commit", "-qm", "add a and z");
    String giant = ("line " + "a".repeat(96) + "\n").repeat(1100); // ~110 KiB
    Files.writeString(repo.resolve("a.txt"), giant);
    Files.writeString(repo.resolve("z.txt"), "v1\n");
    String stateOne = identity();
    ToolResult displayOne = tool.diff(handle);
    Files.writeString(repo.resolve("z.txt"), "v2\n");
    String stateTwo = identity();
    ToolResult displayTwo = tool.diff(handle);

    assertThat(stateTwo).isNotEqualTo(stateOne);
    // The attempt-1 source (truncated display diff) collided on these states.
    assertThat(displayTwo.output()).isEqualTo(displayOne.output());
    assertThat(displayOne.output()).contains("[output truncated:");
    assertThat(displayOne.output()).doesNotContain("+v1");
    assertThat(displayTwo.output()).doesNotContain("+v2");
  }

  /**
   * Binary contents are invisible in the plain display diff ("Binary files
   * differ", no bytes shown); the content identity still tracks the actual
   * delivered binary bytes.
   */
  @Test
  void binaryContentChangeChangesIdentity() throws Exception {
    Files.write(repo.resolve("icon.bin"), new byte[] {0, 1, 2, 3});
    git("add", "icon.bin");
    git("commit", "-qm", "add icon");
    Files.write(repo.resolve("icon.bin"), new byte[] {0, 1, 2, 4});
    String stateOne = identity();
    ToolResult displayOne = tool.diff(handle);
    Files.write(repo.resolve("icon.bin"), new byte[] {0, 1, 2, 5});
    String stateTwo = identity();

    assertThat(stateTwo).isNotEqualTo(stateOne);
    assertThat(displayOne.output())
        .contains("Binary files a/icon.bin and b/icon.bin differ")
        .doesNotContain("[B@");
  }

  /**
   * Commits made during the run vanish from status/diff (clean tree) even
   * though the delivered content differs from the base; the content identity
   * must keep tracking the delivered bytes and stay invariant under the
   * commit itself.
   */
  @Test
  void commitDuringRunKeepsDeliveredIdentityDistinctFromBase() throws Exception {
    String base = identity();
    Files.writeString(repo.resolve("README.md"), "delivered content\n");
    String worktree = identity();
    git("add", "README.md");
    git("commit", "-qm", "model commit during the run");

    String committed = identity();

    assertThat(committed).isEqualTo(worktree);
    assertThat(committed).isNotEqualTo(base);
    // The attempt-1 display said "(no changes)" both at the base and after
    // the commit — the delivered change vanished from the display.
    assertThat(display()).contains("(no changes)");
  }

  @Test
  void identityIsValidTreeId() {
    assertThat(identity()).matches("[0-9a-f]{40,64}");
  }

  private String identity() {
    ToolResult result = tool.worktreeIdentity(handle);
    assertThat(result.ok()).as("worktree identity must succeed: %s", result.error()).isTrue();
    return result.output();
  }

  private String display() {
    ToolResult result = tool.diff(handle);
    assertThat(result.ok()).as("diff must succeed: %s", result.error()).isTrue();
    return result.output();
  }

  private void git(String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process p = new ProcessBuilder(command).directory(repo.toFile()).start();
    p.getInputStream().readAllBytes();
    p.getErrorStream().readAllBytes();
    assertThat(p.waitFor()).as("git %s failed", String.join(" ", args)).isZero();
  }
}
