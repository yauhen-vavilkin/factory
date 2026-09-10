package org.folio.factory.devfactory.resolution;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Trusted v1 repository catalog and exact mappings. */
public final class RepositoryCatalog {
  public static final String ORGANIZATION = "folio-org";

  private static final Set<String> REPOSITORIES = Set.of(
      "folio-org/folio-module-sidecar",
      "folio-org/mgr-tenant-entitlements",
      "folio-org/mod-scheduler",
      "folio-org/mod-roles-keycloak");
  private static final Map<String, String> PROJECTS = Map.of(
      "MODSIDECAR", "folio-org/folio-module-sidecar",
      "MGRENTITLE", "folio-org/mgr-tenant-entitlements",
      "MODSCHED", "folio-org/mod-scheduler",
      "MODROLESKC", "folio-org/mod-roles-keycloak");
  private static final Map<String, String> ALIASES = aliases();

  public Set<String> candidates(String explicit, String project, String component) {
    Set<String> result = new LinkedHashSet<>();
    canonicalizeExplicit(explicit).ifPresent(result::add);
    lookup(PROJECTS, project).ifPresent(result::add);
    lookup(ALIASES, component).ifPresent(result::add);
    return Set.copyOf(result);
  }

  public Optional<String> canonicalizeExplicit(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    if (!trimmed.contains("://")) {
      String canonical = ALIASES.get(trimmed.toLowerCase(Locale.ROOT));
      if (canonical == null || !REPOSITORIES.contains(canonical)) {
        throw new RepositorySecurityException("repository is not in the approved catalog: " + value);
      }
      return Optional.of(canonical);
    }
    URI uri;
    try {
      uri = URI.create(trimmed);
    } catch (IllegalArgumentException e) {
      throw new RepositorySecurityException("repository URL is malformed", e);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())) {
      throw new RepositorySecurityException("only approved HTTPS GitHub origins are allowed");
    }
    if (uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new RepositorySecurityException("repository URL credentials, ports, query and fragment are forbidden");
    }
    String path = uri.getPath();
    if (path == null || path.contains("//") || path.contains("..")) {
      throw new RepositorySecurityException("repository URL path is unsafe");
    }
    String slug = path.replaceFirst("^/", "").replaceFirst("\\.git$", "");
    if (!REPOSITORIES.contains(slug)) {
      throw new RepositorySecurityException("repository is not in the approved catalog: " + slug);
    }
    return Optional.of(slug);
  }

  public String origin(String slug) {
    if (!REPOSITORIES.contains(slug)) {
      throw new RepositorySecurityException("repository is not approved: " + slug);
    }
    return "https://github.com/" + slug + ".git";
  }

  public Set<String> approvedSlugs() {
    return REPOSITORIES;
  }

  private static Optional<String> lookup(Map<String, String> map, String value) {
    if (value == null) {
      return Optional.empty();
    }
    String result = map.get(value);
    return Optional.ofNullable(result == null ? map.get(value.toLowerCase(Locale.ROOT)) : result);
  }

  private static Map<String, String> aliases() {
    Map<String, String> result = new LinkedHashMap<>();
    for (String slug : REPOSITORIES) {
      result.put(slug, slug);
      result.put(slug.substring(slug.indexOf('/') + 1), slug);
    }
    result.put("module-sidecar", "folio-org/folio-module-sidecar");
    result.put("sidecar", "folio-org/folio-module-sidecar");
    result.put("tenant-entitlements", "folio-org/mgr-tenant-entitlements");
    result.put("scheduler", "folio-org/mod-scheduler");
    result.put("roles-keycloak", "folio-org/mod-roles-keycloak");
    PROJECTS.forEach((project, slug) -> result.put(project.toLowerCase(Locale.ROOT), slug));
    return Map.copyOf(result);
  }
}
