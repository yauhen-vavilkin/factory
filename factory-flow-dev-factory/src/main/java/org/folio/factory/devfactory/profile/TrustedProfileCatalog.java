package org.folio.factory.devfactory.profile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.folio.factory.devfactory.contract.CanonicalJson;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Code-owned initial profile and verification-plan catalog. */
public final class TrustedProfileCatalog {
  /** Protocol-level model provider; the upstream vendor is known only to the gateway. */
  public static final String MODEL_PROVIDER = "openai-compatible";
  /** Stable model alias the coding runtime requests; the gateway maps it upstream. */
  public static final String MODEL_ALIAS = "factory-coding";
  public static final String VERSION = "dev-factory-v1-m1";
  public static final String JAVA_MAVEN_21 = "java-maven-21";
  public static final String JAVA_MAVEN_PI = "java21-pi-unit";
  public static final String JAVA_MAVEN_PI_VERSION = "dev-factory-v1-m3";
  public static final String JAVA_MAVEN_VERIFY = "java-maven-verify";
  public static final String JAVA_MAVEN_VERIFY_IT = "java-maven-verify-it";
  public static final String SCENARIO_README_VERIFY = "scenario-readme-verify";
  /** General plans an operator may select for any Java/Maven task, weakest first. */
  public static final List<String> GENERAL_JAVA_MAVEN_PLANS = List.of(JAVA_MAVEN_VERIFY, JAVA_MAVEN_VERIFY_IT);

  private final Map<String, ExecutionProfile> profiles;
  private final Map<String, VerificationPlan> plans;

  public TrustedProfileCatalog() {
    ExecutionProfile.Budgets budgets = new ExecutionProfile.Budgets(3600, 300, 900, 40, 32768);
    ExecutionProfile profileWithoutHash = new ExecutionProfile(JAVA_MAVEN_21, VERSION, "JAVA",
        "MAVEN", "21", null, "maven:3.9-eclipse-temurin-21", null, "linux/arm64",
        MODEL_PROVIDER, "java-maven-21",
        "/workspace/repo", List.of(List.of("./mvnw", "-B", "-ntp", "test")),
        Map.of("surefire", "**/target/surefire-reports/TEST-*.xml"),
        new ExecutionProfile.NetworkPolicy("APPROVED_DEPENDENCY_PROXY_ONLY", "NONE"),
        new ExecutionProfile.ResourcePolicy(2.0, 4096, 512, 8192), budgets, null);
    ExecutionProfile profile = withHash(profileWithoutHash);
    ExecutionProfile piWithoutHash = new ExecutionProfile(JAVA_MAVEN_PI, JAVA_MAVEN_PI_VERSION, "JAVA",
        "MAVEN", "21", null, "factory-pi:jdk21", null, "linux/arm64",
        MODEL_PROVIDER, MODEL_ALIAS, "/workspace/repo",
        List.of(List.of("mvn", "-B", "-ntp", "test")),
        Map.of("surefire", "**/target/surefire-reports/TEST-*.xml"),
        new ExecutionProfile.NetworkPolicy("APPROVED_DEPENDENCY_PROXY_ONLY", "GATEWAY_ONLY"),
        new ExecutionProfile.ResourcePolicy(2.0, 4096, 512, 8192),
        new ExecutionProfile.Budgets(3600, 300, 900, 40, 16384), null);
    ExecutionProfile pi = withHash(piWithoutHash);
    profiles = Map.of(profile.id(), profile, pi.id(), pi);

    // Plain `mvn`, not `./mvnw`: the approved sandbox images ship Maven on the
    // PATH and the approved FOLIO repositories do not ship a wrapper, so a
    // wrapper argv would make the authoritative unit gate unexecutable.
    VerificationPlan planWithoutHash = new VerificationPlan(JAVA_MAVEN_VERIFY, VERSION,
        List.of(new VerificationPlan.Check("maven-tests",
            List.of("mvn", "-B", "-ntp", "test"), true, List.of(),
            "**/target/surefire-reports/TEST-*.xml", false)), null);
    VerificationPlan plan = withHash(planWithoutHash);
    // Honest plan for tasks whose acceptance criteria demand the full build
    // including the failsafe integration suite: `mvn clean verify` runs the
    // Testcontainers-backed ITs of the approved FOLIO repositories, so the
    // check declares the Docker capability it needs.
    VerificationPlan itPlan = withHash(new VerificationPlan(JAVA_MAVEN_VERIFY_IT, VERSION,
        List.of(new VerificationPlan.Check("maven-verify-it",
            List.of("mvn", "-B", "-ntp", "clean", "verify"), true, List.of(),
            "**/target/surefire-reports/TEST-*.xml", true)), null));
    VerificationPlan scenarioPlan = withHash(new VerificationPlan(SCENARIO_README_VERIFY, VERSION,
        List.of(new VerificationPlan.Check("readme-change",
            List.of("cd repo && grep -n 'T16 scenario change.' README.md"), true, List.of(), null,
            false)), null));
    VerificationPlan piPlan = withHash(new VerificationPlan("modsidecar-208-v1", JAVA_MAVEN_PI_VERSION,
        List.of(new VerificationPlan.Check("maven-tests", List.of("mvn", "-B", "-ntp", "test"),
            true, List.of(), "**/target/surefire-reports/TEST-*.xml", false)), null));
    plans = Map.of(plan.id(), plan, itPlan.id(), itPlan, piPlan.id(), piPlan, scenarioPlan.id(),
        scenarioPlan);
  }

