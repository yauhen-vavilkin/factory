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
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryReceipt;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Delivers only the exact candidate authorized by the current successful receipt. */
public class DeliveryWorker implements AgentWorker {
    public static final String ID = "dev-deliver";
    public static final String DELIVERY = "dev_delivery.json";

    private final DevFactoryProperties repositories;
    private final DevRuntimeProperties runtime;
    private final DevDeliveryProperties properties;
    private final CandidateDelivery delivery;
    private final GitHubProperties githubProperties;
    private final GitHubConnector github;
    private final FrontmatterCodec frontmatter;
    private final JsonMapper json = JsonMapper.builder().build();

    public DeliveryWorker(DevFactoryProperties repositories, DevRuntimeProperties runtime, DevDeliveryProperties properties,
                          CandidateDelivery delivery, GitHubProperties githubProperties,
                          GitHubConnector github, FrontmatterCodec frontmatter) {
        this.repositories = repositories;
        this.runtime = runtime;
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
        try {
            var target = properties.requireTarget(candidate.repository());
            if (!repository.verificationPlan().equals(verification.planId())
                    || !repository.buildImage().equals(verification.image())
                    || !runtime.command(repository.verificationPlan()).equals(verification.argv())
                    || !runtime.requiredReports(repository.verificationPlan()).equals(verification.requiredReports())) {
                return blocked(previous, candidate, "Verification receipt no longer matches the current Factory plan; reverify the candidate");
            }
            if (!githubProperties.isConfigured()) {
                return blocked(previous, candidate, "FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
            }
            String sourceUrl = repositories.gitBaseUrl() + "/" + repository.sourceRepo() + ".git";
            var receipt = delivery.deliver(context.executionId().toString(), issueKey, summary, sourceUrl,
                    candidate, verification, target, githubProperties.token());
            String prUrl = "";
            if (properties.createPullRequest()) {
                try {
                    prUrl = github.findOpenPullRequest(target.repository(), receipt.branch(), target.baseBranch())
                            .orElseGet(() -> github.createPullRequest(target.repository(), receipt.branch(), target.baseBranch(),
                                    issueKey + ": " + summary, pullRequestBody(context, summary, candidate, verification, receipt)));
                } catch (HttpClientErrorException e) {
                    int status = e.getStatusCode().value();
                    if (status != 408 && status != 429) {
                        return blocked(previous, candidate,
                                "Candidate branch and commit delivered; GitHub blocked pull request creation (HTTP " + status + ")",
                                receipt);
                    }
                    throw e;
                }
            }
            Map<String, Object> result = identity("DELIVERED", candidate);
            deliveredIdentity(result, receipt);
            result.put("pullRequestUrl", prUrl);
            return new AgentResult(Map.of(DELIVERY, json.writeValueAsString(result),
                    VerifyWorker.RESULT, json.writeValueAsString(result)), Map.of());
        } catch (DeliveryBlockedException e) {
            return blocked(previous, candidate, e.getMessage());
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status != 408 && status != 429) {
                return blocked(previous, candidate, "GitHub rejected delivery configuration or destination (HTTP " + status + ")");
            }
            throw e;
        }
    }

    private AgentResult blocked(tools.jackson.databind.JsonNode previous, Candidate candidate, String reason) {
        return blocked(previous, candidate, reason, null);
    }

