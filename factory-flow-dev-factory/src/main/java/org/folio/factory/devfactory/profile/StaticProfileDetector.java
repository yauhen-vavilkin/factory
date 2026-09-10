package org.folio.factory.devfactory.profile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.folio.factory.devfactory.resolution.RepositoryAccess;
import org.w3c.dom.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Detects build facts by bounded file reads only; it never invokes repository code. */
public final class StaticProfileDetector {
  private static final int MAX_EVIDENCE_BYTES = 256 * 1024;
  private final RepositoryAccess access;
  private final JsonMapper json = JsonMapper.builder().build();

  public StaticProfileDetector(RepositoryAccess access) {
    this.access = access;
  }

  public ProfileEvidence detect(String slug, String sha) {
    Optional<byte[]> packageJson = access.readFile(slug, sha, "package.json", MAX_EVIDENCE_BYTES);
    Optional<byte[]> pom = access.readFile(slug, sha, "pom.xml", MAX_EVIDENCE_BYTES);
    if (packageJson.isPresent()) {
      return nodeEvidence(slug, sha, packageJson.get());
    }
    if (pom.isEmpty()) {
      return new ProfileEvidence("UNKNOWN", "UNKNOWN", null, null, null, null, "UNKNOWN",
          List.of(), List.of("LANGUAGE_UNKNOWN", "BUILD_TOOL_UNKNOWN", "DOCKER_REQUIREMENT_UNKNOWN"));
    }
    Document document = SafePomReader.parse(pom.get());
    List<String> paths = new ArrayList<>(List.of("pom.xml"));
    String javaVersion = SafePomReader.firstText(document, "maven.compiler.release",
        "java.version", "maven.compiler.target").orElse(null);
    String framework = SafePomReader.contains(document, "quarkus") ? "Quarkus"
        : SafePomReader.contains(document, "spring-boot") ? "Spring Boot" : null;
    String frameworkVersion = "Quarkus".equals(framework)
        ? SafePomReader.firstText(document, "quarkus.platform.version", "quarkus.version").orElse(null)
        : "Spring Boot".equals(framework)
            ? SafePomReader.firstText(document, "spring-boot.version").orElse(null) : null;
    String wrapper = access.readFile(slug, sha, ".mvn/wrapper/maven-wrapper.properties", 16 * 1024)
        .map(bytes -> wrapperVersion(new String(bytes, StandardCharsets.UTF_8))).orElse(null);
    if (wrapper != null) {
      paths.add(".mvn/wrapper/maven-wrapper.properties");
    }
    List<String> unknowns = new ArrayList<>();
    if (javaVersion == null) {
      unknowns.add("JAVA_VERSION_UNKNOWN");
    }
    if (framework != null && frameworkVersion == null) {
      unknowns.add("INHERITED_FRAMEWORK_VERSION_UNKNOWN");
    }
    // A missing direct dependency cannot establish that integration tests do not need Docker.
    unknowns.add("DOCKER_REQUIREMENT_UNKNOWN");
    return new ProfileEvidence("JAVA", "MAVEN", javaVersion, framework, frameworkVersion, wrapper, "UNKNOWN",
        List.copyOf(paths), List.copyOf(unknowns));
  }

  private ProfileEvidence nodeEvidence(String slug, String sha, byte[] bytes) {
    try {
      JsonNode root = json.readTree(bytes);
      String version = root.path("engines").path("node").asString(null);
      List<String> paths = new ArrayList<>(List.of("package.json"));
      String lock = null;
      for (String candidate : List.of("pnpm-lock.yaml", "yarn.lock", "package-lock.json")) {
        if (access.readFile(slug, sha, candidate, 1024).isPresent()) {
          lock = candidate;
          paths.add(candidate);
          break;
        }
      }
      List<String> unknowns = new ArrayList<>();
      if (version == null) {
        unknowns.add("NODE_VERSION_UNKNOWN");
      }
      unknowns.add("DOCKER_REQUIREMENT_UNKNOWN");
      return new ProfileEvidence("NODE", lock == null ? "NPM_OR_UNKNOWN" : lock, version,
          null, null, null, "UNKNOWN", List.copyOf(paths), List.copyOf(unknowns));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("package.json is not valid JSON", e);
    }
  }

  private static String wrapperVersion(String properties) {
    for (String line : properties.lines().toList()) {
      if (line.startsWith("distributionUrl=")) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("apache-maven-([0-9.]+)-bin")
            .matcher(line);
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
    }
    return null;
  }
}
