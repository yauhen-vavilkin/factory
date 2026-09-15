package org.folio.factory.devfactory.delivery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.folio.factory.core.service.ArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Trusted Developer Flow delivery. Runs only in the Factory process, never in a
 * coding sandbox, and operates only on the frozen verified candidate identity:
 * it re-checks that the verification evidence belongs to exactly that
 * candidate, reproduces the candidate from the exact authoritative base in a
 * private checkout, proves the reproduced tree equals the verified tree, and
 * only then pushes one deterministic branch and opens (or reuses) one pull
 * request with the delivery-scope credential. Every refusal happens before any
 * remote mutation.
 */
public final class TrustedDeliveryService {
  public static final String LOCAL_ONLY = "LOCAL_ONLY";
  public static final String DELIVER_PR = "DELIVER_PR";
  public static final String SCHEMA = "DevFlowDelivery/v1";
  public static final String CREDENTIALS_NOT_CONFIGURED = "DELIVERY_CREDENTIALS_NOT_CONFIGURED";
  private static final String SLUG = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+";
  private static final long GIT_TIMEOUT_SECONDS = 600L;
  private static final int MAX_DETAIL_CHARS = 2048;

  /** Where the delivery evidence comes from. */
  public enum Source {
    /** The deliver step of a running flow: verification just passed, finalize has not run. */
    FLOW_STEP,
    /** An operator request against an execution that already completed. */
    COMPLETED_EXECUTION
  }

  /**
   * The verified candidate as persisted by the flow. {@code result} and
   * {@code executionStatus} are required for {@link Source#COMPLETED_EXECUTION}.
   */
  public record Evidence(Source source, String executionId, String executionStatus, JsonNode task,
                         JsonNode candidate, String patch, JsonNode verification, JsonNode result) {
  }

  private final DeliveryGitHub github;
  private final String token;
  private final String forkOwner;
  private final Function<String, String> remoteUrl;
  private final String authorName;
  private final String authorEmail;
  private final JsonMapper json = JsonMapper.builder().build();

  public TrustedDeliveryService(DeliveryGitHub github, String token, String forkOwner,
                                String authorName, String authorEmail) {
    this(github, token, forkOwner, authorName, authorEmail,
        slug -> "https://github.com/" + slug + ".git");
  }

  TrustedDeliveryService(DeliveryGitHub github, String token, String forkOwner, String authorName,
                         String authorEmail, Function<String, String> remoteUrl) {
    this.github = github;
    this.token = token == null ? "" : token.trim();
    this.forkOwner = forkOwner == null ? "" : forkOwner.trim();
    this.authorName = authorName;
    this.authorEmail = authorEmail;
    this.remoteUrl = remoteUrl;
  }

  /** The requested delivery mode of an effective task payload. */
  public static String deliveryMode(JsonNode task) {
    String mode = task.path("resolvedIntent").path("task").path("deliveryMode").asString("");
    if (mode.isBlank()) {
      mode = task.path("deliveryMode").asString("");
    }
    return mode.isBlank() ? LOCAL_ONLY : mode;
  }

  /** Deterministic branch for one candidate identity: same candidate, same branch. */
  public static String branchName(String taskId, String patchSha256) {
    String safeTask = taskId == null ? "" : taskId.replaceAll("[^A-Za-z0-9._-]", "-");
    if (safeTask.isBlank() || safeTask.startsWith(".") || safeTask.startsWith("-")) {
      safeTask = "task";
    }
    return "factory/" + safeTask + "-" + patchSha256.substring(0, 12).toLowerCase(Locale.ROOT);
  }

