package org.folio.factory.devfactory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.core.LocalSandboxService;
import org.folio.factory.sandbox.core.SandboxProperties;
import org.folio.factory.sandbox.harness.ChatModelAdapter;
import org.folio.factory.sandbox.harness.ChatMessage;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.folio.factory.sandbox.harness.HarnessConfig;
import org.folio.factory.sandbox.harness.ModelReply;
import org.folio.factory.sandbox.harness.ToolDispatcher;
import org.folio.factory.sandbox.tools.ApplyPatchTool;
import org.folio.factory.sandbox.tools.ExecTool;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ListTool;
import org.folio.factory.sandbox.tools.ReadTool;
import org.folio.factory.sandbox.tools.TestTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared machinery for the T21 hermetic eval-pack scenarios. Everything is
 * real except the model: a turn-queued {@link ChatModelAdapter} script drives
 * the real {@link CodingHarness} decision tree, the real tool dispatcher
 * (read/list/apply_patch/exec/git_diff/test) and the real {@link CodingWorker}
 * export over a real local sandbox cloning a real git fixture repository. No
 * live provider is contacted and no API key is used — this is hermetic,
 * scripted-model evidence, deliberately separated from any live-provider run.
 *
 * <p>Fixture tasks are deliberately tiny shell programs so the observable
 * criteria (a check script flipping red to green in a fresh consumer
 * checkout) run everywhere git and a POSIX shell do.</p>
 */
final class EvalPackScenarios {

  /** The greeting task contract shared by the regression and no-op shapes. */
  static final String REGRESSION_GOAL =
      "Fix the greeting regression in greet.sh so that sh check.sh passes.";

  static final String REGRESSION_ACCEPTANCE = """
      - sh check.sh exits 0 after the change
      - sh check.sh fails on the recorded base before the change""";

  static final String REGRESSION_NOTES =
      "The greeting regressed in release 4.2: greet.sh prints Goodbye instead of Hello.";

  /** The model's fix for the planted regression (git diff format). */
  static final String GREETING_FIX = String.join("\n",
      "diff --git a/greet.sh b/greet.sh",
      "--- a/greet.sh",
      "+++ b/greet.sh",
      "@@ -1,2 +1,2 @@",
      " #!/bin/sh",
      "-echo \"Goodbye, $1.\"",
      "+echo \"Hello, $1.\"",
      "");

  static final String CHECK = String.join("\n",
      "#!/bin/sh",
      "out=$(sh greet.sh World)",
      "expected=\"Hello, World.\"",
      "if [ \"$out\" = \"$expected\" ]; then",
      "  echo \"OK: greet.sh prints '$expected'\"",
      "  exit 0",
      "fi",
      "echo \"FAIL: greet.sh printed '$out', expected '$expected'\"",
      "exit 1",
      "");

  private static final String README = """
      # Greeting service

      Spec: `sh greet.sh <name>` prints `Hello, <name>.`
      `sh check.sh` verifies the spec and is the task's observable criterion.
      """;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final FrontmatterCodec codec = new FrontmatterCodec();
  private final Path root;

  EvalPackScenarios(Path root) {
    this.root = root;
  }

  /** One finished pack run: the worker result plus what was actually persisted. */
  record PackRun(String id, Path workDir, AgentResult result, long durationMs,
      String persistedReport, String persistedTrajectory) { }

  /**
   * Runs the full coding worker against the fixture repository with the
   * scripted model turns. The reply script is consumed strictly in order;
   * exhausting it fails loudly instead of silently becoming a MODEL_ERROR.
   */
  PackRun run(String id, Path fixtureRepo, JsonNode contractPayload,
      List<ModelReply> modelScript) throws Exception {
    SandboxService sandbox = new LocalSandboxService(new SandboxProperties("local", null, null,
        root.resolve("sbx-root-" + id), Duration.ZERO, null), Clock.systemUTC());
    ScriptedModel model = new ScriptedModel(id, modelScript);
    GitDiffTool gitDiffTool = new GitDiffTool(sandbox);
    CodingHarness harness = new CodingHarness(model,
        new ToolDispatcher(new ReadTool(sandbox), new ListTool(sandbox),
            new ApplyPatchTool(sandbox), new ExecTool(sandbox), gitDiffTool,
            new TestTool(sandbox)),
        gitDiffTool, Clock.systemUTC(), HarnessConfig.defaults());
    CodingWorker worker = new CodingWorker(sandbox, harness, codec);
    Path workDir = root.resolve("work-" + id);

    long started = System.nanoTime();
    AgentResult result = worker.execute(context(id, fixtureRepo, workDir, contractPayload));
    long durationMs = (System.nanoTime() - started) / 1_000_000L;

    return new PackRun(id, workDir, result, durationMs,
        Files.readString(workDir.resolve("report.md")),
        Files.readString(workDir.resolve("trajectory.jsonl")));
  }

