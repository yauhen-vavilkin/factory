package org.folio.factory.devfactory.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.folio.factory.core.service.ArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Trusted delivery against real local Git repositories standing in for the
 * authoritative GitHub repository and the operator's fork: the frozen
 * candidate is reproduced from the exact base, identity-checked, pushed once
 * and turned into one pull request; every refusal leaves the remotes untouched.
 */
class TrustedDeliveryServiceTest {
  private static final String AUTHORITATIVE = "folio-org/mod-example";
  private static final String FORK = "operator/mod-example";
  private final JsonMapper json = JsonMapper.builder().build();

  @TempDir
  Path root;
  private String base;
  private String patch;
  private String tree;
  private FakeGitHub github;

  @BeforeEach
  void setUp() throws Exception {
    Path work = Files.createDirectories(root.resolve("work"));
    git(work, "init", "--quiet", "-b", "master");
    Files.writeString(work.resolve("README.md"), "# Example\n");
    Files.writeString(work.resolve("obsolete.txt"), "remove me\n");
    git(work, "add", "-A");
    git(work, "commit", "--quiet", "-m", "base");
    base = git(work, "rev-parse", "HEAD").trim();
    git(root, "clone", "--quiet", "--bare", work.toString(), remote(AUTHORITATIVE).toString());
    git(root, "clone", "--quiet", "--bare", work.toString(), remote(FORK).toString());

    Files.writeString(work.resolve("README.md"), "# Example\n\nTIMEOUT defaults to 60.\n");
    Files.delete(work.resolve("obsolete.txt"));
    Files.createDirectories(work.resolve("src"));
    Files.writeString(work.resolve("src/App.java"), "class App {}\n");
    Files.write(work.resolve("src/logo.bin"), new byte[] {0, 1, 2, (byte) 0xff, 10, 13});
    git(work, "add", "-A");
    patch = git(work, "diff", "--cached", "--binary", base);
    tree = git(work, "write-tree").trim();
    github = new FakeGitHub();
    github.repositories.put(AUTHORITATIVE, new DeliveryGitHub.RepositoryInfo(AUTHORITATIVE, false, "", "", "master"));
    github.repositories.put(FORK, new DeliveryGitHub.RepositoryInfo(FORK, true, AUTHORITATIVE, AUTHORITATIVE,
        "master"));
  }

  @Test
  void deliversTheExactVerifiedCandidateToTheForkOnceAndReusesItOnRepeat() throws Exception {
    TrustedDeliveryService service = service("delivery-token", "operator");

    ObjectNode first = service.deliver(evidence(candidate(patch, tree), verification(tree), result("SUCCESS")));

    assertThat(first.path("status").asString()).as(first.toString()).isEqualTo("DELIVERED");
    String branch = TrustedDeliveryService.branchName("MODEX-1", ArtifactStore.sha256(patch));
    assertThat(first.path("branch").asString()).isEqualTo(branch).startsWith("factory/MODEX-1-");
    assertThat(first.path("targetRepository").asString()).isEqualTo(FORK);
    assertThat(first.path("reproducedTree").asString()).isEqualTo(tree);
    String commit = first.path("commitSha").asString();
    Path fork = remote(FORK);
    assertThat(git(fork, "rev-parse", "refs/heads/" + branch).trim()).isEqualTo(commit);
    assertThat(git(fork, "rev-parse", commit + "^{tree}").trim()).isEqualTo(tree);
    assertThat(git(fork, "rev-parse", commit + "^").trim()).isEqualTo(base);
    // The delivered change is byte-identical to the verified candidate patch.
    assertThat(git(fork, "diff", "--binary", base, commit)).isEqualTo(patch);
    assertThat(heads(AUTHORITATIVE)).isEqualTo("refs/heads/master");
    assertThat(github.created).hasSize(1);
    assertThat(first.path("pullRequest").path("reused").asBoolean()).isFalse();
    assertThat(github.created.get(0)).contains("head=" + branch, "base=master",
        "Patch SHA-256: `" + ArtifactStore.sha256(patch) + "`", "mvn -B -ntp test");

    ObjectNode repeat = service.deliver(evidence(candidate(patch, tree), verification(tree), result("SUCCESS")));

    assertThat(repeat.path("status").asString()).isEqualTo("DELIVERED");
    assertThat(repeat.path("commitSha").asString()).isEqualTo(commit);
    assertThat(repeat.path("branchReused").asBoolean()).isTrue();
    assertThat(repeat.path("pullRequest").path("reused").asBoolean()).isTrue();
    assertThat(repeat.path("pullRequest").path("url").asString())
        .isEqualTo(first.path("pullRequest").path("url").asString());
    assertThat(github.created).hasSize(1);
  }