  /**
   * Delivers the verified candidate or refuses. Serialized so two concurrent
   * requests for the same candidate cannot race each other into two pull
   * requests. Never throws for delivery faults: the returned delivery record
   * carries DELIVERED, REFUSED (nothing was mutated) or FAILED.
   */
  public synchronized ObjectNode deliver(Evidence evidence) {
    ObjectNode record = json.createObjectNode();
    record.put("schema", SCHEMA);
    record.put("mode", DELIVER_PR);
    record.put("source", evidence.source().name());
    record.put("executionId", evidence.executionId());
    record.put("taskId", evidence.task() == null ? "" : evidence.task().path("taskId").asString(""));
    record.put("requestedAt", Instant.now().toString());
    Refusal refusal = gate(evidence);
    JsonNode candidate = evidence.candidate();
    if (candidate != null) {
      record.putObject("candidate")
          .put("base", candidate.path("base").asString(""))
          .put("candidateTree", candidate.path("candidateTree").asString(""))
          .put("patchSha256", candidate.path("patchSha256").asString(""));
    }
    if (refusal != null) {
      return refused(record, refusal);
    }
    if (token.isBlank()) {
      return refused(record, new Refusal(CREDENTIALS_NOT_CONFIGURED,
          "no delivery-scope GitHub credential is configured for the Factory process"));
    }
    JsonNode task = evidence.task();
    String authoritative = task.path("resolvedIntent").path("repository").path("canonicalSlug").asString();
    String base = task.path("baseRevision").asString().toLowerCase(Locale.ROOT);
    String tree = candidate.path("candidateTree").asString();
    String patchSha = candidate.path("patchSha256").asString();
    String name = authoritative.substring(authoritative.indexOf('/') + 1);
    String target = forkOwner.isBlank() ? authoritative : forkOwner + "/" + name;
    String branch = branchName(task.path("taskId").asString(""), patchSha);
    record.put("repository", authoritative);
    record.put("targetRepository", target);
    record.put("baseRevision", base);
    record.put("branch", branch);
    record.set("verification", verificationSummary(evidence.verification(), task));
    Path workDir = null;
    try {
      DeliveryGitHub.RepositoryInfo targetInfo = github.repository(target);
      boolean allowed = (target.equalsIgnoreCase(authoritative)
              && authoritative.equalsIgnoreCase(targetInfo.fullName()))
          || (targetInfo.fork() && target.equalsIgnoreCase(targetInfo.fullName())
              && (authoritative.equalsIgnoreCase(targetInfo.parentFullName())
                  || authoritative.equalsIgnoreCase(targetInfo.sourceFullName())));
      if (!allowed) {
        return refused(record, new Refusal("TARGET_REPOSITORY_NOT_ALLOWED", "delivery target " + target
            + " is neither the authoritative repository " + authoritative + " nor a fork of it"));
      }
      String pullBase = targetInfo.defaultBranch();
      record.put("pullRequestBase", pullBase);
      // The pull request diff equals the candidate only when the PR base branch
      // contains the exact base revision (their merge base is then the base).
      if (pullBase.isBlank() || !github.branchContains(target, pullBase, base)) {
        return refused(record, new Refusal("BASE_NOT_IN_PULL_REQUEST_BASE", "base revision " + base
            + " is not contained in " + target + ":" + pullBase + "; the pull request diff would not "
            + "equal the verified candidate"));
      }

      workDir = Files.createTempDirectory("factory-delivery-");
      Path repo = Files.createDirectory(workDir.resolve("repo"));
      Path patchFile = workDir.resolve("candidate.patch");
      Files.writeString(patchFile, evidence.patch(), StandardCharsets.UTF_8);
      git(repo, Map.of(), "init", "--quiet");
      git(repo, Map.of(), "fetch", "--quiet", "--depth", "1", remoteUrl.apply(authoritative), base);
      git(repo, Map.of(), "checkout", "--quiet", "--detach", "FETCH_HEAD");
      String head = git(repo, Map.of(), "rev-parse", "HEAD").trim();
      if (!base.equalsIgnoreCase(head)) {
        return refused(record, new Refusal("BASE_MISMATCH", "trusted checkout resolved " + head
            + " instead of the authoritative base " + base));
      }
      git(repo, Map.of(), "apply", "--binary", patchFile.toString());
      git(repo, Map.of(), "add", "-A");
      String reproducedTree = git(repo, Map.of(), "write-tree").trim();
      record.put("reproducedTree", reproducedTree);
      if (!tree.equals(reproducedTree)) {
        return refused(record, new Refusal("CANDIDATE_IDENTITY_MISMATCH", "trusted reproduction produced tree "
            + reproducedTree + " but the verified candidate tree is " + tree));
      }
      git(repo, Map.of("GIT_AUTHOR_NAME", authorName, "GIT_AUTHOR_EMAIL", authorEmail,
              "GIT_COMMITTER_NAME", authorName, "GIT_COMMITTER_EMAIL", authorEmail),
          "commit", "--quiet", "--no-verify", "--no-gpg-sign", "-m", commitMessage(task, candidate, evidence));
      String commit = git(repo, Map.of(), "rev-parse", "HEAD").trim();
      String commitTree = git(repo, Map.of(), "rev-parse", "HEAD^{tree}").trim();
      String parent = git(repo, Map.of(), "rev-parse", "HEAD^").trim();
      if (!tree.equals(commitTree) || !base.equalsIgnoreCase(parent)) {
        return refused(record, new Refusal("CANDIDATE_IDENTITY_MISMATCH", "delivery commit " + commit
            + " has tree " + commitTree + " and parent " + parent + " instead of " + tree + " on " + base));
      }

      Optional<DeliveryGitHub.RemoteCommit> existing = github.branchHead(target, branch);
      boolean branchReused = false;
      if (existing.isPresent()) {
        DeliveryGitHub.RemoteCommit remote = existing.get();
        if (!sameCandidate(remote, tree, base)) {
          return refused(record, new Refusal("BRANCH_CONFLICT", "branch " + target + ":" + branch
              + " already exists at " + remote.sha() + " with different content"));
        }
        commit = remote.sha();
        branchReused = true;
      } else {
        git(repo, pushCredential(), "push", "--quiet", remoteUrl.apply(target),
            "HEAD:refs/heads/" + branch);
        DeliveryGitHub.RemoteCommit pushed = github.branchHead(target, branch).orElse(null);
        if (pushed == null || !commit.equals(pushed.sha()) || !sameCandidate(pushed, tree, base)) {
          return failed(record, "REMOTE_BRANCH_MISMATCH", "after push " + target + ":" + branch
              + " does not point at the verified delivery commit " + commit);
        }
      }
      String deliveredCommit = commit;
      record.put("commitSha", deliveredCommit);
      record.put("branchReused", branchReused);

      String owner = target.substring(0, target.indexOf('/'));
      Optional<DeliveryGitHub.PullRequest> found = github.findPullRequest(target, owner, branch);
      DeliveryGitHub.PullRequest pull = found.orElseGet(() -> github.createPullRequest(target, branch,
          pullBase, pullRequestTitle(task), pullRequestBody(task, candidate, evidence, deliveredCommit)));
      record.putObject("pullRequest")
          .put("number", pull.number())
          .put("url", pull.url())
          .put("state", pull.state())
          .put("reused", found.isPresent());
      record.put("status", "DELIVERED");
      record.put("deliveredAt", Instant.now().toString());
      return record;
    } catch (RuntimeException | IOException error) {
      return failed(record, "DELIVERY_ERROR", error.getMessage());
    } finally {
      deleteTree(workDir);
    }
  }

