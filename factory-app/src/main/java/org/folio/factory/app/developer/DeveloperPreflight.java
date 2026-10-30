package org.folio.factory.app.developer;

import org.folio.factory.app.web.DeveloperPricingProperties;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.trigger.TriggerPreflight;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.DevFactoryProperties.Repository;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.intake.IssueSnapshot;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.repository.SelectedRepositoryPreflight;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.RunProfiles;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Safe effective configuration and task admission, independent of application health. */
@Component
public class DeveloperPreflight implements TriggerPreflight, SelectedRepositoryPreflight {

    private static final String FLOW_ID = "dev-factory";
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    /**
     * Checks that fail because of the task itself or the run profile it chose, not because of Factory,
     * Docker, Jira or GitHub.
     */
    private static final Set<String> TASK_CHECKS = Set.of("task", "issue", "mapping", "profile");
    /** The settings of the pilot run being launched on this thread; see {@link #forRun}. */
    private static final ThreadLocal<CodingRequest.RunSettings> LAUNCHING = new ThreadLocal<>();

    public enum Status {
        PASS, FAIL, WARN, NOT_RUN
    }

    public record Check(String name, Status status, String message) {
    }

    public record Report(boolean ready, String issueKey, Map<String, Object> runtime,
                         List<Map<String, Object>> repositories, List<Check> checks,
                         List<Map<String, Object>> profiles) {
    }

    /**
     * The preflight failed only because of the task itself: Jira no longer returns
     * the issue under its key, or no configured repository matches it. Waiting does
     * not help, unlike a failure of Docker, the runtime, Jira or GitHub.
     */
    public static final class TaskNotRunnableException extends IllegalArgumentException {
        private final String reason;