  @Test
  void patchBytesThatDoNotMatchTheFrozenIdentityAreRefusedBeforeAnyRemoteCall() throws Exception {
    String tampered = patch.replace("TIMEOUT defaults to 60.", "TIMEOUT defaults to 90.");
    ObjectNode candidate = candidate(patch, tree);

    ObjectNode record = service("delivery-token", "operator")
        .deliver(evidence(candidate, verification(tree), result("SUCCESS"), tampered));

    assertThat(record.path("status").asString()).isEqualTo("REFUSED");
    assertThat(record.path("reason").asString()).isEqualTo("CANDIDATE_IDENTITY_MISMATCH");
    assertNoRemoteMutation();
    assertThat(github.calls).isEmpty();
  }

  @Test
  void reproductionThatDoesNotYieldTheVerifiedTreeIsRefusedBeforePush() throws Exception {
    String otherTree = "0123456789abcdef0123456789abcdef01234567";

    ObjectNode record = service("delivery-token", "operator")
        .deliver(evidence(candidate(patch, otherTree), verification(otherTree), result("SUCCESS")));

    assertThat(record.path("status").asString()).isEqualTo("REFUSED");
    assertThat(record.path("reason").asString()).isEqualTo("CANDIDATE_IDENTITY_MISMATCH");
    assertThat(record.path("reproducedTree").asString()).isEqualTo(tree);
    assertNoRemoteMutation();
    assertThat(github.created).isEmpty();
  }

  @Test
  void onlySuccessfulIndependentlyVerifiedExecutionsAreDeliverable() throws Exception {
    TrustedDeliveryService service = service("delivery-token", "operator");
    for (String outcome : List.of("FAILED", "BLOCKED_ENVIRONMENT", "ERROR", "CANCELLED", "NEEDS_DECISION")) {
      ObjectNode record = service.deliver(evidence(candidate(patch, tree), verification(tree), result(outcome)));
      assertThat(record.path("reason").asString()).as(outcome).isEqualTo("OUTCOME_NOT_SUCCESS");
    }
    ObjectNode paused = service.deliver(new TrustedDeliveryService.Evidence(
        TrustedDeliveryService.Source.COMPLETED_EXECUTION, "exec-1", "AWAITING_HITL", task(),
        candidate(patch, tree), patch, verification(tree), result("SUCCESS")));
    assertThat(paused.path("reason").asString()).isEqualTo("EXECUTION_NOT_COMPLETED");

    ObjectNode failedVerification = verification(tree).put("status", "FAIL");
    ObjectNode notIndependent = verification(tree).put("independent", false);
    ObjectNode staleTree = verification(tree).put("candidateTreeMatches", false);
    assertThat(flowStep(service, failedVerification).path("reason").asString()).isEqualTo("VERIFICATION_NOT_PASSED");
    assertThat(flowStep(service, notIndependent).path("reason").asString())
        .isEqualTo("VERIFICATION_NOT_INDEPENDENT");
    assertThat(flowStep(service, staleTree).path("reason").asString()).isEqualTo("CANDIDATE_IDENTITY_MISMATCH");
    ObjectNode otherBase = verification(tree).put("base", "f".repeat(40));
    assertThat(flowStep(service, otherBase).path("reason").asString()).isEqualTo("BASE_MISMATCH");

    assertNoRemoteMutation();
    assertThat(github.calls).isEmpty();
  }

