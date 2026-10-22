package org.folio.factory.app.developer;

import org.folio.factory.app.web.DeveloperPricingProperties;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.trigger.TriggerPreflight;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.intake.IssueSnapshot;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.repository.SelectedRepositoryPreflight;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Safe effective configuration and task admission, independent of application health. */
@Component
public class DeveloperPreflight implements TriggerPreflight, SelectedRepositoryPreflight {
    public enum Status { PASS, FAIL, WARN, NOT_RUN }
    public record Check(String name, Status status, String message) { }
    public record Report(boolean ready, String issueKey, Map<String, Object> runtime,
                         List<Map<String, Object>> repositories, List<Check> checks) { }

    private final DevFactoryProperties repositories;
    private final DevDeliveryProperties delivery;
    private final DevRuntimeProperties settings;
    private final CodingRuntime coding;
    private final JiraProperties jiraProperties;
    private final JiraConnector jira;
    private final GitHubProperties github;
    private final DeveloperPricingProperties pricing;
    private final DeveloperPreflightProbe probe;

    public DeveloperPreflight(DevFactoryProperties repositories, DevDeliveryProperties delivery,
                             DevRuntimeProperties settings, CodingRuntime coding, JiraProperties jiraProperties,
                             JiraConnector jira, GitHubProperties github, DeveloperPricingProperties pricing,
                             DeveloperPreflightProbe probe) {
        this.repositories = repositories;
        this.delivery = delivery;
        this.settings = settings;
        this.coding = coding;
        this.jiraProperties = jiraProperties;
        this.jira = jira;
        this.github = github;
        this.pricing = pricing;
        this.probe = probe;
    }

    @Override
    public void requireReady(String flowId, JsonNode payload) {
        if (!"dev-factory".equals(flowId)) return;
        String issueKey = payload == null ? "" : payload.path("issueKey").asString("");
        if (!IssueSnapshot.ISSUE_KEY.matcher(issueKey).matches())
            throw new IllegalArgumentException("Developer Flow preflight failed: a valid Jira issueKey is required.");
        var report = check(issueKey);
        if (!report.ready()) throw new IllegalArgumentException("Developer Flow preflight failed: "
                + report.checks().stream().filter(check -> check.status() == Status.FAIL)
                .map(Check::message).collect(java.util.stream.Collectors.joining("; ")));
    }

