package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryReceipt;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.PullRequestRenderer;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.verification.VerificationPolicy;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.springframework.web.client.HttpClientErrorException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Delivers only the exact candidate authorized by the current successful receipt. */
public class DeliveryWorker implements AgentWorker {
    public static final String ID = "dev-deliver";
    public static final String DELIVERY = "dev_delivery.json";
    private final DevFactoryProperties repositories;
    private final VerificationPolicy verificationPolicy;
    private final PullRequestRenderer renderer = new PullRequestRenderer();
    private final DevDeliveryProperties properties;
    private final CandidateDelivery delivery;
    private final GitHubProperties githubProperties;
    private final GitHubConnector github;
    private final FrontmatterCodec frontmatter;
    private final StateManager executionState;
    private final JsonMapper json = JsonMapper.builder().build();

    public DeliveryWorker(
            DevFactoryProperties repositories,
            DevRuntimeProperties runtime,
            DevDeliveryProperties properties,
            CandidateDelivery delivery,
            GitHubProperties githubProperties,
            GitHubConnector github,
            FrontmatterCodec frontmatter,
            StateManager executionState) {
        this.repositories = repositories;
        this.verificationPolicy = new VerificationPolicy(repositories, runtime);
        this.executionState = executionState;
        this.properties = properties;
        this.delivery = delivery;
        this.githubProperties = githubProperties;
        this.github = github;
        this.frontmatter = frontmatter;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        var previous = json.readTree(context.requireInput(VerifyWorker.RESULT).content());
        if (!"VERIFIED".equals(previous.path("state").asString(""))) {
            return new AgentResult(
                    Map.of(
                            DELIVERY,
                            json.writeValueAsString(
                                    Map.of(
                                            "state",
                                            "NOT_RUN",
                                            "reason",
                                            "Candidate was not independently verified")),
                            VerifyWorker.RESULT,
                            context.requireInput(VerifyWorker.RESULT).content()),
                    Map.of());
        }
        Candidate candidate =
                json.readValue(
                        context.requireInput(DevelopWorker.CANDIDATE).content(), Candidate.class);
        VerificationReceipt verification =
                json.readValue(
                        context.requireInput(VerifyWorker.RECEIPT).content(),
                        VerificationReceipt.class);
        var brief =
                frontmatter
                        .parse(context.requireInput(IntakeResolveWorker.TASK_BRIEF).content())
                        .metadata();
        String issueKey = brief.path("issue").path("key").asString("");
        String summary = brief.path("issue").path("summary").asString("");
        var repository = repositories.repositories().get(candidate.repository());
        if (repository == null)
            throw new IllegalStateException(
                    "Missing trusted repository: " + candidate.repository());
        try {
            var target = properties.requireTarget(candidate.repository(), repositories);
            if (!verificationPolicy.matches(candidate, verification)) {
                return blocked(
                        previous,
                        candidate,
                        "Verification receipt no longer matches the current Factory plan; reverify"
                                + " the candidate");
            }
            if (!githubProperties.isConfigured()) {
                return blocked(previous, candidate, "FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
            }
            String sourceUrl = repositories.gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
            boolean feedbackVersion =
                    FeedbackArtifacts.VERSION.equals(
                            executionState.get(context.executionId()).getFlowVersion());
            var receipt =
                    feedbackVersion
                            ? delivery.deliver(
                                    context.executionId().toString(),
                                    issueKey,
                                    summary,
                                    sourceUrl,
                                    candidate,
                                    verification,
                                    target,
                                    githubProperties.token(),
                                    true)
                            : delivery.deliver(
                                    context.executionId().toString(),
                                    issueKey,
                                    summary,
                                    sourceUrl,
                                    candidate,
                                    verification,
                                    target,
                                    githubProperties.token());
            String prUrl = "";
            String prTitle = "";
            if (properties.createPullRequest()) {
                try {
                    var existing =
                            github.findOpenPullRequest(
                                    target.repository(), receipt.branch(), target.baseBranch());
                    if (existing.isPresent()) {
                        prUrl = existing.get();
                    } else {
                        verification.requireVerified(context.executionId().toString(), candidate);
                        if (!verificationPolicy.matches(candidate, verification))
                            return blocked(
                                    previous,
                                    candidate,
                                    "Verification receipt no longer matches the current Factory"
                                            + " plan; reverify the candidate",
                                    receipt);
                        prTitle = issueKey + ": " + summary;
                        prUrl =
                                github.createPullRequest(
                                        target.repository(),
                                        receipt.branch(),
                                        target.baseBranch(),
                                        prTitle,
                                        renderer.render(
                                                summary,
                                                candidate,
                                                verification,
                                                receipt,
                                                inputJson(context, "dev_coding_request.json"),
                                                inputJson(context, "dev_coding_outcome.json")));
                    }
                } catch (HttpClientErrorException e) {
                    int status = e.getStatusCode().value();
                    if (status != 408 && status != 429) {
                        return blocked(
                                previous,
                                candidate,
                                "Candidate branch and commit delivered; GitHub blocked pull request"
                                        + " creation (HTTP "
                                        + status
                                        + ")",
                                receipt);
                    }
                    throw e;
                }
            }
            Map<String, Object> result = identity("DELIVERED", candidate);
            deliveredIdentity(result, receipt);
            result.put("pullRequestUrl", prUrl);
            if (!prTitle.isBlank()) result.put("pullRequestTitle", prTitle);
            return new AgentResult(
                    Map.of(
                            DELIVERY,
                            json.writeValueAsString(result),
                            VerifyWorker.RESULT,
                            json.writeValueAsString(result)),
                    Map.of());
        } catch (DeliveryBlockedException e) {
            return blocked(previous, candidate, e.getMessage());
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status != 408 && status != 429) {
                return blocked(
                        previous,
                        candidate,
                        "GitHub rejected delivery configuration or destination (HTTP "
                                + status
                                + ")");
            }
            throw e;
        }
    }

    private AgentResult blocked(JsonNode previous, Candidate candidate, String reason) {
        return blocked(previous, candidate, reason, null);
    }

    private AgentResult blocked(
            JsonNode previous, Candidate candidate, String reason, DeliveryReceipt receipt) {
        Map<String, Object> result = identity("DELIVERY_BLOCKED", candidate);
        result.put("reason", reason);
        result.put("verificationPlan", previous.path("verificationPlan").asString(""));
        if (receipt != null) {
            deliveredIdentity(result, receipt);
            result.put("pullRequestState", "BLOCKED");
        }
        return new AgentResult(
                Map.of(
                        DELIVERY,
                        json.writeValueAsString(result),
                        VerifyWorker.RESULT,
                        json.writeValueAsString(result)),
                Map.of());
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
}
