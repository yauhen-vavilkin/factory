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
              List.of(), List.of("Provide one approved canonical repository slug")), List.of());
    }
    if (candidates.size() != 1) {
      return blocked(task, "REPOSITORY_EVIDENCE_CONFLICT",
          "Repository evidence identifies more than one repository",
          new ResolvedIntent.RepositoryDecision("BLOCKED", null, null, task.baseRef(), null,
              candidates.stream().sorted().toList(),
              List.of("Clarify the single primary implementation repository")), List.of());
    }
    String slug = candidates.iterator().next();
    String exactSha = task.baseRevision() == null
        ? access.resolveBranch(slug, task.baseRef())
        : access.verifyCommit(slug, task.baseRevision());
    if (task.baseRevision() != null && !exactSha.equalsIgnoreCase(task.baseRevision())) {
      throw new RepositorySecurityException("verified commit does not match requested baseRevision");
    }
    ResolvedIntent.RepositoryDecision repository = new ResolvedIntent.RepositoryDecision(
        "RESOLVED", slug, repositories.origin(slug), task.baseRef(), exactSha,
        List.of(slug), List.of());

    List<ResolvedIntent.Ambiguity> ambiguities = materialAmbiguities(task);
    if (!ambiguities.isEmpty()) {
      return blocked(task, "MATERIAL_AMBIGUITY", "Task has unresolved material alternatives",
          repository, ambiguities);
    }

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
      ResolvedIntent draft = new ResolvedIntent(SCHEMA, "RESOLVED", null,
          "Repository, revision, profile and verification plan resolved", task, semanticHash,
          admissionKey, repository, evidence, profile, plan, List.copyOf(unknowns), List.of(), false, null);
      return withHash(draft);
    } catch (UnsupportedProfileException e) {
      String semanticHash = semanticHash(task, repository, task.profileId(), task.verificationPlanId());
      ResolvedIntent draft = new ResolvedIntent(SCHEMA, "BLOCKED", e.code(), e.getMessage(), task,
          semanticHash, admissionKey(semanticHash, task.runKey()), repository, evidence, null, null,
          evidence.unknowns(), List.of(), false, null);
      return withHash(draft);
    }
  }

  private ResolvedIntent blocked(TaskRequest task, String code, String message,
                                 ResolvedIntent.RepositoryDecision repository,
                                 List<ResolvedIntent.Ambiguity> ambiguities) {
    String semanticHash = semanticHash(task, repository, task.profileId(), task.verificationPlanId());
    return withHash(new ResolvedIntent(SCHEMA, "BLOCKED", code, message, task, semanticHash,
        admissionKey(semanticHash, task.runKey()), repository, null, null, null,
        List.of(), ambiguities, false, null));
  }

  private ResolvedIntent withHash(ResolvedIntent intent) {
    ObjectNode node = json.valueToTree(intent);
    node.remove("intentHash");
    String hash = CanonicalJson.sha256(node);
    return new ResolvedIntent(intent.schema(), intent.status(), intent.code(), intent.message(),
        intent.task(), intent.semanticTaskHash(), intent.admissionKey(), intent.repository(),
        intent.profileEvidence(), intent.profile(), intent.verificationPlan(), intent.unknowns(),
        intent.ambiguities(), intent.executionReady(), hash);
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
    return CanonicalJson.sha256(node);
  }

  private static String admissionKey(String semanticHash, String runKey) {
    return "file.inbox:" + CanonicalJson.sha256((semanticHash + "\n" + runKey)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static List<ResolvedIntent.Ambiguity> materialAmbiguities(TaskRequest task) {
    if (!"MGRENTITLE-172".equalsIgnoreCase(task.source().id())) {
      return List.of();
    }
    String combined = task.goal() + " " + task.acceptanceCriteria().stream()
        .map(TaskRequest.AcceptanceCriterion::text).reduce("", (a, b) -> a + " " + b);
    if (!combined.toLowerCase(Locale.ROOT).contains("maxitems")) {
      return List.of();
    }
    return List.of(new ResolvedIntent.Ambiguity("MGRENTITLE-172-maxItems",
        "What does maxItems limit?",
        List.of("set maxItems to 100", "remove maxItems for an unbounded array"),
        "MGRENTITLE-172 original permitted alternatives"));
  }

  private static void validateTaskControls(TaskRequest task) {
    if (task.runKey() == null || task.runKey().isBlank() || task.runKey().length() > 128
        || !task.runKey().matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("runKey must match [A-Za-z0-9._-]+ and be at most 128 characters");
    }
    if (!"LOCAL_ONLY".equals(task.deliveryMode())) {
      throw new IllegalArgumentException("only LOCAL_ONLY deliveryMode is accepted in M1");
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