  /**
   * Pure pre-mutation gate: the execution succeeded, verification independently
   * passed for exactly this candidate, and repository/base are the authoritative
   * values. Returns null when delivery may proceed.
   */
  static Refusal gate(Evidence evidence) {
    JsonNode task = evidence.task();
    JsonNode candidate = evidence.candidate();
    JsonNode verification = evidence.verification();
    if (evidence.source() == Source.COMPLETED_EXECUTION) {
      if (!"COMPLETED".equals(evidence.executionStatus())) {
        return new Refusal("EXECUTION_NOT_COMPLETED", "execution status is " + evidence.executionStatus());
      }
      String outcome = evidence.result() == null ? "" : evidence.result().path("outcome").asString("");
      if (!"SUCCESS".equals(outcome)) {
        return new Refusal("OUTCOME_NOT_SUCCESS", "execution outcome is "
            + (outcome.isBlank() ? "missing" : outcome) + "; only SUCCESS is deliverable");
      }
    }
    if (task == null || candidate == null || verification == null || evidence.patch() == null) {
      return new Refusal("EVIDENCE_MISSING", "task, candidate, candidate patch and verification are required");
    }
    if (!"PASS".equals(verification.path("status").asString(""))) {
      return new Refusal("VERIFICATION_NOT_PASSED", "verification status is "
          + verification.path("status").asString("missing"));
    }
    if (!verification.path("independent").asBoolean(false)) {
      return new Refusal("VERIFICATION_NOT_INDEPENDENT", "verification was not an independent fresh check");
    }
    if (!verification.path("candidateIdentityMatches").asBoolean(false)
        || !verification.path("candidateTreeMatches").asBoolean(false)) {
      return new Refusal("CANDIDATE_IDENTITY_MISMATCH", "verification did not prove the candidate identity");
    }
    if (!"READY".equals(candidate.path("status").asString("")) || evidence.patch().isBlank()) {
      return new Refusal("CANDIDATE_NOT_READY", "no frozen non-empty candidate");
    }
    String patchSha = candidate.path("patchSha256").asString("");
    if (!patchSha.matches("[0-9a-f]{64}") || !patchSha.equals(ArtifactStore.sha256(evidence.patch()))) {
      return new Refusal("CANDIDATE_IDENTITY_MISMATCH", "candidate patch bytes do not hash to the frozen "
          + "patchSha256");
    }
    String tree = candidate.path("candidateTree").asString("");
    if (!tree.matches("[0-9a-f]{40}") || !tree.equals(verification.path("candidateTree").asString(""))) {
      return new Refusal("CANDIDATE_IDENTITY_MISMATCH", "verified tree "
          + verification.path("candidateTree").asString("missing") + " is not the candidate tree " + tree);
    }
    String base = task.path("baseRevision").asString("");
    JsonNode repository = task.path("resolvedIntent").path("repository");
    String exact = repository.path("exactRevision").asString(base);
    if (!base.matches("[0-9a-fA-F]{40}") || !base.equalsIgnoreCase(candidate.path("base").asString(""))
        || !base.equalsIgnoreCase(verification.path("base").asString("")) || !base.equalsIgnoreCase(exact)) {
      return new Refusal("BASE_MISMATCH", "task, candidate and verification do not share one authoritative "
          + "base revision");
    }
    String slug = repository.path("canonicalSlug").asString("");
    String repoUrl = task.path("repoUrl").asString("");
    if (!slug.matches(SLUG) || !"RESOLVED".equals(repository.path("status").asString("RESOLVED"))
        || (!repoUrl.isBlank() && !repoUrl.equalsIgnoreCase("https://github.com/" + slug + ".git"))) {
      return new Refusal("REPOSITORY_MISMATCH", "the resolved repository '" + slug
          + "' is not one authoritative GitHub repository matching the task source");
    }
    return null;
  }