    @Override
    public void requireRepositoryReady(String repositoryKey, String frozenBaseSha) {
        if (!repositories.repositories().containsKey(repositoryKey)
                || frozenBaseSha == null || !frozenBaseSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
            throw new IllegalArgumentException("Selected repository and frozen base must be valid.");
        var checks = new ArrayList<Check>();
        if (checkDeliveryApi(checks)) checkRepository(repositoryKey, checks, frozenBaseSha);
        if (checks.stream().anyMatch(check -> check.status() == Status.FAIL))
            throw new IllegalStateException("Selected repository preflight failed: "
                    + checks.stream().filter(check -> check.status() == Status.FAIL).map(Check::message)
                    .collect(java.util.stream.Collectors.joining("; ")));
    }

    public Report check(String requestedIssue) {
        String issueKey = requestedIssue == null ? "" : requestedIssue;
        var checks = new ArrayList<Check>();
        checkRuntime(checks);
        if (issueKey.isEmpty()) {
            add(checks, "task", Status.NOT_RUN, "Enter an issue key to check Jira, repository mapping and delivery access.");
        } else if (!IssueSnapshot.ISSUE_KEY.matcher(issueKey).matches()) {
            add(checks, "task", Status.FAIL, "Use a valid Jira issue key.");
        } else {
            checkTask(issueKey, checks);
        }
        add(checks, "inference", Status.NOT_RUN,
                "No model call made. Provider authentication, model availability, streaming and tools are not verified.");
        return new Report(!issueKey.isEmpty() && checks.stream().noneMatch(check -> check.status() == Status.FAIL),
                issueKey, effectiveRuntime(), effectiveRepositories(), List.copyOf(checks));
    }

    private Map<String, Object> effectiveRuntime() {
        var identity = new LinkedHashMap<>(coding.identity());
        identity.put("timeoutSeconds", settings.timeoutSeconds());
        identity.put("mavenCacheVolume", settings.mavenCacheVolume());
        if ("claude-code".equals(coding.id())) {
            var profile = settings.coding().claudeCode();
            identity.put("api", profile.api());
            identity.put("authentication", ("api-key".equals(profile.authentication()) || "bearer-token".equals(profile.authentication()))
                    ? profile.authentication() : "unset or invalid");
            identity.put("maxTurns", profile.maxTurns());
            identity.put("maxOutputTokens", profile.maxOutputTokens());
        } else {
            var profile = settings.coding().piProfile();
            identity.put("authentication", "api-key".equals(profile.authentication()) ? "api-key" : "unset or invalid");
            if (profile.api() != null) identity.put("api", profile.api());
            if (profile.maxOutputTokens() != null) identity.put("maxOutputTokens", profile.maxOutputTokens());
        }
        try {
            URI uri = endpoint();
            if (uri != null) identity.put("endpointOrigin", new URI(uri.getScheme(), null, uri.getHost(),
                    uri.getPort(), null, null, null).toString());
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            identity.put("endpointOrigin", "unset or invalid");
        }
        return Map.copyOf(identity);
    }

    private void checkRuntime(List<Check> checks) {
        try {
            coding.requireConfigured();
            add(checks, "runtime", Status.PASS, "Selected coding profile is configured.");
            URI endpoint = endpoint();
            if (endpoint == null) {
                add(checks, "gateway", Status.NOT_RUN, "Runtime uses its provider endpoint; no explicit gateway to probe.");
            } else {
                boolean reachable = probe.gatewayReachable(endpoint);
                add(checks, "gateway", reachable ? Status.PASS : Status.WARN,
                        "Gateway TCP " + (reachable ? "is reachable" : "is not reachable")
                                + " from Factory; coding-container routing and API compatibility are not tested.");
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            add(checks, "runtime", Status.FAIL, "Configure the selected coding profile: API, model, endpoint and authentication.");
        }
        boolean docker = probe.dockerAvailable();
        add(checks, "docker", docker ? Status.PASS : Status.FAIL, docker ? "Docker daemon is reachable." : "Docker daemon is unavailable.");
        if (docker) {
            boolean imageAvailable = probe.imageAvailable(coding.image());
            add(checks, "coding-image", imageAvailable ? Status.PASS : Status.FAIL,
                    imageAvailable ? "Selected coding image exists locally."
                            : "Build the selected coding image with docker compose up -d --build.");
        }
        String model = String.valueOf(coding.identity().getOrDefault("model", ""));
        add(checks, "pricing", pricing.rateFor(model).isPresent() ? Status.PASS : Status.WARN,
                pricing.rateFor(model).isPresent() ? "A rate card exists for the literal model ID; estimate still depends on observed usage."
                        : "No matching rate card. Coding is allowed, but a complete cost estimate may be unavailable.");
    }

    private URI endpoint() {
        String value = "claude-code".equals(coding.id()) ? settings.coding().claudeCode().baseUrl()
                : settings.coding().piProfile().baseUrl();
        if (value == null || value.isBlank()) return null;
        URI endpoint = URI.create(value);
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("Gateway must be an HTTP(S) URL without embedded credentials");
        return endpoint;
    }

    private void checkTask(String issueKey, List<Check> checks) {
        if (!checkDeliveryApi(checks)) return;
        if (!jiraProperties.isReadable()) {
            add(checks, "jira", Status.FAIL, "Configure the Jira base URL and any credentials needed to read the issue.");
            return;
        }
        List<String> candidates;
        try {
            var issue = jira.getIssue(issueKey);
            if (issue == null || !issueKey.equals(issue.key())) throw new IllegalStateException();
            candidates = new RepositoryPolicy(repositories).candidates(IssueSnapshot.projectKey(issueKey),
                    IssueSnapshot.components(IssueSnapshot.of(issue)));
            add(checks, "jira", Status.PASS, "Requested issue is readable.");
        } catch (RuntimeException e) {
            add(checks, "jira", Status.FAIL, "Cannot read the requested Jira issue; check URL, credentials and issue access.");
            return;
        }
        if (candidates.isEmpty()) {
            add(checks, "mapping", Status.FAIL, "No configured repository matches the issue project and components.");
            return;
        }
        var candidateChecks = new ArrayList<Check>();
        boolean anyReady = false;
        for (String key : candidates) {
            var selected = new ArrayList<Check>();
            checkRepository(key, selected, null);
            anyReady |= selected.stream().noneMatch(check -> check.status() == Status.FAIL);
            candidateChecks.addAll(selected);
        }
        boolean choiceAvailable = candidates.size() > 1 && anyReady;
        for (var check : candidateChecks) {
            checks.add(choiceAvailable && check.status() == Status.FAIL
                    ? new Check(check.name(), Status.WARN, "Candidate option is not ready: " + check.message()) : check);
        }
        if (choiceAvailable) add(checks, "repository-choice", Status.NOT_RUN,
                "Multiple repositories match. Confirm one in review; it is checked again before checkout and build.");
    }

    private boolean checkDeliveryApi(List<Check> checks) {
        if (!"https://api.github.com".equals(github.effectiveBaseUrl().replaceAll("/+$", ""))) {
            add(checks, "delivery-api", Status.FAIL,
                    "Developer Flow delivery supports GitHub.com only; GitHub Enterprise and GitLab require a delivery adapter.");
            return false;
        }
        return true;
    }

    private void checkRepository(String key, List<Check> checks, String frozenBaseSha) {
        var repository = repositories.repositories().get(key);
        try {
            settings.command(repository.verificationPlan());
            add(checks, "verification:" + key, Status.PASS, "Trusted verification command is configured for " + key + ".");
        } catch (IllegalStateException e) {
            add(checks, "verification:" + key, Status.FAIL, "Configure the trusted verification plan for " + key + ".");
        }
        if (!probe.imageAvailable(repository.buildImage())) add(checks, "build-image:" + key, Status.WARN,
                "Build image is not local for " + key + "; Docker will need to pull it before baseline.");
        String sourceSha = frozenBaseSha;
        if (sourceSha == null) {
            try {
                sourceSha = probe.sourceBase(repositories.gitBaseUrl(), repository.sourceRepo(), repository.baseBranch());
                add(checks, "source:" + key, Status.PASS, "Public source base is readable for " + key + ".");
            } catch (RuntimeException e) {
                add(checks, "source:" + key, Status.FAIL, "Cannot read the public source base for " + key + ".");
                return;
            }
        }
        try {
            var target = delivery.requireTarget(key, repositories);
            if (!repository.baseBranch().equals(target.baseBranch())) throw new IllegalStateException();
            if (!github.isConfigured()) throw new IllegalStateException();
            probe.requireDeliveryAccess(target, sourceSha);
            add(checks, "delivery:" + key, Status.PASS, "Token reports push permission and destination contains the source base for " + key + ".");
        } catch (RuntimeException e) {
            add(checks, "delivery:" + key, Status.FAIL, "Check delivery authorization, GitHub token push permission and matching base for " + key + ".");
        }
    }

    private List<Map<String, Object>> effectiveRepositories() {
        return repositories.repositories().entrySet().stream().map(entry -> {
            var repository = entry.getValue();
            var target = delivery.target(entry.getKey(), repositories);
            var row = new LinkedHashMap<String, Object>();
            row.put("key", entry.getKey());
            row.put("source", repository.sourceRepo());
            row.put("baseBranch", repository.baseBranch());
            row.put("buildImage", repository.buildImage());
            row.put("verificationPlan", repository.verificationPlan());
            row.put("jiraProjects", repository.jiraProjects());
            row.put("delivery", target == null || target.repository() == null ? "" : target.repository());
            row.put("authorized", target != null && target.authorized());
            return Map.copyOf(row);
        }).toList();
    }

    private static void add(List<Check> checks, String name, Status status, String message) {
        checks.add(new Check(name, status, message));
    }
}
