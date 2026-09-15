package org.folio.factory.devfactory.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.folio.factory.devfactory.contract.ResolvedIntent;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.profile.TrustedProfileCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TaskResolutionServiceTest {
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final byte[] JAVA_POM = """
      <project><properties><java.version>21</java.version></properties>
      <dependencies><dependency><artifactId>quarkus-core</artifactId></dependency></dependencies></project>
      """.getBytes(StandardCharsets.UTF_8);
  private final JsonMapper json = JsonMapper.builder().build();
  private final FakeAccess access = new FakeAccess();
  private final TaskResolutionService resolver = new TaskResolutionService(
      new RepositoryCatalog(), access, new TrustedProfileCatalog());

  @Test
  void explicitSlugBranchResolvesOnceToExactShaAndTrustedProfiles() {
    ResolvedIntent result = resolver.resolve(task("MODSIDECAR-208",
        "folio-org/folio-module-sidecar", "MODSIDECAR", null, null, "master", null, null, "default"));

    assertThat(result.status()).isEqualTo("RESOLVED");
    assertThat(result.repository().canonicalSlug()).isEqualTo("folio-org/folio-module-sidecar");
    assertThat(result.repository().requestedRef()).isEqualTo("master");
    assertThat(result.repository().exactRevision()).isEqualTo(SHA);
    assertThat(result.profile().id()).isEqualTo(TrustedProfileCatalog.JAVA_MAVEN_21);
    assertThat(result.verificationPlan().id()).isEqualTo(TrustedProfileCatalog.JAVA_MAVEN_VERIFY);
    assertThat(result.profileEvidence().framework()).isEqualTo("Quarkus");
    assertThat(access.branchCalls).isEqualTo(1);
    assertThat(access.commitCalls).isZero();
    assertThat(result.executionReady()).isFalse();
    assertThat(result.unknowns()).contains("DOCKER_REQUIREMENT_UNKNOWN",
        "INHERITED_FRAMEWORK_VERSION_UNKNOWN", "IMAGE_DIGEST_PENDING_PREPARATION",
        "BASELINE_PENDING_PREPARATION");
  }

  @Test
  void exactShaIsVerifiedWithoutTreatingItAsBranch() {
    ResolvedIntent result = resolver.resolve(task("MODSIDECAR-208",
        "folio-org/folio-module-sidecar", null, null, SHA, null, null, null, "default"));
    assertThat(result.repository().requestedRef()).isNull();
    assertThat(result.repository().exactRevision()).isEqualTo(SHA);
    assertThat(access.branchCalls).isZero();
    assertThat(access.commitCalls).isEqualTo(1);
  }

  @Test
  void allFourTrustedProjectsResolve() {
    Map<String, String> mappings = Map.of(
        "MODSIDECAR", "folio-org/folio-module-sidecar",
        "MGRENTITLE", "folio-org/mgr-tenant-entitlements",
        "MODSCHED", "folio-org/mod-scheduler",
        "MODROLESKC", "folio-org/mod-roles-keycloak");
    mappings.forEach((project, slug) -> assertThat(resolver.resolve(task(project + "-1",
        null, project, null, SHA, null, null, null, "default")).repository().canonicalSlug())
        .isEqualTo(slug));
  }

  @Test
  void conflictingRepositoryEvidenceNeedsDecisionAndMissingEvidenceBlocks() {
    ResolvedIntent conflict = resolver.resolve(task("X-1", "folio-org/folio-module-sidecar",
        "MGRENTITLE", null, SHA, null, null, null, "default"));
    assertThat(conflict.status()).isEqualTo(ResolvedIntent.NEEDS_DECISION);
    assertThat(conflict.code()).isEqualTo("REPOSITORY_SELECTION");
    assertThat(conflict.repository().candidates()).containsExactlyInAnyOrder(
        "folio-org/folio-module-sidecar", "folio-org/mgr-tenant-entitlements");
    assertThat(conflict.decision().options()).extracting(TaskRequest.Option::id)
        .containsExactly("folio-org/folio-module-sidecar", "folio-org/mgr-tenant-entitlements");
    assertThat(conflict.decision().facts()).extracting(ResolvedIntent.Fact::statement)
        .anyMatch(fact -> fact.contains("MGRENTITLE maps to folio-org/mgr-tenant-entitlements"));
    assertThat(conflict.decision().recommendedOptionId()).isNull();
    assertThat(access.commitCalls).isZero();

    ResolvedIntent selected = resolver.resolveSelectedRepository(conflict.task(),
        "folio-org/mgr-tenant-entitlements");
    assertThat(selected.status()).isEqualTo("RESOLVED");
    assertThat(selected.repository().canonicalSlug()).isEqualTo("folio-org/mgr-tenant-entitlements");
    assertThat(selected.repository().exactRevision()).isEqualTo(SHA);
    assertThatThrownBy(() -> resolver.resolveSelectedRepository(conflict.task(), "folio-org/mod-scheduler"))
        .isInstanceOf(RepositorySecurityException.class);

    ResolvedIntent missing = resolver.resolve(task("X-1", null, null, "unknown-component",
        SHA, null, null, null, "default"));
    assertThat(missing.code()).isEqualTo("REPOSITORY_NOT_RESOLVED");
  }

  @Test
  void nodeAndUnknownJavaDoNotFallBackToMaven() {
    access.files.put("package.json", "{\"engines\":{\"node\":\"22\"}}".getBytes(StandardCharsets.UTF_8));
    ResolvedIntent node = resolver.resolve(task("MODSIDECAR-208", null, "MODSIDECAR", null,
        SHA, null, null, null, "default"));
    assertThat(node.status()).isEqualTo("BLOCKED");
    assertThat(node.code()).isEqualTo("UNSUPPORTED_PROFILE");

    access.files.clear();
    access.files.put("pom.xml", "<project/>".getBytes(StandardCharsets.UTF_8));
    ResolvedIntent unknown = resolver.resolve(task("MODSIDECAR-208", null, "MODSIDECAR", null,
        SHA, null, null, null, "default"));
    assertThat(unknown.code()).isEqualTo("JAVA_VERSION_UNKNOWN");
  }

  @Test
  void unknownTaskProfileAndPlanBlockExplicitly() {
    ResolvedIntent profile = resolver.resolve(task("MODSIDECAR-208", null, "MODSIDECAR", null,
        SHA, null, "root-shell", null, "default"));
    assertThat(profile.code()).isEqualTo("UNKNOWN_PROFILE");
    ResolvedIntent plan = resolver.resolve(task("MODSIDECAR-208", null, "MODSIDECAR", null,
        SHA, null, null, "jira-command", "default"));
    assertThat(plan.code()).isEqualTo("UNKNOWN_VERIFICATION_PLAN");
  }

  @Test
  void declaredDecisionPausesAfterDeterministicResolutionWithRepositoryFacts() {
    access.files.put("src/schema.json", "{\n  \"minItems\": 1,\n  \"maxItems\": 25\n}".getBytes(StandardCharsets.UTF_8));
    TaskRequest.DeclaredDecision declared = new TaskRequest.DeclaredDecision("limit", "PRODUCT_SEMANTICS",
        "Raise the limit or remove it?", "External API contract", List.of(
            new TaskRequest.Option("raise", "Raise to 100", "Bounded"),
            new TaskRequest.Option("remove", "Remove", "Unbounded")), null, null,
        List.of(new TaskRequest.Evidence("src/schema.json", List.of("maxItems")),
            new TaskRequest.Evidence("src/missing.json", List.of("maxItems"))));
    TaskRequest request = withDecisions(task("ANY-1", null, "MGRENTITLE", null, SHA, null, null, null,
        "default"), List.of(declared));

    ResolvedIntent result = resolver.resolve(request);

    assertThat(result.status()).isEqualTo(ResolvedIntent.NEEDS_DECISION);
    assertThat(result.code()).isEqualTo("PRODUCT_SEMANTICS");
    assertThat(result.repository().exactRevision()).isEqualTo(SHA);
    assertThat(result.verificationPlan()).isNotNull();
    assertThat(result.decision().options()).extracting(TaskRequest.Option::id).containsExactly("raise", "remove");
    assertThat(result.decision().facts()).extracting(ResolvedIntent.Fact::statement)
        .anyMatch(fact -> fact.contains("L3: \"maxItems\": 25"))
        .anyMatch(fact -> fact.contains("src/missing.json does not exist"));
    assertThat(result.semanticTaskHash()).isNotEqualTo(resolver.resolve(task("ANY-1", null, "MGRENTITLE",
        null, SHA, null, null, null, "default")).semanticTaskHash());
  }

  @Test
  void taskWithoutDeclaredDecisionNeverNeedsDecisionEvenIfTextMentionsAlternatives() {
    TaskRequest request = task("MGRENTITLE-172", null, "MGRENTITLE", null, SHA, null, null, null, "default");
    request = new TaskRequest(request.schemaVersion(), request.source(), request.repository(),
        request.baseRevision(), request.baseRef(), request.profileId(), request.verificationPlanId(),
        request.runKey(), request.deliveryMode(), request.metadata(), "Set maxItems to 100 (or remove it)",
        request.acceptanceCriteria(), request.constraints(), request.notes(), request.rawTaskText(), false);

    assertThat(resolver.resolve(request).status()).isEqualTo("RESOLVED");
  }

  @Test
  void semanticHashIgnoresMetadataKeyOrderAndRunKeyControlsIntentionalRerun() {
    TaskRequest first = task("MODSIDECAR-208", null, "MODSIDECAR", null, SHA, null,
        null, null, "run-1");
    var reversed = json.createObjectNode();
    reversed.put("b", 2).put("a", 1);
    var ordered = json.createObjectNode();
    ordered.put("a", 1).put("b", 2);
    first = withMetadata(first, ordered);
    TaskRequest second = withMetadata(task("MODSIDECAR-208", null, "MODSIDECAR", null, SHA,
        null, null, null, "run-1"), reversed);
    ResolvedIntent a = resolver.resolve(first);
    ResolvedIntent b = resolver.resolve(second);
    assertThat(a.semanticTaskHash()).isEqualTo(b.semanticTaskHash());
    assertThat(a.admissionKey()).isEqualTo(b.admissionKey());

    ResolvedIntent rerun = resolver.resolve(withMetadata(task("MODSIDECAR-208", null,
        "MODSIDECAR", null, SHA, null, null, null, "run-2"), ordered));
    assertThat(rerun.semanticTaskHash()).isEqualTo(a.semanticTaskHash());
    assertThat(rerun.admissionKey()).isNotEqualTo(a.admissionKey());
  }

  @Test
  void hostileOriginsPathsProtocolsAndCredentialsAreRejected() {
    RepositoryCatalog catalog = new RepositoryCatalog();
    for (String value : List.of("/tmp/repo", "file:///tmp/repo", "ssh://github.com/folio-org/mod-scheduler",
        "ext::sh -c evil", "https://user:pass@github.com/folio-org/mod-scheduler.git",
        "https://127.0.0.1/folio-org/mod-scheduler.git",
        "https://github.com/folio-org/not-approved.git")) {
      assertThatThrownBy(() -> catalog.canonicalizeExplicit(value))
          .isInstanceOf(RepositorySecurityException.class);
    }
  }

  @Test
  void hostilePomDoctypeIsRejectedWithoutEntityExpansion() {
    access.files.put("pom.xml", """
        <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
        <project><properties><java.version>&xxe;</java.version></properties></project>
        """.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> resolver.resolve(task("MODSIDECAR-208", null, "MODSIDECAR", null,
        SHA, null, null, null, "default"))).hasMessageContaining("not safe");
  }

  private TaskRequest task(String id, String repository, String project, String component,
                           String revision, String ref, String profile, String plan, String runKey) {
    return new TaskRequest(1, new TaskRequest.SourceIdentity("JIRA", id, project, component),
        repository, revision, ref, profile, plan, runKey, "LOCAL_ONLY", json.createObjectNode(),
        "Implement requested behavior", List.of(new TaskRequest.AcceptanceCriterion(
        "AC-1", "Behavior works", "TICKET")), json.createObjectNode(), null,
        "full original task text", false);
  }

  private static TaskRequest withMetadata(TaskRequest task, tools.jackson.databind.JsonNode metadata) {
    return new TaskRequest(task.schemaVersion(), task.source(), task.repository(), task.baseRevision(),
        task.baseRef(), task.profileId(), task.verificationPlanId(), task.runKey(), task.deliveryMode(),
        metadata, task.goal(), task.acceptanceCriteria(), task.constraints(), task.notes(),
        task.rawTaskText(), task.legacyAdapted());
  }

  private static TaskRequest withDecisions(TaskRequest task, List<TaskRequest.DeclaredDecision> decisions) {
    return new TaskRequest(task.schemaVersion(), task.source(), task.repository(), task.baseRevision(),
        task.baseRef(), task.profileId(), task.verificationPlanId(), task.runKey(), task.deliveryMode(),
        task.metadata(), task.goal(), task.acceptanceCriteria(), task.constraints(), task.notes(),
        task.rawTaskText(), task.legacyAdapted(), decisions);
  }

  private static final class FakeAccess implements RepositoryAccess {
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private int branchCalls;
    private int commitCalls;

    private FakeAccess() {
      files.put("pom.xml", JAVA_POM);
      files.put(".mvn/wrapper/maven-wrapper.properties",
          "distributionUrl=https://repo/apach-maven-3.9.16-bin.zip".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String resolveBranch(String slug, String branch) {
      branchCalls++;
      return SHA;
    }

    @Override
    public String verifyCommit(String slug, String fullSha) {
      commitCalls++;
      return fullSha;
    }

    @Override
    public Optional<byte[]> readFile(String slug, String fullSha, String path, int maxBytes) {
      return Optional.ofNullable(files.get(path));
    }
  }
}