  @Test
  void targetThatIsNotAForkOfTheAuthoritativeRepositoryIsRefused() throws Exception {
    github.repositories.put(FORK, new DeliveryGitHub.RepositoryInfo(FORK, true, "someone/else", "someone/else",
        "master"));

    ObjectNode record = service("delivery-token", "operator")
        .deliver(evidence(candidate(patch, tree), verification(tree), result("SUCCESS")));

    assertThat(record.path("reason").asString()).isEqualTo("TARGET_REPOSITORY_NOT_ALLOWED");
    assertNoRemoteMutation();
  }

  @Test
  void existingBranchWithDifferentContentIsNeverOverwritten() throws Exception {
    String branch = TrustedDeliveryService.branchName("MODEX-1", ArtifactStore.sha256(patch));
    git(remote(FORK), "branch", branch, base);

    ObjectNode record = service("delivery-token", "operator")
        .deliver(evidence(candidate(patch, tree), verification(tree), result("SUCCESS")));

    assertThat(record.path("reason").asString()).isEqualTo("BRANCH_CONFLICT");
    assertThat(git(remote(FORK), "rev-parse", "refs/heads/" + branch).trim()).isEqualTo(base);
    assertThat(github.created).isEmpty();
  }

  @Test
  void missingDeliveryCredentialRefusesWithoutAnyRemoteCall() throws Exception {
    ObjectNode record = service("", "operator")
        .deliver(evidence(candidate(patch, tree), verification(tree), result("SUCCESS")));

    assertThat(record.path("reason").asString()).isEqualTo(TrustedDeliveryService.CREDENTIALS_NOT_CONFIGURED);
    assertThat(github.calls).isEmpty();
    assertNoRemoteMutation();
  }

  private ObjectNode flowStep(TrustedDeliveryService service, JsonNode verification) {
    return service.deliver(new TrustedDeliveryService.Evidence(TrustedDeliveryService.Source.FLOW_STEP,
        "exec-1", null, task(), candidate(patch, tree), patch, verification, null));
  }

  private void assertNoRemoteMutation() throws Exception {
    assertThat(heads(FORK)).isEqualTo("refs/heads/master");
    assertThat(heads(AUTHORITATIVE)).isEqualTo("refs/heads/master");
  }

  private String heads(String slug) throws Exception {
    return git(remote(slug), "for-each-ref", "--format=%(refname)", "refs/heads").trim();
  }

  private TrustedDeliveryService service(String token, String forkOwner) {
    return new TrustedDeliveryService(github, token, forkOwner, "Factory Test", "factory@example.org",
        slug -> remote(slug).toUri().toString());
  }

  private TrustedDeliveryService.Evidence evidence(JsonNode candidate, JsonNode verification, JsonNode result) {
    return evidence(candidate, verification, result, patch);
  }

  private TrustedDeliveryService.Evidence evidence(JsonNode candidate, JsonNode verification, JsonNode result,
                                                   String patchBytes) {
    return new TrustedDeliveryService.Evidence(TrustedDeliveryService.Source.COMPLETED_EXECUTION, "exec-1",
        "COMPLETED", task(), candidate, patchBytes, verification, result);
  }

  private ObjectNode task() {
    ObjectNode task = json.createObjectNode();
    task.put("taskId", "MODEX-1");
    task.put("repoUrl", "https://github.com/" + AUTHORITATIVE + ".git");
    task.put("baseRevision", base);
    task.put("goal", "Document the timeout. Implement the complete task.");
    task.putArray("acceptanceCriteria").addObject().put("id", "AC1").put("text", "README documents TIMEOUT.");
    ObjectNode resolved = task.putObject("resolvedIntent");
    resolved.putObject("repository").put("canonicalSlug", AUTHORITATIVE).put("exactRevision", base)
        .put("status", "RESOLVED");
    resolved.putObject("task").put("deliveryMode", "DELIVER_PR");
    resolved.putObject("verificationPlan").put("id", "java-maven-verify");
    return task;
  }