  private static boolean sameCandidate(DeliveryGitHub.RemoteCommit commit, String tree, String base) {
    return tree.equals(commit.tree()) && commit.parents().size() == 1
        && base.equalsIgnoreCase(commit.parents().get(0));
  }

  /**
   * The only credential path: an HTTP header scoped to github.com, passed to the
   * push child process through its environment (never argv, never a remote URL,
   * never a sandbox). Operator credential helpers are disabled.
   */
  private Map<String, String> pushCredential() {
    String basic = Base64.getEncoder().encodeToString(("x-access-token:" + token)
        .getBytes(StandardCharsets.UTF_8));
    return Map.of("GIT_CONFIG_COUNT", "1",
        "GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader",
        "GIT_CONFIG_VALUE_0", "AUTHORIZATION: basic " + basic);
  }

  private String git(Path directory, Map<String, String> extraEnv, String... args)
      throws IOException {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    Path output = Files.createTempFile("factory-delivery-git-", ".log");
    try {
      ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
          .redirectErrorStream(true).redirectOutput(output.toFile());
      Map<String, String> env = builder.environment();
      env.keySet().removeIf(key -> key.startsWith("GIT_"));
      env.put("GIT_TERMINAL_PROMPT", "0");
      env.put("GIT_CONFIG_NOSYSTEM", "1");
      env.put("GIT_CONFIG_GLOBAL", "/dev/null");
      env.putAll(extraEnv);
      Process process = builder.start();
      boolean finished;
      try {
        finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new IllegalStateException("git " + args[0] + " interrupted", e);
      }
      if (!finished) {
        process.destroyForcibly();
        throw new IllegalStateException("git " + args[0] + " timed out");
      }
      String text = Files.readString(output, StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new IllegalStateException("git " + args[0] + " failed (exit " + process.exitValue() + "): "
            + text);
      }
      return text;
    } finally {
      Files.deleteIfExists(output);
    }
  }

  private ObjectNode verificationSummary(JsonNode verification, JsonNode task) {
    ObjectNode summary = json.createObjectNode();
    summary.put("status", verification.path("status").asString(""));
    summary.put("independent", verification.path("independent").asBoolean(false));
    summary.put("candidateTree", verification.path("candidateTree").asString(""));
    summary.put("planId", verification.path("planId").asString(
        task.path("resolvedIntent").path("verificationPlan").path("id").asString("")));
    summary.set("checks", verification.path("checks").isArray()
        ? verification.path("checks").deepCopy() : json.createArrayNode());
    return summary;
  }