  private AgentContext context(String id, Path repo, Path workDir, JsonNode contractPayload)
      throws IOException {
    JsonNode payload = JSON.readTree(contractPayload.toString());
    ((tools.jackson.databind.node.ObjectNode) payload)
        .put("taskId", id)
        .put("repoUrl", repo.toAbsolutePath().toString())
        .put("baseBranch", "main")
        .put("branch", "dev/" + id);
    return new AgentContext(UUID.randomUUID(), "coding", Map.of(), payload,
        Map.of("workDir", workDir.toString()),
        List.of("patch.diff", "report.md", "trajectory.jsonl"));
  }

  /**
   * The regression fixture: a planted bug (greet.sh says Goodbye where the
   * spec — and check.sh — require Hello) so the observable check is red at
   * the recorded base. Also the base fixture for the green variant.
   */
  Path createGreetingFixture(String name, String greetingLine) throws Exception {
    Path dir = root.resolve(name);
    Files.createDirectories(dir);
    git(dir, "init", "-b", "main");
    git(dir, "config", "user.name", "T21 Eval Pack");
    git(dir, "config", "user.email", "t21-eval-pack@factory.invalid");
    Files.writeString(dir.resolve("README.md"), README);
    Files.writeString(dir.resolve("greet.sh"), "#!/bin/sh\necho \"" + greetingLine + "\"\n");
    Files.writeString(dir.resolve("check.sh"), CHECK);
    git(dir, "add", "README.md", "greet.sh", "check.sh");
    git(dir, "commit", "-qm", "baseline");
    return dir.toAbsolutePath();
  }

  /** Fresh consumer checkout of the recorded base, optionally with the run's patch applied. */
  Path consumerCheckout(PackRun run, String tag, boolean withPatch) throws Exception {
    JsonNode metadata = codec.parse(run.persistedReport()).metadata();
    String base = metadata.path("base_revision").asString("");
    assertThat(base).as("report.md must record the pinned base revision").matches("[0-9a-f]{40,64}");
    Path consumer = root.resolve("consumer-" + tag);
    git(root, "clone", "-q", sourceRepo(run).toString(), consumer.toString());
    git(consumer, "checkout", "-q", base);
    if (withPatch) {
      String patch = run.result().outputs().get("patch.diff");
      if (!"(no changes)\n".equals(patch)) {
        Files.write(consumer.resolve("incoming.diff"), patch.getBytes(StandardCharsets.UTF_8));
        git(consumer, "apply", "incoming.diff");
      }
    }
    return consumer;
  }

  Path sourceRepo(PackRun run) {
    return root.resolve(run.id());
  }

  /** Runs a shell command in {@code dir}; returns exit code and combined output. */
  ShellResult shell(Path dir, String... command) throws Exception {
    Process p = new ProcessBuilder(command).directory(dir.toFile()).start();
    String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = p.waitFor();
    return new ShellResult(exit, stdout);
  }

  record ShellResult(int exitCode, String stdout) { }

  static final class ScriptedModel implements ChatModelAdapter {

    private final String id;
    private final Queue<ModelReply> script;

    ScriptedModel(String id, List<ModelReply> script) {
      this.id = id;
      this.script = new ArrayDeque<>(script);
    }

    @Override
    public ModelReply reply(String systemPrompt, String taskGoal, List<ChatMessage> history) {
      ModelReply reply = script.poll();
      if (reply == null) {
        throw new IllegalStateException("eval-pack script exhausted for " + id
            + ": the harness asked for a turn the script does not provide");
      }
      return reply;
    }
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
}