  private ObjectNode candidate(String patchBytes, String candidateTree) {
    return json.createObjectNode().put("status", "READY").put("base", base).put("candidateTree", candidateTree)
        .put("patchSha256", ArtifactStore.sha256(patchBytes));
  }

  private ObjectNode verification(String candidateTree) {
    ObjectNode verification = json.createObjectNode().put("status", "PASS").put("independent", true)
        .put("freshCheckout", true).put("base", base).put("candidateTree", candidateTree)
        .put("candidateTreeMatches", true).put("candidateIdentityMatches", true).put("planId", "java-maven-verify");
    verification.putArray("checks").addObject().put("id", "maven-tests").put("command", "mvn -B -ntp test")
        .put("exitCode", 0);
    return verification;
  }

  private ObjectNode result(String outcome) {
    return json.createObjectNode().put("outcome", outcome);
  }

  private Path remote(String slug) {
    return root.resolve("remotes").resolve(slug + ".git");
  }

  private static String git(Path directory, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(Arrays.asList(args));
    Files.createDirectories(directory);
    Path output = Files.createTempFile("delivery-test-git-", ".log");
    ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
        .redirectErrorStream(true).redirectOutput(output.toFile());
    Map<String, String> env = builder.environment();
    env.put("GIT_CONFIG_NOSYSTEM", "1");
    env.put("GIT_CONFIG_GLOBAL", "/dev/null");
    env.put("GIT_AUTHOR_NAME", "Test");
    env.put("GIT_AUTHOR_EMAIL", "test@example.org");
    env.put("GIT_COMMITTER_NAME", "Test");
    env.put("GIT_COMMITTER_EMAIL", "test@example.org");
    Process process = builder.start();
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
    String text = Files.readString(output, StandardCharsets.UTF_8);
    Files.delete(output);
    assertThat(process.exitValue()).as(String.join(" ", command) + "\n" + text).isZero();
    return text;
  }

  /** GitHub stand-in whose branch reads come from the local bare repositories. */
  private final class FakeGitHub implements DeliveryGitHub {
    final Map<String, RepositoryInfo> repositories = new java.util.HashMap<>();
    final List<String> calls = new ArrayList<>();
    final List<String> created = new ArrayList<>();
    final Map<String, PullRequest> pulls = new java.util.HashMap<>();

    @Override
    public RepositoryInfo repository(String slug) {
      calls.add("repository " + slug);
      return repositories.get(slug);
    }

    @Override
    public boolean branchContains(String slug, String branch, String sha) {
      calls.add("branchContains " + slug);
      try {
        git(remote(slug), "merge-base", "--is-ancestor", sha, "refs/heads/" + branch);
        return true;
      } catch (AssertionError | IOException | InterruptedException notAncestor) {
        return false;
      }
    }

    @Override
    public Optional<RemoteCommit> branchHead(String slug, String branch) {
      calls.add("branchHead " + slug + " " + branch);
      try {
        if (git(remote(slug), "for-each-ref", "refs/heads/" + branch).isBlank()) {
          return Optional.empty();
        }
        String sha = git(remote(slug), "rev-parse", "refs/heads/" + branch).trim();
        String[] parts = git(remote(slug), "log", "-1", "--format=%T %P", sha).trim().split(" ");
        return Optional.of(new RemoteCommit(sha, parts[0],
            Arrays.asList(parts).subList(1, parts.length)));
      } catch (IOException | InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public Optional<PullRequest> findPullRequest(String slug, String owner, String branch) {
      calls.add("findPullRequest " + slug);
      return Optional.ofNullable(pulls.get(slug + " " + owner + ":" + branch));
    }

    @Override
    public PullRequest createPullRequest(String slug, String headBranch, String baseBranch, String title,
                                         String body) {
      calls.add("createPullRequest " + slug);
      created.add("head=" + headBranch + " base=" + baseBranch + " title=" + title + "\n" + body);
      PullRequest pull = new PullRequest(created.size(), "https://github.com/" + slug + "/pull/" + created.size(),
          "open", "");
      pulls.put(slug + " " + slug.substring(0, slug.indexOf('/')) + ":" + headBranch, pull);
      return pull;
    }
  }
}
