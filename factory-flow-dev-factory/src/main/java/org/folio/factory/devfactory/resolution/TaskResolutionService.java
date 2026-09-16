package org.folio.factory.devfactory.resolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.folio.factory.devfactory.contract.CanonicalJson;
import org.folio.factory.devfactory.contract.ResolvedIntent;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.profile.ExecutionProfile;
import org.folio.factory.devfactory.profile.ProfileEvidence;
import org.folio.factory.devfactory.profile.StaticProfileDetector;
import org.folio.factory.devfactory.profile.TrustedProfileCatalog;
import org.folio.factory.devfactory.profile.UnsupportedProfileException;
import org.folio.factory.devfactory.profile.VerificationPlan;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Pure decision pipeline apart from bounded read-only repository lookups. */
public final class TaskResolutionService {
  private static final String SCHEMA = "ResolvedIntent/v1";
  private final RepositoryCatalog repositories;
  private final RepositoryAccess access;
  private final StaticProfileDetector detector;
  private final TrustedProfileCatalog profiles;
  private final JsonMapper json = JsonMapper.builder().build();

  public TaskResolutionService(RepositoryCatalog repositories, RepositoryAccess access,
                               TrustedProfileCatalog profiles) {
    this.repositories = repositories;
    this.access = access;
    this.detector = new StaticProfileDetector(access);
    this.profiles = profiles;
  }

  public ResolvedIntent resolve(TaskRequest task) {
    validateTaskControls(task);
    Set<String> candidates = repositories.candidates(task.repository(), task.source().project(),
        task.source().component());
    if (candidates.isEmpty()) {
      return blocked(task, "REPOSITORY_NOT_RESOLVED", "No approved repository evidence matched",
          new ResolvedIntent.RepositoryDecision("BLOCKED", null, null, task.baseRef(), null,
              List.of(), List.of("Provide one approved canonical repository slug")));
    }
    if (candidates.size() != 1) {
      List<String> sorted = candidates.stream().sorted().toList();
      ResolvedIntent.RepositoryDecision repository = new ResolvedIntent.RepositoryDecision(
          ResolvedIntent.NEEDS_DECISION, null, null, task.baseRef(), null, sorted,
          List.of("Select the single primary implementation repository"));
      if (!task.decisions().isEmpty()) {
        // One execution pauses at most once: a repository conflict plus a declared
        // decision would need a nested decision, which this flow does not support.
        return blocked(task, "MULTIPLE_DECISIONS_UNSUPPORTED",
            "Repository evidence conflicts and the task also declares an open decision", repository);
      }
      return needsDecision(task, repository, null, null, null, repositoryDecision(task, sorted));
    }
    return resolveRepository(task, candidates.iterator().next());
  }

  /**
   * Resolution after the repository is known: either the single deterministic
   * candidate or the repository a human selected for a REPOSITORY_SELECTION
   * decision. The selection must be approved and belong to the original
   * candidate evidence; it never widens the catalog.
   */
  public ResolvedIntent resolveSelectedRepository(TaskRequest task, String selectedSlug) {
    validateTaskControls(task);
    Set<String> candidates = repositories.candidates(task.repository(), task.source().project(),
        task.source().component());
    if (!candidates.contains(selectedSlug)) {
      throw new RepositorySecurityException("selected repository is not one of the evidence candidates: "
          + selectedSlug);
    }
    TaskRequest selected = new TaskRequest(task.schemaVersion(), task.source(), selectedSlug,
        task.baseRevision(), task.baseRef(), task.profileId(), task.verificationPlanId(), task.runKey(),
        task.deliveryMode(), task.metadata(), task.goal(), task.acceptanceCriteria(), task.constraints(),
        task.notes(), task.rawTaskText(), task.legacyAdapted(), task.decisions());
    return resolveRepository(selected, selectedSlug);
  }