    private AgentResult blocked(tools.jackson.databind.JsonNode previous, Candidate candidate, String reason,
                                DeliveryReceipt receipt) {
        Map<String, Object> result = identity("DELIVERY_BLOCKED", candidate);
        result.put("reason", reason);
        result.put("verificationPlan", previous.path("verificationPlan").asString(""));
        if (receipt != null) {
            deliveredIdentity(result, receipt);
            result.put("pullRequestState", "BLOCKED");
        }
        return new AgentResult(Map.of(DELIVERY, json.writeValueAsString(result),
                VerifyWorker.RESULT, json.writeValueAsString(result)), Map.of());
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

    private String pullRequestBody(AgentContext context, String summary, Candidate candidate,
                                   VerificationReceipt verification, DeliveryReceipt delivery) {
        var requestArtifact = context.inputs().get("dev_coding_request.json");
        var outcomeArtifact = context.inputs().get("dev_coding_outcome.json");
        var request = requestArtifact == null ? json.createObjectNode() : json.readTree(requestArtifact.content());
        var outcome = outcomeArtifact == null ? json.createObjectNode() : json.readTree(outcomeArtifact.content());
        var body = new StringBuilder("## What changed and why\n\n")
                .append(prose(summary, 500)).append("\n\n");
        String description = prose(request.path("description").asString(""), 1600);
        if (!description.isBlank()) body.append("Jira context: ").append(description).append("\n\n");
        String runtimeSummary = prose(outcome.path("summary").asString(""), 2000);
        body.append(runtimeSummary.isBlank() ? "Implementation summary unavailable; review the frozen diff below."
                : "Coding runtime summary (descriptive, not verification evidence): " + runtimeSummary).append("\n\n");
        int decisions = 0;
        for (var decision : request.path("confirmedDecisions")) {
            if (decisions++ >= 5) break;
            body.append("- Confirmed decision: ").append(prose(decision.path("question").asString(""), 300))
                    .append(" — ").append(prose(decision.path("answer").asString(""), 500)).append("\n");
        }
        var files = candidate.patch().lines().filter(line -> line.startsWith("diff --git ")).toList();
        body.append("\nFrozen diff: ").append(files.size()).append(" changed file(s).\n");
        files.stream().limit(20).forEach(line -> body.append("- ").append(prose(line.substring(11), 240)).append("\n"));
        if (files.size() > 20) body.append("- Additional files are visible in the PR diff.\n");
        body.append("\n## Independent Factory verification\n\n")
                .append("Factory verified this exact candidate in a fresh Docker workload.\n\n")
                .append("- Plan: ").append(prose(verification.planId(), 200)).append("\n")
                .append("- Command argv: ").append(prose(json.writeValueAsString(verification.argv()), 1200)).append("\n")
                .append("- Result: ").append(verification.result()).append("; exit ").append(verification.exitCode())
                .append("; executed tests: ").append(count(verification.testCount()))
                .append("; failures: ").append(count(verification.failureCount()))
                .append("; errors: ").append(count(verification.errorCount())).append(".\n")
                .append("- Reports: Surefire ").append(verification.surefireReportCount())
                .append("; Failsafe ").append(verification.failsafeReportCount()).append(".\n")
                .append("- Required report suites: ").append(verification.requiredReports().isEmpty()
                        ? "No named suites configured; aggregate executed-test evidence only."
                        : prose(String.join(", ", verification.requiredReports()), 1600)).append("\n")
                .append("- Image: ").append(prose(verification.image(), 300)).append("\n\n")
                .append("Only the configured plan and its collected evidence were verified. Runtime self-checks are not independent verification. ")
                .append("Passing tests do not establish every Jira requirement; manual acceptance and checks outside this plan remain unverified.\n\n")
                .append("## Candidate and delivery identity\n\n")
                .append("- Execution: `").append(verification.executionId()).append("`\n")
                .append("- Destination: `").append(delivery.repository()).append("` / `").append(delivery.branch()).append("`\n");
        return body.append("- Base: `").append(candidate.baseSha()).append("`\n")
                .append("- Tree: `").append(candidate.treeSha()).append("`\n")
                .append("- Patch SHA-256: `").append(candidate.patchSha256()).append("`\n")
                .append("- Delivered commit: `").append(delivery.commitSha()).append("`").toString();
    }

    private static String count(Integer value) { return value == null ? "unknown" : value.toString(); }

    /** Descriptive input cannot introduce Markdown structure, HTML or mentions into trusted prose. */
    private static String prose(String value, int limit) {
        String clean = value == null ? "" : MavenBaselineOutput.sanitize(value
                .replaceAll("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]+", "$1 [REDACTED]"))
                .replaceAll("[\\p{Cntrl}\\s]+", " ")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("@", "＠").replaceAll("([\\\\`*_{}\\[\\]()#+!|])", "\\\\$1").strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit) + "…";
    }
}