        public TaskNotRunnableException(String reason) {
            super("Developer Flow preflight failed: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private final DevFactoryProperties repositories;
    private final DevDeliveryProperties delivery;
    private final DevRuntimeProperties settings;
    private final CodingRuntime coding;
    private final JiraProperties jiraProperties;
    private final JiraConnector jira;
    private final GitHubProperties github;
    private final DeveloperPricingProperties pricing;
    private final DeveloperPreflightProbe probe;
    private final RunProfiles profiles;

    public DeveloperPreflight(DevFactoryProperties repositories, DevDeliveryProperties delivery,
                              DevRuntimeProperties settings, CodingRuntime coding, JiraProperties jiraProperties,
                              JiraConnector jiraConnector, GitHubProperties github, DeveloperPricingProperties pricing,
                              DeveloperPreflightProbe probe, RunProfiles profiles) {
        this.repositories = repositories;
        this.delivery = delivery;
        this.settings = settings;
        this.coding = coding;
        this.jiraProperties = jiraProperties;
        this.jira = jiraConnector;
        this.github = github;
        this.pricing = pricing;
        this.probe = probe;
        this.profiles = profiles;
    }

    /**
     * Runs a trigger (and with it this preflight) for a pilot run that uses these settings. The trigger
     * payload cannot carry them, so the launcher hands them over here: a broken default profile then does
     * not block a run with another profile, and a run whose own profile cannot be used stops alone.
     */
    public static <T> T forRun(CodingRequest.RunSettings settings, Supplier<T> trigger) {
        LAUNCHING.set(settings);
        try {
            return trigger.get();
        } finally {
            LAUNCHING.remove();
        }
    }

    @Override
    public void requireReady(String flowId, JsonNode payload) {
        if (!FLOW_ID.equals(flowId)) {
            return;
        }

        String issueKey = payload == null ? "" : payload.path("issueKey").asString("");
        if (!IssueSnapshot.ISSUE_KEY.matcher(issueKey).matches()) {
            throw new IllegalArgumentException("Developer Flow preflight failed: a valid Jira issueKey is required.");
        }

        Report report = check(issueKey, false);
        if (!report.ready()) {
            if (report.checks().stream().filter(check -> check.status() == Status.FAIL)
                    .allMatch(check -> TASK_CHECKS.contains(check.name()))) {
                throw new TaskNotRunnableException(failureMessages(report.checks()));
            }
            throw new IllegalArgumentException("Developer Flow preflight failed: " + failureMessages(report.checks()));
        }
    }

    /**
     * Refuses a My tasks run before it is queued when the GitHub token cannot push
     * to the repository's effective delivery destination, so no paid work starts
     * that could only fail at the pull request.
     */
    public void requirePushAccess(String repositoryKey) {
        DeliveryTarget target = delivery.requireTarget(repositoryKey, repositories);
        if (!github.isConfigured()) {
            throw new IllegalStateException("Set FACTORY_CONNECTORS_GITHUB_TOKEN to a token that can push to "
                    + target.repository());
        }
        try {
            probe.requirePushAccess(target);
        } catch (RestClientException | IllegalStateException e) {
            throw new IllegalStateException("Your GitHub token cannot push to " + target.repository());
        }
    }

    @Override
    public void requireRepositoryReady(String repositoryKey, String frozenBaseSha) {
        if (!repositories.repositories().containsKey(repositoryKey)
                || frozenBaseSha == null || !COMMIT_SHA.matcher(frozenBaseSha).matches()) {
            throw new IllegalArgumentException("Selected repository and frozen base must be valid.");
        }

        List<Check> checks = new ArrayList<>();
        if (checkDeliveryApi(checks)) {
            checkRepository(repositoryKey, checks, frozenBaseSha);
        }
        if (hasFailures(checks)) {
            throw new IllegalStateException("Selected repository preflight failed: " + failureMessages(checks));
        }
    }

    public Report check(String requestedIssue) {
        return check(requestedIssue, true);
    }

    /** A trigger needs only the checks of its own run; the page also lists every run profile. */
    private Report check(String requestedIssue, boolean listProfiles) {
        String issueKey = requestedIssue == null ? "" : requestedIssue;
        List<Check> checks = new ArrayList<>();
        checkRuntime(checks);
        if (issueKey.isEmpty()) {
            add(checks, "task", Status.NOT_RUN,
                    "Enter an issue key to check Jira, repository mapping and delivery access.");
        } else if (!IssueSnapshot.ISSUE_KEY.matcher(issueKey).matches()) {
            add(checks, "task", Status.FAIL, "Use a valid Jira issue key.");
        } else {
            checkTask(issueKey, checks);
        }
        add(checks, "inference", Status.NOT_RUN,
                "No model call made. Provider authentication, model availability, streaming and tools are not verified.");

        boolean ready = !issueKey.isEmpty() && !hasFailures(checks);
        return new Report(ready, issueKey, effectiveRuntime(), effectiveRepositories(), List.copyOf(checks),
                listProfiles ? profileRows() : List.of());
    }

    private void checkRuntime(List<Check> checks) {
        Check configuration = checkCodingConfiguration();
        checks.add(configuration);
        if (configuration.status() == Status.PASS) {
            checks.add(checkGateway());
        }
        checkDocker(checks);
        checks.add(checkPricing());
    }

    private Check checkCodingConfiguration() {
        try {
            coding.requireConfigured(LAUNCHING.get());
            endpoint();
            return new Check("runtime", Status.PASS, usesClaudeCode()
                    ? "Run profile " + runProfile().id() + " is configured." : "Selected coding profile is configured.");
        } catch (RunProfiles.ProfileException e) {
            // Written to be shown; a run that cannot use its own profile stops alone instead of the whole queue.
            return new Check("profile", Status.FAIL, coding.redact(e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Configuration exceptions can contain credentials. Report a safe instruction instead.
            return new Check("runtime", Status.FAIL,
                    "Configure the selected coding profile: API, model, endpoint and authentication.");
        }
    }

    private Check checkGateway() {
        URI endpoint = endpoint();
        if (endpoint == null) {
            return new Check("gateway", Status.NOT_RUN,
                    "Runtime uses its provider endpoint; no explicit gateway to probe.");
        }
        if (probe.gatewayReachable(endpoint)) {
            return new Check("gateway", Status.PASS,
                    "Gateway TCP is reachable from Factory; coding-container routing and API compatibility are not tested.");
        }
        return new Check("gateway", Status.WARN,
                "Gateway TCP is not reachable from Factory; coding-container routing and API compatibility are not tested.");
    }

    private void checkDocker(List<Check> checks) {
        if (!probe.dockerAvailable()) {
            add(checks, "docker", Status.FAIL, "Docker daemon is unavailable.");
            return;
        }
        add(checks, "docker", Status.PASS, "Docker daemon is reachable.");
        if (probe.imageAvailable(coding.image())) {
            add(checks, "coding-image", Status.PASS, "Selected coding image exists locally.");
        } else {
            add(checks, "coding-image", Status.FAIL,
                    "Build the selected coding image with docker compose up -d --build.");
        }
    }

    private Check checkPricing() {
        String model = selectedModel();
        if (pricing.rateFor(model).isPresent()) {
            return new Check("pricing", Status.PASS,
                    "A rate card exists for the literal model ID; estimate still depends on observed usage.");
        }
        return new Check("pricing", Status.WARN,
                "No matching rate card. Coding is allowed, but a complete cost estimate may be unavailable.");
    }

    private String selectedModel() {
        if (usesClaudeCode()) {
            try {
                return runProfile().model();
            } catch (RunProfiles.ProfileException e) {
                return ""; // The profile check already fails with the reason.
            }
        }
        return settings.coding().piProfile().model();
    }

    /** The profile of the run being checked: the launching pilot run's, or the default. */
    private RunProfiles.Profile runProfile() {
        return profiles.resolve(LAUNCHING.get()).profile();
    }

    private URI endpoint() {
        String baseUrl;
        if (usesClaudeCode()) {
            baseUrl = runProfile().baseUrl();
        } else {
            baseUrl = settings.coding().piProfile().baseUrl();
        }
        return endpoint(baseUrl);
    }

    private static URI endpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }

        URI endpoint = URI.create(baseUrl);
        boolean supportedScheme = "http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme());
        if (!supportedScheme || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Gateway must be an HTTP(S) URL without embedded credentials");
        }
        return endpoint;
    }

    private boolean usesClaudeCode() {
        return "claude-code".equals(coding.id());
    }

    private void checkTask(String issueKey, List<Check> checks) {
        if (!checkDeliveryApi(checks)) {
            return;
        }
        if (!jiraProperties.isReadable()) {
            add(checks, "jira", Status.FAIL, "Configure the Jira base URL and any credentials needed to read the issue.");
            return;
        }

        List<String> candidates;
        try {
            JiraIssue issue = jira.getIssue(issueKey);
            if (issue == null || !issueKey.equals(issue.key())) {
                add(checks, "issue", Status.FAIL, "Jira did not return the requested issue.");
                return;
            }
            RepositoryPolicy policy = new RepositoryPolicy(repositories);
            candidates = policy.candidates(IssueSnapshot.projectKey(issueKey),
                    IssueSnapshot.components(IssueSnapshot.of(issue)));
        } catch (HttpClientErrorException.NotFound e) {
            // Jira itself answers 404 with its errorMessages JSON for a deleted issue or one the
            // reader may not see. Any other 404 (a wrong base URL, a proxy page) is Jira being
            // unreachable, which must keep the queue waiting instead of stopping every task.
            if (e.getResponseBodyAsString().contains("\"errorMessages\"")) {
                add(checks, "issue", Status.FAIL, "Jira did not return the requested issue.");
            } else {
                add(checks, "jira", Status.FAIL,
                        "Cannot read the requested Jira issue; check URL, credentials and issue access.");
            }
            return;
        } catch (RestClientException | IllegalStateException | IllegalArgumentException e) {
            add(checks, "jira", Status.FAIL, "Cannot read the requested Jira issue; check URL, credentials and issue access.");
            return;
        }
        add(checks, "jira", Status.PASS, "Requested issue is readable.");
        if (candidates.isEmpty()) {
            add(checks, "mapping", Status.FAIL, "No configured repository matches the issue project and components.");
            return;
        }
        checkCandidates(candidates, checks);
    }

    private void checkCandidates(List<String> candidates, List<Check> checks) {
        List<Check> candidateChecks = new ArrayList<>();
        boolean anyCandidateReady = false;
        for (String repositoryKey : candidates) {
            List<Check> selected = new ArrayList<>();
            checkRepository(repositoryKey, selected, null);
            if (!hasFailures(selected)) {
                anyCandidateReady = true;
            }
            candidateChecks.addAll(selected);
        }

        boolean choiceAvailable = candidates.size() > 1 && anyCandidateReady;
        for (Check check : candidateChecks) {
            if (choiceAvailable && check.status() == Status.FAIL) {
                checks.add(new Check(check.name(), Status.WARN, "Candidate option is not ready: " + check.message()));
            } else {
                checks.add(check);
            }
        }
        if (choiceAvailable) {
            add(checks, "repository-choice", Status.NOT_RUN,
                    "Multiple repositories match. Confirm one in review; it is checked again before checkout and build.");
        }
    }

    private boolean checkDeliveryApi(List<Check> checks) {
        String baseUrl = github.effectiveBaseUrl().replaceAll("/+$", "");
        if (!"https://api.github.com".equals(baseUrl)) {
            add(checks, "delivery-api", Status.FAIL,
                    "Developer Flow delivery supports GitHub.com only; GitHub Enterprise and GitLab require a delivery adapter.");
            return false;
        }
        return true;
    }

    private void checkRepository(String key, List<Check> checks, String frozenBaseSha) {
        Repository repository = repositories.repositories().get(key);
        checks.add(checkVerificationPlan(key, repository));
        if (!probe.imageAvailable(repository.buildImage())) {
            add(checks, "build-image:" + key, Status.WARN,
                    "Build image is not local for " + key + "; Docker will need to pull it before baseline.");
        }

        String sourceSha = frozenBaseSha;
        if (sourceSha == null) {
            try {
                sourceSha = probe.sourceBase(repositories.gitBaseUrl(), repository.sourceRepo(), repository.baseBranch());
            } catch (IllegalStateException | IllegalArgumentException e) {
                add(checks, "source:" + key, Status.FAIL, "Cannot read the public source base for " + key + ".");
                return;
            }
            add(checks, "source:" + key, Status.PASS, "Public source base is readable for " + key + ".");
        }
        checks.add(checkDelivery(key, repository, sourceSha));
    }

    private Check checkVerificationPlan(String key, Repository repository) {
        try {
            settings.command(repository.verificationPlan());
            return new Check("verification:" + key, Status.PASS,
                    "Trusted verification command is configured for " + key + ".");
        } catch (IllegalStateException e) {
            return new Check("verification:" + key, Status.FAIL,
                    "Configure the trusted verification plan for " + key + ".");
        }
    }

    private Check checkDelivery(String key, Repository repository, String sourceSha) {
        String checkName = "delivery:" + key;
        try {
            DeliveryTarget target = delivery.requireTarget(key, repositories);
            if (!repository.baseBranch().equals(target.baseBranch())) {
                return new Check(checkName, Status.FAIL, "Delivery base must match the source base for " + key + ".");
            }
            if (!github.isConfigured()) {
                return new Check(checkName, Status.FAIL, "Configure a GitHub token with push permission for " + key + ".");
            }
            probe.requireDeliveryAccess(target, sourceSha);
            return new Check(checkName, Status.PASS,
                    "Token reports push permission and destination contains the source base for " + key + ".");
        } catch (RestClientException | IllegalStateException | IllegalArgumentException e) {
            return new Check(checkName, Status.FAIL,
                    "Check the delivery destination, GitHub token push permission and matching base for " + key + ".");
        }
    }

    private Map<String, Object> effectiveRuntime() {
        Map<String, Object> identity = new LinkedHashMap<>(coding.identity(null));
        identity.put("timeoutSeconds", settings.timeoutSeconds());
        identity.put("mavenCacheVolume", settings.mavenCacheVolume());
        if (usesClaudeCode()) {
            // The default run profile; every profile is listed with its checks under Run profiles.
            RunProfiles.Profile profile = profiles.defaultProfile();
            identity.put("api", settings.coding().claudeCode().api());
            identity.put("authentication", safeAuthentication(profile.authentication(), true));
            identity.put("maxTurns", profile.maxTurns());
            identity.put("maxOutputTokens", profile.maxOutputTokens());
        } else {
            DevRuntimeProperties.Pi profile = settings.coding().piProfile();
            identity.put("authentication", safeAuthentication(profile.authentication(), false));
            if (profile.api() != null) {
                identity.put("api", profile.api());
            }
            if (profile.maxOutputTokens() != null) {
                identity.put("maxOutputTokens", profile.maxOutputTokens());
            }
        }
        addEndpointOrigin(identity);
        return Map.copyOf(identity);
    }

    private static String safeAuthentication(String authentication, boolean supportsBearerToken) {
        if ("api-key".equals(authentication)) {
            return authentication;
        }
        if (supportsBearerToken && "bearer-token".equals(authentication)) {
            return authentication;
        }
        return "unset or invalid";
    }

    private void addEndpointOrigin(Map<String, Object> identity) {
        try {
            URI endpoint = endpoint(usesClaudeCode()
                    ? profiles.defaultProfile().baseUrl() : settings.coding().piProfile().baseUrl());
            if (endpoint != null) {
                URI origin = new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), null, null, null);
                identity.put("endpointOrigin", origin.toString());
            }
        } catch (IllegalArgumentException | URISyntaxException e) {
            identity.put("endpointOrigin", "unset or invalid");
        }
    }

