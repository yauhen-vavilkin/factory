package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryReceipt;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.PublicationGit;
import org.folio.factory.devfactory.delivery.PullRequestRenderer;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.verification.VerificationPolicy;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.springframework.web.client.HttpClientErrorException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Delivers only the exact candidate authorized by the current successful receipt. */
public class DeliveryWorker implements AgentWorker {
    public static final String ID = "dev-deliver";
    public static final String DELIVERY = "dev_delivery.json";
    private static final String PLAN_CHANGED =
            "Verification receipt no longer matches the current Factory plan; reverify the candidate";
    private final DevFactoryProperties repositories;
    private final VerificationPolicy verificationPolicy;
    private final PullRequestRenderer renderer = new PullRequestRenderer();
    private final DevDeliveryProperties properties;
    private final CandidateDelivery delivery;
    private final GitHubProperties githubProperties;
    private final JiraProperties jiraProperties;
    private final GitHubConnector github;
    private final FrontmatterCodec frontmatter;
    private final StateManager executionState;
    private final TaskAttemptPolicy taskPolicy;
    private final JsonMapper json = JsonMapper.builder().build();

    public DeliveryWorker(
            DevFactoryProperties repositories,
            DevRuntimeProperties runtime,
            DevDeliveryProperties properties,
            CandidateDelivery delivery,
            GitHubProperties githubProperties,
            JiraProperties jiraProperties,
            GitHubConnector github,
            FrontmatterCodec frontmatter,
            StateManager executionState) {
        this(repositories, runtime, properties, delivery, githubProperties, jiraProperties, github,
                frontmatter, executionState, TaskAttemptPolicy.none());
    }

    public DeliveryWorker(
            DevFactoryProperties repositories,
            DevRuntimeProperties runtime,
            DevDeliveryProperties properties,
            CandidateDelivery delivery,
            GitHubProperties githubProperties,
            JiraProperties jiraProperties,
            GitHubConnector github,
            FrontmatterCodec frontmatter,
            StateManager executionState,
            TaskAttemptPolicy taskPolicy) {
        this.repositories = repositories;
        this.verificationPolicy = new VerificationPolicy(repositories, runtime);
        this.executionState = executionState;
        this.properties = properties;
        this.delivery = delivery;
        this.githubProperties = githubProperties;
        this.jiraProperties = jiraProperties;
        this.github = github;
        this.frontmatter = frontmatter;
        this.taskPolicy = taskPolicy;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        var previous = json.readTree(context.requireInput(VerifyWorker.RESULT).content());
        if (!"VERIFIED".equals(previous.path("state").asString(""))) {
            return notVerified(context);
        }
        var inputs = readInputs(context, previous);
        var sourceUrl = sourceUrl(inputs.candidate());
        var stop = taskStop(context);
        if (stop != null) {
            return blocked(inputs, stop);
        }
        var gitTimeoutSeconds = stageTimeoutSeconds(context);
        try {
            return deliver(context, inputs, sourceUrl, gitTimeoutSeconds);
        } catch (DeliveryBlockedException e) {
            return blocked(inputs, e.getMessage());
        } catch (HttpClientErrorException e) {
            rethrowIfRetryable(e);
            return blocked(inputs, "GitHub rejected delivery configuration or destination (HTTP "
                    + e.getStatusCode().value() + ")");
        }
    }

    private AgentResult notVerified(AgentContext context) {
        var notRun = Map.of("state", "NOT_RUN", "reason", "Candidate was not independently verified");
        return new AgentResult(
                Map.of(DELIVERY, json.writeValueAsString(notRun),
                        VerifyWorker.RESULT, context.requireInput(VerifyWorker.RESULT).content()),
                Map.of());
    }

    private Inputs readInputs(AgentContext context, JsonNode previous) {
        var candidate = json.readValue(context.requireInput(DevelopWorker.CANDIDATE).content(), Candidate.class);
        var verification = json.readValue(
                context.requireInput(VerifyWorker.RECEIPT).content(), VerificationReceipt.class);
        var issue = frontmatter.parse(context.requireInput(IntakeResolveWorker.TASK_BRIEF).content())
                .metadata()
                .path("issue");
        return new Inputs(previous, candidate, verification,
                issue.path("key").asString(""), issue.path("summary").asString(""));
    }

    private String sourceUrl(Candidate candidate) {
        var repository = repositories.repositories().get(candidate.repository());
        if (repository == null) {
            throw new IllegalStateException("Missing trusted repository: " + candidate.repository());
        }
        return repositories.gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
    }