  public Optional<ExecutionProfile> profile(String id) {
    return Optional.ofNullable(profiles.get(id));
  }

  public Optional<VerificationPlan> verificationPlan(String id) {
    return Optional.ofNullable(plans.get(id));
  }

  public ExecutionProfile resolveProfile(ProfileEvidence evidence, String requestedId) {
    if ("NODE".equals(evidence.language())) {
      throw new UnsupportedProfileException("UNSUPPORTED_PROFILE",
          "Node/frontend repositories are not supported by the v1 Maven profile");
    }
    if (!"JAVA".equals(evidence.language()) || !"MAVEN".equals(evidence.buildTool())) {
      throw new UnsupportedProfileException("PROFILE_EVIDENCE_UNKNOWN",
          "language or build tool could not be determined from repository files");
    }
    if (evidence.languageVersion() == null) {
      throw new UnsupportedProfileException("JAVA_VERSION_UNKNOWN",
          "Java version is not explicit in trusted static repository evidence");
    }
    if (!"21".equals(normalizeJava(evidence.languageVersion()))) {
      throw new UnsupportedProfileException("UNSUPPORTED_PROFILE",
          "no approved profile for Java " + evidence.languageVersion());
    }
    String selected = requestedId == null ? JAVA_MAVEN_21 : requestedId;
    ExecutionProfile profile = profile(selected).orElseThrow(() ->
        new UnsupportedProfileException("UNKNOWN_PROFILE", "unknown trusted profile id: " + selected));
    if (!profile.language().equals(evidence.language()) || !profile.buildTool().equals(evidence.buildTool())) {
      throw new UnsupportedProfileException("PROFILE_MISMATCH",
          "requested profile conflicts with detected repository evidence");
    }
    return profile;
  }

  /** An explicitly selected trusted plan. There is no implicit default plan. */
  public VerificationPlan resolvePlan(String requestedId) {
    if (requestedId == null || requestedId.isBlank()) {
      throw new IllegalArgumentException("a verification plan id is required");
    }
    return verificationPlan(requestedId).orElseThrow(() ->
        new UnsupportedProfileException("UNKNOWN_VERIFICATION_PLAN",
            "unknown trusted verification plan id: " + requestedId));
  }

  /**
   * The general trusted plans that can prove a task on the given profile when
   * the task does not name one, weakest first. When more than one remains,
   * the plans are materially different and Factory must not pick one itself.
   */
  public List<VerificationPlan> planCandidates(ExecutionProfile profile) {
    if (!"JAVA".equals(profile.language()) || !"MAVEN".equals(profile.buildTool())) {
      return List.of();
    }
    return GENERAL_JAVA_MAVEN_PLANS.stream().map(plans::get).toList();
  }

  private static String normalizeJava(String value) {
    return value.startsWith("1.") ? value.substring(2) : value.replaceAll("[^0-9].*$", "");
  }

  private static ExecutionProfile withHash(ExecutionProfile profile) {
    JsonMapper json = JsonMapper.builder().build();
    ObjectNode node = json.valueToTree(profile);
    node.remove("configurationHash");
    String hash = CanonicalJson.sha256(node);
    return new ExecutionProfile(profile.id(), profile.catalogVersion(), profile.language(),
        profile.buildTool(), profile.languageVersion(), profile.framework(), profile.imageReference(),
        profile.imageDigest(), profile.platform(), profile.modelProvider(), profile.modelId(),
        profile.workdir(), profile.buildCommands(),
        profile.reportGlobs(), profile.networkPolicy(), profile.resources(), profile.budgets(), hash);
  }

  private static VerificationPlan withHash(VerificationPlan plan) {
    JsonMapper json = JsonMapper.builder().build();
    ObjectNode node = json.valueToTree(plan);
    node.remove("configurationHash");
    String hash = CanonicalJson.sha256(node);
    return new VerificationPlan(plan.id(), plan.catalogVersion(), plan.checks(), hash);
  }
}
