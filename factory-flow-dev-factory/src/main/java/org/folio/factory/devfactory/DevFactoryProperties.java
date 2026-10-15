package org.folio.factory.devfactory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Trusted Developer Flow configuration. Repository, base branch and verification
 * values come only from here — never from Jira text or model output.
 */
@ConfigurationProperties(prefix = "factory.dev-factory")
public record DevFactoryProperties(String gitBaseUrl, SortedMap<String, Repository> repositories) {

    private static final Pattern REPOSITORY_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern SOURCE_REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}");
    private static final Pattern PLAN_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    public DevFactoryProperties {
        gitBaseUrl = gitBaseUrl == null || gitBaseUrl.isBlank() ? "https://github.com" : gitBaseUrl.strip();
        if (gitBaseUrl.endsWith("/")) {
            gitBaseUrl = gitBaseUrl.substring(0, gitBaseUrl.length() - 1);
        }
        SortedMap<String, Repository> copy = new TreeMap<>();
        if (repositories != null) {
            repositories.forEach((key, repository) -> {
                require(REPOSITORY_KEY, key, "repository key");
                if (repository == null) {
                    throw new IllegalArgumentException("factory.dev-factory.repositories." + key + " is empty");
                }
                copy.put(key, repository);
            });
        }
        repositories = copy;
    }

    public record Repository(String sourceRepo, String baseBranch, String buildImage, String verificationPlan,
                             List<String> jiraProjects, List<String> jiraComponents) {

        public Repository {
            require(SOURCE_REPO, sourceRepo, "source-repo");
            require(BRANCH, baseBranch, "base-branch");
            if (baseBranch.contains("..") || baseBranch.endsWith("/") || baseBranch.endsWith(".lock")) {
                throw new IllegalArgumentException("Invalid base-branch '" + baseBranch + "'");
            }
            require(IMAGE, buildImage, "build-image");
            require(PLAN_ID, verificationPlan, "verification-plan");
            if (jiraProjects == null || jiraProjects.isEmpty()) {
                throw new IllegalArgumentException("jira-projects must list at least one Jira project key");
            }
            jiraProjects.forEach(project -> require(PROJECT_KEY, project, "jira-projects entry"));
            jiraProjects = List.copyOf(jiraProjects);
            jiraComponents = jiraComponents == null ? List.of() : List.copyOf(jiraComponents);
        }
    }


    private static void require(Pattern pattern, String value, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Developer Flow " + name + " '" + value + "'");
        }
    }
}
