package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Delivers only the exact candidate authorized by the current successful receipt. */
public class DeliveryWorker implements AgentWorker {
    public static final String ID = "dev-deliver";
    public static final String DELIVERY = "dev_delivery.json";

    private final DevFactoryProperties repositories;
    private final DevDeliveryProperties properties;
    private final CandidateDelivery delivery;
    private final GitHubProperties githubProperties;
    private final GitHubConnector github;
    private final FrontmatterCodec frontmatter;
    private final JsonMapper json = JsonMapper.builder().build();

    public DeliveryWorker(DevFactoryProperties repositories, DevDeliveryProperties properties,
                          CandidateDelivery delivery, GitHubProperties githubProperties,
                          GitHubConnector github, FrontmatterCodec frontmatter) {
        this.repositories = repositories;
        this.properties = properties;
        this.delivery = delivery;
        this.githubProperties = githubProperties;
        this.github = github;
        this.frontmatter = frontmatter;
    }

    @Override public String id() { return ID; }

    @Override
    public AgentResult execute(AgentContext context) {
        var previous = json.readTree(context.requireInput(VerifyWorker.RESULT).content());
        if (!"VERIFIED".equals(previous.path("state").asString(""))) {
            return new AgentResult(Map.of(DELIVERY, json.writeValueAsString(Map.of(
                    "state", "NOT_RUN", "reason", "Candidate was not independently verified")),
                    VerifyWorker.RESULT, context.requireInput(VerifyWorker.RESULT).content()), Map.of());
        }
        Candidate candidate = json.readValue(context.requireInput(DevelopWorker.CANDIDATE).content(), Candidate.class);
        VerificationReceipt verification = json.readValue(
                context.requireInput(VerifyWorker.RECEIPT).content(), VerificationReceipt.class);
        var brief = frontmatter.parse(context.requireInput(IntakeResolveWorker.TASK_BRIEF).content()).metadata();
        String issueKey = brief.path("issue").path("key").asString("");
        String summary = brief.path("issue").path("summary").asString("");
        var repository = repositories.repositories().get(candidate.repository());
        if (repository == null) throw new IllegalStateException("Missing trusted repository: " + candidate.repository());
        var target = properties.requireTarget(candidate.repository());
        if (!githubProperties.isConfigured()) {
            return blocked(previous, candidate, "FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
        }
        String sourceUrl = repositories.gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
        var receipt = delivery.deliver(context.executionId().toString(), issueKey, summary, sourceUrl,
                candidate, verification, target, githubProperties.token());
        String prUrl = "";
        if (properties.createPullRequest()) {
            prUrl = github.findOpenPullRequest(target.repository(), receipt.branch(), target.baseBranch())
                    .orElseGet(() -> github.createPullRequest(target.repository(), receipt.branch(), target.baseBranch(),
                            issueKey + ": " + summary, pullRequestBody(candidate, receipt.commitSha())));
        }
        Map<String, Object> result = identity("DELIVERED", candidate);
        result.put("deliveryRepository", receipt.repository());
        result.put("deliveryBranch", receipt.branch());
        result.put("deliveryCommitSha", receipt.commitSha());
        result.put("pullRequestUrl", prUrl);
        return new AgentResult(Map.of(DELIVERY, json.writeValueAsString(result),
                VerifyWorker.RESULT, json.writeValueAsString(result)), Map.of());
    }

    private AgentResult blocked(tools.jackson.databind.JsonNode previous, Candidate candidate, String reason) {
        Map<String, Object> result = identity("DELIVERY_BLOCKED", candidate);
        result.put("reason", reason);
        result.put("verificationPlan", previous.path("verificationPlan").asString(""));
        return new AgentResult(Map.of(DELIVERY, json.writeValueAsString(result),
                VerifyWorker.RESULT, json.writeValueAsString(result)), Map.of());
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

    private static String pullRequestBody(Candidate candidate, String commit) {
        return "Factory Developer Flow verified the exact candidate in a fresh Docker workspace.\n\n"
                + "- Base: `" + candidate.baseSha() + "`\n"
                + "- Tree: `" + candidate.treeSha() + "`\n"
                + "- Patch SHA-256: `" + candidate.patchSha256() + "`\n"
                + "- Delivered commit: `" + commit + "`";
    }
}