    /**
     * Every Claude Code run profile with its own checks: secret set, endpoint reachable over TCP, rate card,
     * effort list. Empty with the Pi runtime, which has no profiles.
     */
    private List<Map<String, Object>> profileRows() {
        if (!usesClaudeCode()) {
            return List.of();
        }
        Map<String, Boolean> reachable = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RunProfiles.Profile profile : profiles.all()) {
            List<Check> checks = new ArrayList<>();
            boolean secret = profile.secret() != null && !profile.secret().isBlank();
            checks.add(secret ? new Check("secret", Status.PASS, profile.secretVariable() + " is set.")
                    : new Check("secret", Status.FAIL, profile.problem()));
            if (secret && profile.problem() != null) {
                checks.add(new Check("settings", Status.FAIL, profile.problem()));
            }
            String origin;
            try {
                origin = profile.endpointOrigin();
                boolean open = reachable.computeIfAbsent(origin, key -> probe.gatewayReachable(endpoint(key)));
                checks.add(new Check("endpoint", open ? Status.PASS : Status.WARN, open
                        ? origin + " is reachable over TCP from Factory; the API itself is not tested."
                        : origin + " is not reachable over TCP from Factory; check the base URL and the network."));
            } catch (IllegalArgumentException e) {
                origin = "unset or invalid";
                checks.add(new Check("endpoint", Status.FAIL, "The base URL must be an HTTP(S) URL without"
                        + " credentials, query or fragment."));
            }
            checks.add(pricing.rateFor(profile.model()).isPresent()
                    ? new Check("rate card", Status.PASS, "config/developer-pricing.yaml has a rate for "
                            + profile.model() + ".")
                    : new Check("rate card", Status.WARN, "No rate for " + profile.model()
                            + " in config/developer-pricing.yaml; runs work, but cost is not estimated."));
            boolean effortsValid = RunProfiles.EFFORTS.containsAll(profile.efforts())
                    && (profile.defaultEffort() == null || profile.efforts().contains(profile.defaultEffort()));
            checks.add(new Check("efforts", effortsValid ? Status.PASS : Status.FAIL, effortsValid
                    ? String.join(", ", profile.efforts()) + "; default " + RunProfiles.effortLabel(profile.defaultEffort())
                    : "The default effort is not in the effort list."));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", profile.id());
            row.put("label", profile.label());
            row.put("default", profile.id().equals(profiles.defaultProfile().id()));
            row.put("model", profile.model());
            row.put("endpointOrigin", origin);
            row.put("authentication", safeAuthentication(profile.authentication(), true));
            row.put("efforts", profile.efforts());
            row.put("effort", RunProfiles.effortLabel(profile.defaultEffort()));
            row.put("maxTurns", profile.maxTurns());
            row.put("maxOutputTokens", profile.maxOutputTokens());
            row.put("checks", List.copyOf(checks));
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private List<Map<String, Object>> effectiveRepositories() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Repository> entry : repositories.repositories().entrySet()) {
            Repository repository = entry.getValue();
            DeliveryTarget target = delivery.target(entry.getKey(), repositories);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", entry.getKey());
            row.put("source", repository.sourceRepo());
            row.put("baseBranch", repository.baseBranch());
            row.put("buildImage", repository.buildImage());
            row.put("verificationPlan", repository.verificationPlan());
            row.put("jiraProjects", repository.jiraProjects());
            row.put("delivery", deliveryRepository(target));
            rows.add(Map.copyOf(row));
        }
        return List.copyOf(rows);
    }

    private static String deliveryRepository(DeliveryTarget target) {
        if (target == null || target.repository() == null) {
            return "";
        }
        return target.repository();
    }

    private static boolean hasFailures(List<Check> checks) {
        return checks.stream().anyMatch(check -> check.status() == Status.FAIL);
    }

    private static String failureMessages(List<Check> checks) {
        return checks.stream()
                .filter(check -> check.status() == Status.FAIL)
                .map(Check::message)
                .collect(Collectors.joining("; "));
    }

    private static void add(List<Check> checks, String name, Status status, String message) {
        checks.add(new Check(name, status, message));
    }
}
