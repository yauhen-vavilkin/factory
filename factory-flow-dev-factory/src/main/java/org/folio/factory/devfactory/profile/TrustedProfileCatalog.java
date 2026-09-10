package org.folio.factory.devfactory.profile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.folio.factory.devfactory.contract.CanonicalJson;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Code-owned initial profile and verification-plan catalog. */
public final class TrustedProfileCatalog {
  public static final String VERSION = "dev-factory-v1-m1";
  public static final String JAVA_MAVEN_21 = "java-maven-21";
  public static final String JAVA_MAVEN_PI = "java21-pi-unit";
  public static final String JAVA_MAVEN_VERIFY = "java-maven-verify";

  private final Map<String, ExecutionProfile> profiles;
  private final Map<String, VerificationPlan> plans;

  public TrustedProfileCatalog() {
    ExecutionProfile.Budgets budgets = new ExecutionProfile.Budgets(3600, 300, 900, 40, 32768);
    ExecutionProfile profileWithoutHash = new ExecutionProfile(JAVA_MAVEN_21, VERSION, "JAVA",
        "MAVEN", "21", null, "maven:3.9-eclipse-temurin-21", null, "linux/arm64",
        "/workspace/repo", List.of(List.of("./mvnw", "-B", "-ntp", "test")),
        Map.of("surefire", "**/target/surefire-reports/TEST-*.xml"),
        new ExecutionProfile.NetworkPolicy("APPROVED_DEPENDENCY_PROXY_ONLY", "NONE"),
        new ExecutionProfile.ResourcePolicy(2.0, 4096, 512, 8192), budgets, null);
    ExecutionProfile profile = withHash(profileWithoutHash);
    ExecutionProfile piWithoutHash = new ExecutionProfile(JAVA_MAVEN_PI, "dev-factory-v1-m2", "JAVA",
        "MAVEN", "21", null, "factory-pi:jdk21", null, "linux/arm64", "/workspace/repo",
        List.of(List.of("mvn", "-B", "-ntp", "test")),
        Map.of("surefire", "**/target/surefire-reports/TEST-*.xml"),
        new ExecutionProfile.NetworkPolicy("APPROVED_DEPENDENCY_PROXY_ONLY", "GATEWAY_ONLY"),
        new ExecutionProfile.ResourcePolicy(2.0, 4096, 512, 8192),
        new ExecutionProfile.Budgets(3600, 300, 900, 40, 16384), null);
    ExecutionProfile pi = withHash(piWithoutHash);
    profiles = Map.of(profile.id(), profile, pi.id(), pi);

    VerificationPlan planWithoutHash = new VerificationPlan(JAVA_MAVEN_VERIFY, VERSION,
        List.of(new VerificationPlan.Check("maven-tests",
            List.of("./mvnw", "-B", "-ntp", "test"), true, List.of(),
            "**/target/surefire-reports/TEST-*.xml")), null);
    VerificationPlan plan = withHash(planWithoutHash);
    VerificationPlan piPlan = withHash(new VerificationPlan("modsidecar-208-v1", "dev-factory-v1-m2",
        List.of(new VerificationPlan.Check("maven-tests", List.of("mvn", "-B", "-ntp", "test"),
            true, List.of(), "**/target/surefire-reports/TEST-*.xml")), null));
    plans = Map.of(plan.id(), plan, piPlan.id(), piPlan);
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

  public VerificationPlan resolvePlan(String requestedId) {
    String selected = requestedId == null ? JAVA_MAVEN_VERIFY : requestedId;
    return verificationPlan(selected).orElseThrow(() ->
        new UnsupportedProfileException("UNKNOWN_VERIFICATION_PLAN",
            "unknown trusted verification plan id: " + selected));
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
        profile.imageDigest(), profile.platform(), profile.workdir(), profile.buildCommands(),
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