  private ResolvedIntent resolveRepository(TaskRequest task, String slug) {
    // A task that names neither revision nor ref (a Jira issue carries neither)
    // starts from the resolved repository's own default branch, never a guessed name.
    String ref = task.baseRevision() == null && task.baseRef() == null
        ? access.defaultBranch(slug) : task.baseRef();
    String exactSha = task.baseRevision() == null
        ? access.resolveBranch(slug, ref)
        : access.verifyCommit(slug, task.baseRevision());
    if (task.baseRevision() != null && !exactSha.equalsIgnoreCase(task.baseRevision())) {
      throw new RepositorySecurityException("verified commit does not match requested baseRevision");
    }
    ResolvedIntent.RepositoryDecision repository = new ResolvedIntent.RepositoryDecision(
        "RESOLVED", slug, repositories.origin(slug), ref, exactSha,
        List.of(slug), List.of());

    ProfileEvidence evidence = detector.detect(slug, exactSha);
    try {
      ExecutionProfile profile = profiles.resolveProfile(evidence, task.profileId());
      VerificationPlan plan = profiles.resolvePlan(task.verificationPlanId());
      String semanticHash = semanticHash(task, repository, profile.id(), plan.id());
      String admissionKey = "file.inbox:" + CanonicalJson.sha256(
          (semanticHash + "\n" + task.runKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      List<String> unknowns = new ArrayList<>(evidence.unknowns());
      if (profile.imageDigest() == null) {
        unknowns.add("IMAGE_DIGEST_PENDING_PREPARATION");
      }
      unknowns.add("DEPENDENCY_SEED_PENDING_PREPARATION");
      unknowns.add("BASELINE_PENDING_PREPARATION");
      if (!task.decisions().isEmpty()) {
        // Everything deterministic is resolved first, so the human sees the
        // exact repository, revision and verification plan the answer applies to.
        return needsDecision(task, repository, evidence, profile, plan,
            declaredDecision(task.decisions().getFirst(), repository, profile, plan));
      }
      ResolvedIntent draft = new ResolvedIntent(SCHEMA, "RESOLVED", null,
          "Repository, revision, profile and verification plan resolved", task, semanticHash,
          admissionKey, repository, evidence, profile, plan, List.copyOf(unknowns), null, false, null);
      return withHash(draft);
    } catch (UnsupportedProfileException e) {
      String semanticHash = semanticHash(task, repository, task.profileId(), task.verificationPlanId());
      ResolvedIntent draft = new ResolvedIntent(SCHEMA, "BLOCKED", e.code(), e.getMessage(), task,
          semanticHash, admissionKey(semanticHash, task.runKey()), repository, evidence, null, null,
          evidence.unknowns(), null, false, null);
      return withHash(draft);
    }
  }

  private ResolvedIntent blocked(TaskRequest task, String code, String message,
                                 ResolvedIntent.RepositoryDecision repository) {
    String semanticHash = semanticHash(task, repository, task.profileId(), task.verificationPlanId());
    return withHash(new ResolvedIntent(SCHEMA, "BLOCKED", code, message, task, semanticHash,
        admissionKey(semanticHash, task.runKey()), repository, null, null, null,
        List.of(), null, false, null));
  }

  private ResolvedIntent needsDecision(TaskRequest task, ResolvedIntent.RepositoryDecision repository,
                                       ProfileEvidence evidence, ExecutionProfile profile,
                                       VerificationPlan plan, ResolvedIntent.Decision decision) {
    String semanticHash = semanticHash(task, repository, profile == null ? task.profileId() : profile.id(),
        plan == null ? task.verificationPlanId() : plan.id());
    return withHash(new ResolvedIntent(SCHEMA, ResolvedIntent.NEEDS_DECISION, decision.category(),
        "Task has a material open decision that must not be guessed", task, semanticHash,
        admissionKey(semanticHash, task.runKey()), repository, evidence, profile, plan,
        List.of("HUMAN_DECISION_PENDING:" + decision.id()), decision, false, null));
  }

  /** General rule: independent evidence names more than one approved repository. */
  private ResolvedIntent.Decision repositoryDecision(TaskRequest task, List<String> candidates) {
    List<ResolvedIntent.Fact> facts = new ArrayList<>();
    repositories.canonicalizeExplicit(task.repository()).ifPresent(slug -> facts.add(new ResolvedIntent.Fact(
        "The task names repository '" + task.repository() + "' (" + slug + ")", "task.repository")));
    if (task.source().project() != null) {
      repositories.candidates(null, task.source().project(), null).forEach(slug -> facts.add(
          new ResolvedIntent.Fact("Source project " + task.source().project() + " maps to " + slug,
              "repository catalog: project mapping")));
    }
    if (task.source().component() != null) {
      repositories.candidates(null, null, task.source().component()).forEach(slug -> facts.add(
          new ResolvedIntent.Fact("Source component " + task.source().component() + " maps to " + slug,
              "repository catalog: component alias")));
    }
    List<TaskRequest.Option> options = candidates.stream().map(slug -> new TaskRequest.Option(slug,
        "Implement in " + slug,
        "The base revision, execution profile and verification plan are resolved for " + slug
            + "; changes are made only there")).toList();
    return new ResolvedIntent.Decision("repository-selection", "REPOSITORY_SELECTION",
        "Which repository is the implementation target for " + task.source().id() + "?",
        "Coding, the verification baseline and the candidate all bind to one repository and revision; "
            + "a wrong guess produces a change in a repository the task does not own.",
        options, null, null, List.copyOf(facts), "selectedOptionId: one of " + candidates,
        "deterministic rule: repository evidence conflict");
  }

  private ResolvedIntent.Decision declaredDecision(TaskRequest.DeclaredDecision declared,
                                                   ResolvedIntent.RepositoryDecision repository,
                                                   ExecutionProfile profile, VerificationPlan plan) {
    List<ResolvedIntent.Fact> facts = new ArrayList<>();
    facts.add(new ResolvedIntent.Fact("Implementation repository " + repository.canonicalSlug()
        + " at exact revision " + repository.exactRevision(), "deterministic resolution"));
    facts.add(new ResolvedIntent.Fact("Execution profile " + profile.id() + "; verification plan " + plan.id()
        + " runs " + plan.checks().stream().filter(VerificationPlan.Check::required)
        .map(check -> String.join(" ", check.argv())).toList(), "trusted profile catalog"));
    for (TaskRequest.Evidence item : declared.evidence()) {
      facts.add(repositoryEvidence(repository, item));
    }
    return new ResolvedIntent.Decision(declared.id(), declared.category(), declared.question(),
        declared.whyItMatters(), declared.options(), declared.recommendedOptionId(),
        declared.recommendationRationale(), List.copyOf(facts),
        "selectedOptionId: one of " + declared.options().stream().map(TaskRequest.Option::id).toList()
            + " (or a short freeText answer when no option fits)",
        "task-declared decision");
  }

  /** Bounded read-only lookup: matching lines of one file at the exact revision. */
  private ResolvedIntent.Fact repositoryEvidence(ResolvedIntent.RepositoryDecision repository,
                                                 TaskRequest.Evidence item) {
    String source = repository.canonicalSlug() + "@" + repository.exactRevision() + ":" + item.path();
    java.util.Optional<byte[]> content = access.readFile(repository.canonicalSlug(),
        repository.exactRevision(), item.path(), 256 * 1024);
    if (content.isEmpty()) {
      return new ResolvedIntent.Fact("File " + item.path() + " does not exist at the base revision", source);
    }
    List<String> matches = new ArrayList<>();
    String[] lines = new String(content.get(), java.nio.charset.StandardCharsets.UTF_8).split("\n", -1);
    for (int index = 0; index < lines.length && matches.size() < 8; index++) {
      String line = lines[index];
      if (item.terms().stream().anyMatch(term -> line.toLowerCase(Locale.ROOT)
          .contains(term.toLowerCase(Locale.ROOT)))) {
        String trimmed = line.strip();
        matches.add("L" + (index + 1) + ": " + (trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed));
      }
    }
    return new ResolvedIntent.Fact(matches.isEmpty()
        ? "File " + item.path() + " contains none of " + item.terms()
        : "File " + item.path() + " lines matching " + item.terms() + ": " + String.join(" | ", matches), source);
  }

  private ResolvedIntent withHash(ResolvedIntent intent) {
    ObjectNode node = json.valueToTree(intent);
    node.remove("intentHash");
    String hash = CanonicalJson.sha256(node);
    return new ResolvedIntent(intent.schema(), intent.status(), intent.code(), intent.message(),
        intent.task(), intent.semanticTaskHash(), intent.admissionKey(), intent.repository(),
        intent.profileEvidence(), intent.profile(), intent.verificationPlan(), intent.unknowns(),
        intent.decision(), intent.executionReady(), hash);
  }

  private String semanticHash(TaskRequest task, ResolvedIntent.RepositoryDecision repository,
                              String profileId, String planId) {
    ObjectNode node = json.createObjectNode();
    node.put("schemaVersion", task.schemaVersion());
    node.set("source", json.valueToTree(task.source()));
    if (repository != null && repository.canonicalSlug() != null) {
      node.put("repository", repository.canonicalSlug());
    } else {
      node.set("repositoryCandidates", json.valueToTree(repository == null ? List.of() : repository.candidates()));
    }
    putNullable(node, "baseRevision", task.baseRevision());
    putNullable(node, "baseRef", task.baseRef());
    putNullable(node, "profileId", profileId);
    putNullable(node, "verificationPlanId", planId);
    node.put("deliveryMode", task.deliveryMode());
    node.set("metadata", task.metadata());
    node.put("goal", task.goal());
    node.set("acceptanceCriteria", json.valueToTree(task.acceptanceCriteria()));
    node.set("constraints", task.constraints());
    putNullable(node, "notes", task.notes());
    if (!task.decisions().isEmpty()) {
      node.set("decisions", json.valueToTree(task.decisions()));
    }
    return CanonicalJson.sha256(node);
  }

  private static String admissionKey(String semanticHash, String runKey) {
    return "file.inbox:" + CanonicalJson.sha256((semanticHash + "\n" + runKey)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void validateTaskControls(TaskRequest task) {
    if (task.runKey() == null || task.runKey().isBlank() || task.runKey().length() > 128
        || !task.runKey().matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("runKey must match [A-Za-z0-9._-]+ and be at most 128 characters");
    }
    if (!"LOCAL_ONLY".equals(task.deliveryMode()) && !"DELIVER_PR".equals(task.deliveryMode())) {
      throw new IllegalArgumentException("deliveryMode must be LOCAL_ONLY or DELIVER_PR");
    }
  }

  private static void putNullable(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }
}