    private AgentResult deliver(AgentContext context, Inputs inputs, String sourceUrl, int gitTimeoutSeconds) {
        var candidate = inputs.candidate();
        var target = properties.requireTarget(candidate.repository(), repositories);
        if (!verificationPolicy.matches(candidate, inputs.verification())) {
            return blocked(inputs, PLAN_CHANGED);
        }
        if (!githubProperties.isConfigured()) {
            return blocked(inputs, "FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
        }
        var consumedRequest = consumedRequest(context);
        taskPolicy.beforePublication(context.executionId(), consumedRequest, target);
        var feedbackVersion = FeedbackArtifacts.VERSION.equals(
                executionState.get(context.executionId()).getFlowVersion());
        recordPublicationIntent(context, inputs, target, feedbackVersion);
        var receipt = delivery.deliver(context.executionId().toString(), inputs.issueKey(), inputs.summary(),
                sourceUrl, candidate, inputs.verification(), target, githubProperties.token(), feedbackVersion,
                gitTimeoutSeconds);
        if (!properties.createPullRequest()) {
            return delivered(inputs, receipt);
        }
        return publishPullRequest(context, inputs, new Publication(target, receipt, consumedRequest));
    }

    private void recordPublicationIntent(
            AgentContext context, Inputs inputs, DeliveryTarget target, boolean feedbackVersion) {
        if (taskPolicy.find(context.executionId()).isEmpty()) {
            return;
        }
        var candidate = inputs.candidate();
        var branch = CandidateDelivery.branchFor(
                context.executionId().toString(), inputs.issueKey(), candidate, feedbackVersion);
        taskPolicy.recordPublicationIntent(context.executionId(), new TaskAttemptPolicy.PublicationIntent(
                target.repository(), target.baseBranch(), branch,
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256()));
    }

    private AgentResult publishPullRequest(AgentContext context, Inputs inputs, Publication publication) {
        // The same computation for a new and an existing pull request, so a replay records the same flag.
        var outcome = inputJson(context, "dev_coding_outcome.json");
        var draftReasons = PullRequestRenderer.draftReasons(outcome);
        var target = publication.target();
        var receipt = publication.receipt();
        try {
            var existing = github.findPullRequests(target.repository(), receipt.branch(), target.baseBranch());
            if (existing.size() > 1) {
                return blocked(inputs, "Multiple existing pull requests match the delivered head identity;"
                        + " inspect the repository before publishing", receipt);
            }
            if (existing.size() == 1) {
                return reuseExisting(inputs, receipt, existing.get(0), draftReasons);
            }
            return createPullRequest(context, inputs, publication, outcome, draftReasons);
        } catch (HttpClientErrorException e) {
            rethrowIfRetryable(e);
            return blocked(inputs, "Candidate branch and commit delivered; GitHub blocked pull request creation"
                    + " (HTTP " + e.getStatusCode().value() + ")", receipt);
        }
    }

    private AgentResult reuseExisting(Inputs inputs, DeliveryReceipt receipt,
                                      GitHubConnector.PullRequest existing, List<String> draftReasons) {
        if (!receipt.commitSha().equals(existing.headSha())) {
            return blocked(inputs, "The existing pull request head no longer matches the delivered commit;"
                    + " inspect the repository before publishing", receipt);
        }
        return delivered(inputs, receipt, new PullRequestOutcome(existing.htmlUrl(), "", draftReasons));
    }

    private AgentResult createPullRequest(AgentContext context, Inputs inputs, Publication publication,
                                          JsonNode outcome, List<String> draftReasons) {
        var candidate = inputs.candidate();
        var verification = inputs.verification();
        var receipt = publication.receipt();
        verification.requireVerified(context.executionId().toString(), candidate);
        if (!verificationPolicy.matches(candidate, verification)) {
            return blocked(inputs, PLAN_CHANGED, receipt);
        }
        taskPolicy.beforePublication(context.executionId(), publication.consumedRequest(), publication.target());
        var title = inputs.issueKey() + ": " + inputs.summary();
        var body = renderer.render(inputs.issueKey(), jiraProperties.baseUrl(), candidate, verification, receipt,
                inputJson(context, "dev_coding_request.json"), outcome);
        var url = github.createPullRequest(publication.target().repository(), receipt.branch(),
                publication.target().baseBranch(), title, body, !draftReasons.isEmpty());
        return delivered(inputs, receipt, new PullRequestOutcome(url, title, draftReasons));
    }

    /** Timeouts and rate limits are retried by the engine; every other client error blocks the delivery. */
    private static void rethrowIfRetryable(HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        if (status == 408 || status == 429) {
            throw e;
        }
    }

    private AgentResult delivered(Inputs inputs, DeliveryReceipt receipt) {
        var result = deliveredResult(inputs, receipt);
        result.put("pullRequestUrl", "");
        return toResult(result);
    }

    private AgentResult delivered(Inputs inputs, DeliveryReceipt receipt, PullRequestOutcome pullRequest) {
        var result = deliveredResult(inputs, receipt);
        result.put("pullRequestUrl", pullRequest.url());
        if (!pullRequest.title().isBlank()) {
            result.put("pullRequestTitle", pullRequest.title());
        }
        result.put("pullRequestDraft", !pullRequest.draftReasons().isEmpty());
        result.put("draftReasons", pullRequest.draftReasons());
        return toResult(result);
    }

    private static Map<String, Object> deliveredResult(Inputs inputs, DeliveryReceipt receipt) {
        var result = identity("DELIVERED", inputs.candidate());
        deliveredIdentity(result, receipt);
        return result;
    }

    private AgentResult blocked(Inputs inputs, String reason) {
        return blocked(inputs, reason, null);
    }

    private AgentResult blocked(Inputs inputs, String reason, DeliveryReceipt receipt) {
        var result = identity("DELIVERY_BLOCKED", inputs.candidate());
        result.put("reason", reason);
        result.put("verificationPlan", inputs.previous().path("verificationPlan").asString(""));
        if (receipt != null) {
            deliveredIdentity(result, receipt);
            result.put("pullRequestState", "BLOCKED");
        }
        return toResult(result);
    }

    private AgentResult toResult(Map<String, Object> result) {
        var content = json.writeValueAsString(result);
        return new AgentResult(Map.of(DELIVERY, content, VerifyWorker.RESULT, content), Map.of());
    }

    /** A stopped, expired or no-longer-owned task attempt must not publish. */
    private String taskStop(AgentContext context) {
        if (taskPolicy.find(context.executionId()).isEmpty()) {
            return null;
        }
        if (!taskPolicy.ownsActiveSlot(context.executionId())) {
            String reason = "the task attempt no longer owns the installation's active slot;"
                    + " a stopped, superseded or released attempt must not publish";
            taskPolicy.recordStop(context.executionId(), context.stepId(), reason);
            return reason;
        }
        var expired = taskPolicy.deadline(context.executionId())
                .filter(deadline -> deadline.expired(Clock.systemUTC()));
        if (expired.isPresent()) {
            String reason = "the whole-attempt deadline expired before publication;"
                    + " queue and human-review waiting are excluded from the budget";
            taskPolicy.recordStop(context.executionId(), context.stepId(), reason);
            return reason;
        }
        return null;
    }

    private int stageTimeoutSeconds(AgentContext context) {
        return taskPolicy.deadline(context.executionId())
                .map(budget -> budget.capSeconds(Clock.systemUTC(),
                        PublicationGit.DEFAULT_TIMEOUT_SECONDS))
                .orElse(PublicationGit.DEFAULT_TIMEOUT_SECONDS);
    }

    private static void deliveredIdentity(Map<String, Object> result, DeliveryReceipt receipt) {
        result.put("deliveryRepository", receipt.repository());
        result.put("deliveryBranch", receipt.branch());
        result.put("deliveryCommitSha", receipt.commitSha());
    }

    private static Map<String, Object> identity(String state, Candidate candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("repository", candidate.repository());
        result.put("baseSha", candidate.baseSha());
        result.put("treeSha", candidate.treeSha());
        result.put("patchSha256", candidate.patchSha256());
        return result;
    }

    private JsonNode inputJson(AgentContext context, String name) {
        var artifact = context.inputs().get(name);
        return artifact == null ? json.createObjectNode() : json.readTree(artifact.content());
    }

    private CodingRequest consumedRequest(AgentContext context) {
        var artifact = context.inputs().get(IntakeResolveWorker.CODING_REQUEST);
        return artifact == null ? null : json.readValue(artifact.content(), CodingRequest.class);
    }

    private record Inputs(JsonNode previous, Candidate candidate, VerificationReceipt verification,
                          String issueKey, String summary) {
    }

    private record Publication(DeliveryTarget target, DeliveryReceipt receipt, CodingRequest consumedRequest) {
    }

    private record PullRequestOutcome(String url, String title, List<String> draftReasons) {
    }
}