  private static String pullRequestTitle(JsonNode task) {
    String goal = firstSentence(task.path("goal").asString(""));
    String title = task.path("taskId").asString("") + ": " + goal;
    return title.length() <= 120 ? title : title.substring(0, 117) + "...";
  }

  private static String commitMessage(JsonNode task, JsonNode candidate, Evidence evidence) {
    return pullRequestTitle(task) + "\n\n"
        + "Delivered by Factory Developer Flow from an independently verified candidate.\n\n"
        + "Factory-Execution: " + evidence.executionId() + "\n"
        + "Factory-Base: " + task.path("baseRevision").asString("") + "\n"
        + "Factory-Candidate-Tree: " + candidate.path("candidateTree").asString("") + "\n"
        + "Factory-Candidate-Patch-Sha256: " + candidate.path("patchSha256").asString("") + "\n";
  }

  private static String pullRequestBody(JsonNode task, JsonNode candidate, Evidence evidence,
                                        String commit) {
    StringBuilder body = new StringBuilder();
    body.append("## Task\n\n").append(task.path("taskId").asString("")).append(": ")
        .append(task.path("goal").asString("")).append("\n\n");
    JsonNode criteria = task.path("acceptanceCriteria");
    if (criteria.isArray() && !criteria.isEmpty()) {
      criteria.forEach(item -> body.append("- **").append(item.path("id").asString(""))
          .append(":** ").append(item.path("text").asString("")).append('\n'));
      body.append('\n');
    }
    JsonNode verification = evidence.verification();
    body.append("## Verification\n\n")
        .append("Independent fresh-checkout verification: **")
        .append(verification.path("status").asString("")).append("** (plan `")
        .append(verification.path("planId").asString(
            task.path("resolvedIntent").path("verificationPlan").path("id").asString("")))
        .append("`)\n\n");
    JsonNode checks = verification.path("checks");
    if (checks.isArray()) {
      checks.forEach(check -> body.append("- `").append(check.path("command").asString(""))
          .append("` exit ").append(check.path("exitCode").asInt(-1)).append('\n'));
      body.append('\n');
    }
    body.append("## Candidate identity\n\n")
        .append("- Repository: ").append(task.path("resolvedIntent").path("repository")
            .path("canonicalSlug").asString("")).append('\n')
        .append("- Base: `").append(task.path("baseRevision").asString("")).append("`\n")
        .append("- Candidate tree: `").append(candidate.path("candidateTree").asString("")).append("`\n")
        .append("- Patch SHA-256: `").append(candidate.path("patchSha256").asString("")).append("`\n")
        .append("- Commit: `").append(commit).append("`\n")
        .append("- Factory execution: `").append(evidence.executionId()).append("`\n\n")
        .append("Generated by Factory Developer Flow (coding runtime + independent verification); "
            + "Factory reproduced the verified candidate from the exact base before pushing. "
            + "Eligible for human review; not auto-merged.\n");
    return body.toString();
  }

  private static String firstSentence(String goal) {
    int end = goal.indexOf(". ");
    return (end > 0 ? goal.substring(0, end) : goal).trim();
  }

  private ObjectNode refused(ObjectNode record, Refusal refusal) {
    record.put("status", "REFUSED");
    record.put("reason", refusal.reason());
    record.put("detail", sanitize(refusal.detail()));
    record.put("remoteMutated", false);
    return record;
  }

  private ObjectNode failed(ObjectNode record, String reason, String detail) {
    record.put("status", "FAILED");
    record.put("reason", reason);
    record.put("detail", sanitize(detail));
    return record;
  }

  private String sanitize(String detail) {
    String value = detail == null ? "" : detail;
    if (!token.isBlank()) {
      value = value.replace(token, "***");
    }
    return value.length() <= MAX_DETAIL_CHARS ? value : value.substring(0, MAX_DETAIL_CHARS) + "...";
  }

  private static void deleteTree(Path root) {
    if (root == null) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException ignored) {
      // Temporary trusted checkout; the OS temp directory reclaims leftovers.
    }
  }

  record Refusal(String reason, String detail) {
  }
}
