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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Delivers only the exact candidate authorized by the current successful receipt. */
public class DeliveryWorker implements AgentWorker {
    public static final String ID = "dev-deliver";
    public static final String DELIVERY = "dev_delivery.json";
    private static final Pattern DIFF_PATH = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern QUOTED_DIFF_PATH = Pattern.compile("^diff --git \"a/(.+)\" \"b/(.+)\"$");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern RUNTIME_CHECKS = Pattern.compile("(?i)\\bChecks passed:");
    private static final Pattern TOKEN = Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,})\\b");

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
        var body = new StringBuilder("## Summary\n\n")
                .append(prose(summary, 500)).append("\n\n");
        appendRuntimeSummary(body, outcome.path("summary").asString(""));
        int decisions = 0;
        for (var decision : request.path("confirmedDecisions")) {
            if (decisions++ >= 5) break;
            if (decisions == 1) body.append("## Confirmed decisions\n\n");
            body.append("- ").append(prose(decision.path("question").asString(""), 300))
                    .append(" — ").append(prose(decision.path("answer").asString(""), 500)).append("\n");
        }
        if (decisions > 0) body.append("\n");
        List<String> files = candidate.patch().lines().filter(line -> line.startsWith("diff --git "))
                .map(DeliveryWorker::changedPath).toList();
        body.append("## Changed files\n\n");
        files.stream().limit(20).forEach(path -> body.append("- `").append(code(path, 240)).append("`\n"));
        if (files.size() > 20) body.append("- Additional files are visible in the PR diff.\n");
        body.append("\n## Verification\n\n**").append(prose(verification.result(), 40)).append("** · ")
                .append(count(verification.testCount())).append(" tests · ")
                .append(count(verification.failureCount())).append(" failures · ")
                .append(count(verification.errorCount())).append(" errors\n\n")
                .append("- Plan: `").append(code(verification.planId(), 200)).append("`\n")
                .append("- Command: `").append(code(String.join(" ", verification.argv()), 1200)).append("`\n")
                .append("- Image: `").append(code(verification.image(), 300)).append("`\n\n")
                .append("<details>\n<summary>Factory verification details</summary>\n\n")
                .append("- Surefire reports: ").append(verification.surefireReportCount()).append("\n")
                .append("- Failsafe reports: ").append(verification.failsafeReportCount()).append("\n")
                .append("- Required suites: ").append(verification.requiredReports().isEmpty()
                        ? "No named suites configured; aggregate executed-test evidence only."
                        : prose(String.join(", ", verification.requiredReports()), 1600)).append("\n")
                .append("\nFactory independently verified the exact frozen candidate using the configured verification plan.\n")
                .append("</details>\n\n")
                .append("<details>\n<summary>Candidate and delivery details</summary>\n\n")
                .append("- Execution: `").append(code(verification.executionId(), 200)).append("`\n")
                .append("- Destination: `").append(code(delivery.repository(), 300)).append("` / `")
                .append(code(delivery.branch(), 300)).append("`\n");
        return body.append("- Base: `").append(code(candidate.baseSha(), 100)).append("`\n")
                .append("- Tree: `").append(code(candidate.treeSha(), 100)).append("`\n")
                .append("- Patch SHA-256: `").append(code(candidate.patchSha256(), 100)).append("`\n")
                .append("- Delivered commit: `").append(code(delivery.commitSha(), 100)).append("`\n")
                .append("</details>").toString();
    }

    private static void appendRuntimeSummary(StringBuilder body, String summary) {
        if (summary == null || summary.isBlank()) return;
        String descriptive = summary.substring(0, Math.min(summary.length(), 4000));
        var checks = RUNTIME_CHECKS.matcher(descriptive);
        if (checks.find()) descriptive = descriptive.substring(0, checks.start());
        List<String> lines = descriptive.lines().toList();
        boolean changes = false;
        var bullets = new java.util.ArrayList<String>();
        var fallback = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.equalsIgnoreCase("Changes:")) { changes = true; continue; }
            if (changes && line.startsWith("- ")) {
                String bullet = prose(line.substring(2), 240);
                if (!bullet.isBlank() && bullets.size() < 20) bullets.add(bullet);
            } else if (!changes && !line.isBlank()) {
                if (!fallback.isEmpty()) fallback.append(' ');
                fallback.append(line);
            }
        }
        if (!bullets.isEmpty()) {
            body.append("## Changes\n\n");
            bullets.forEach(bullet -> body.append("- ").append(bullet).append("\n"));
            body.append("\n");
        } else {
            String description = prose(fallback.toString(), 2000);
            if (!description.isBlank()) body.append(description).append("\n\n");
        }
    }

    private static String changedPath(String header) {
        var matcher = DIFF_PATH.matcher(header);
        if (!matcher.matches()) matcher = QUOTED_DIFF_PATH.matcher(header);
        return matcher.matches() ? matcher.group(2) : "Path visible in PR diff";
    }

    private static String count(Integer value) { return value == null ? "unknown" : value.toString(); }

    /** Dynamic inline code cannot close Factory's code span or expose credentials. */
    private static String code(String value, int limit) {
        String clean = redact(value).replaceAll("[\\p{Cntrl}\\s]+", " ").replace("`", "′")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit) + "…";
    }

    private static String redact(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]+", "$1 [REDACTED]");
        clean = MavenBaselineOutput.sanitize(clean);
        return TOKEN.matcher(clean).replaceAll("[REDACTED]");
    }

    /** Descriptive input cannot introduce Markdown structure, HTML, links or mentions. */
    private static String prose(String value, int limit) {
        String clean = URL.matcher(redact(value)).replaceAll("[link omitted]").replace("`", "")
                .replaceAll("[\\p{Cntrl}\\s]+", " ")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("#", "&#35;")
                .replace("@", "＠").replace("[", "&#91;").replace("]", "&#93;")
                .replace("*", "&#42;").replace("_", "&#95;")
                .replace("-", "&#45;").replace("+", "&#43;").replace("~", "&#126;")
                .replace("!", "&#33;").replace("|", "&#124;").replace("\\", "&#92;").strip();
        clean = clean.replaceFirst("^([0-9]{1,9})\\.(?=\\s)", "$1&#46;")
                .replaceFirst("^([0-9]{1,9})\\)(?=\\s)", "$1&#41;");
        return clean.length() <= limit ? clean : clean.substring(0, limit) + "…";
    }
}
