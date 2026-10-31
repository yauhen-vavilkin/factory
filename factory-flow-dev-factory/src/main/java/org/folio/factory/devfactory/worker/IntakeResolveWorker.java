package org.folio.factory.devfactory.worker;

import lombok.RequiredArgsConstructor;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.decision.DecisionArtifacts;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Answer;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Request;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.tasks.TaskExecutionContext;
import org.folio.factory.devfactory.repository.BaseRefResolver;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.runtime.CodingRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces the trusted task brief: applies the validated decision (if any), then
 * pins the configured base branch to a full SHA. {@code INTAKE_READY} means the
 * issue and trusted configuration are sufficient; it says nothing about the
 * build environment. Task executions never resolve a required repository
 * decision, so the seeded recommendation cannot be accepted without a human.
 */
@RequiredArgsConstructor
public class IntakeResolveWorker implements AgentWorker {

    public static final String ID = "dev-intake-resolve";
    public static final String TASK_BRIEF = "dev_task_brief.md";
    public static final String CODING_REQUEST = "dev_coding_request.json";

    static final String INTAKE_READY = "INTAKE_READY";

    private final RepositoryPolicy policy;
    private final BaseRefResolver baseRefResolver;
    private final FrontmatterCodec codec;
    private final TaskAttemptPolicy taskPolicy;
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        var intake = codec.parse(context.requireInput(IntakeWorker.INTAKE).content()).metadata();
        var state = intake.path("state").asString("");
        if (!IntakeWorker.MAPPED.equals(state)) {
            return brief(intake, state, intake.path("reason").asString(""), null, null, null, null);
        }
        var request = DecisionArtifacts.parseRequest(codec, context.requireInput(DecisionArtifacts.REQUEST).content());
        var taskRun = taskPolicy.find(context.executionId());
        if (request.required() && taskRun.isPresent()) {
            taskPolicy.recordStop(context.executionId(), context.stepId(),
                    "repository selection requires a human choice; the approved admission must resolve to"
                            + " one repository");
            return brief(intake, "BLOCKED", "REPOSITORY_SELECTION_REQUIRES_HUMAN", Map.of(), null, null, null);
        }
        var choice = request.required()
                ? validAnswer(context, request).choice()
                : intake.path("candidates").path(0).asString("");
        // The settings chosen at Run or Retry; a run without them (manual API trigger) uses the defaults.
        var settings = taskRun.map(TaskExecutionContext::runSettings).orElse(null);
        return briefForChoice(intake, decision(request, choice), choice, settings);
    }

    private static Map<String, Object> decision(Request request, String choice) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("required", request.required());
        if (request.required()) {
            decision.put("request_id", request.requestId());
            decision.put("choice", choice);
            decision.put("recommended", request.recommended());
            decision.put("question", request.question());
        }
        return decision;
    }

    private AgentResult briefForChoice(JsonNode intake, Map<String, Object> decision, String choice,
                                       CodingRequest.RunSettings settings) {
        var repository = policy.find(choice);
        if (repository.isEmpty()) {
            return brief(intake, "BLOCKED", "REPOSITORY_NOT_CONFIGURED", decision, null, null, settings);
        }
        var baseSha = baseRefResolver.resolve(repository.get().sourceRepo(), repository.get().baseBranch());
        if (baseSha.isEmpty()) {
            return brief(intake, "BLOCKED", "BASE_BRANCH_NOT_FOUND", decision, choice, null, settings);
        }
        return brief(intake, INTAKE_READY, null, decision, choice, baseSha.get(), settings);
    }

    private Answer validAnswer(AgentContext context, Request request) {
        Answer answer;
        try {
            answer = DecisionArtifacts.parseAnswer(codec, context.requireInput(DecisionArtifacts.ANSWER).content());
        } catch (RuntimeException e) {
            throw new AgentExecutionException("Decision answer is invalid: " + e.getMessage(), e);
        }
        if (!answer.requestId().equals(request.requestId())) {
            throw new AgentExecutionException("Decision answer is stale: it answers request "
                    + answer.requestId() + " but the current request is " + request.requestId());
        }
        if (!request.offers(answer.choice())) {
            throw new AgentExecutionException("Decision answer choice '" + answer.choice()
                    + "' is not one of the offered options");
        }
        return answer;
    }

    private AgentResult brief(JsonNode intake, String state, String reason, Map<String, Object> decision,
                              String repositoryKey, String baseSha, CodingRequest.RunSettings settings) {
        var shownReason = reason == null || reason.isEmpty() ? null : reason;
        var metadata = briefMetadata(intake, state, shownReason, decision, repositoryKey, baseSha);
        var body = briefBody(intake, state, shownReason, repositoryKey, baseSha);
        var request = codingRequest(intake, decision, repositoryKey, baseSha, settings);
        return new AgentResult(Map.of(TASK_BRIEF, codec.render(metadata, body),
                CODING_REQUEST, json.writeValueAsString(request)), Map.of());
    }

    private Map<String, Object> briefMetadata(JsonNode intake, String state, String reason,
                                              Map<String, Object> decision, String repositoryKey, String baseSha) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", state);
        metadata.put("reason", reason);
        metadata.put("issue_key", intake.path("issue_key").asString(""));
        if (baseSha != null) {
            metadata.put("repository", repositoryMetadata(repositoryKey, baseSha));
        }
        metadata.put("decision", decision);
        metadata.put("issue", intake.path("issue"));
        return metadata;
    }

    private Map<String, Object> repositoryMetadata(String repositoryKey, String baseSha) {
        var repo = policy.find(repositoryKey).orElseThrow();
        Map<String, Object> repository = new LinkedHashMap<>();
        repository.put("key", repositoryKey);
        repository.put("source_repo", repo.sourceRepo());
        repository.put("base_branch", repo.baseBranch());
        repository.put("base_sha", baseSha);
        repository.put("build_image", repo.buildImage());
        repository.put("verification_plan", repo.verificationPlan());
        return repository;
    }

    private static String briefBody(JsonNode intake, String state, String reason, String repositoryKey,
                                    String baseSha) {
        var body = new StringBuilder("# Developer task brief: ")
                .append(intake.path("issue_key").asString("")).append("\n\nState: **").append(state).append("**");
        if (reason != null) {
            body.append(" (").append(reason).append(')');
        }
        body.append("\n\n");
        if (baseSha != null) {
            body.append("Repository `").append(repositoryKey).append("` at `").append(baseSha).append("`.\n\n");
        }
        body.append("Build environment readiness has not been checked yet.\n");
        return body.toString();
    }

    private CodingRequest codingRequest(JsonNode intake, Map<String, Object> decision,
                                        String repositoryKey, String baseSha, CodingRequest.RunSettings settings) {
        var issue = intake.path("issue");
        var comments = comments(issue);
        var links = linkedIssues(issue);
        var decisions = confirmedDecisions(decision);
        var target = repositoryTarget(repositoryKey, baseSha);
        return new CodingRequest(intake.path("issue_key").asString(""), issue.path("summary").asString(""),
                issue.path("description").asString(""), comments, links, List.of(), decisions, target,
                CodingRequest.Constraints.defaults(), settings);
    }

    private static List<CodingRequest.Comment> comments(JsonNode issue) {
        List<CodingRequest.Comment> comments = new ArrayList<>();
        issue.path("comments").forEach(comment -> comments.add(new CodingRequest.Comment(
                comment.path("author").asString(""), comment.path("created").asString(""),
                comment.path("body").asString(""))));
        return comments;
    }

    private static List<CodingRequest.LinkedIssue> linkedIssues(JsonNode issue) {
        List<CodingRequest.LinkedIssue> links = new ArrayList<>();
        issue.path("links").forEach(link -> links.add(new CodingRequest.LinkedIssue(
                link.path("relation").asString(""), link.path("key").asString(""),
                link.path("summary").asString(""), link.path("status").asString(""))));
        return links;
    }

    private static List<CodingRequest.ConfirmedDecision> confirmedDecisions(Map<String, Object> decision) {
        List<CodingRequest.ConfirmedDecision> decisions = new ArrayList<>();
        if (Boolean.TRUE.equals(decision == null ? null : decision.get("required"))) {
            decisions.add(new CodingRequest.ConfirmedDecision(String.valueOf(decision.get("request_id")),
                    "REPOSITORY_SELECTION", String.valueOf(decision.get("question")),
                    String.valueOf(decision.get("choice"))));
        }
        return decisions;
    }

    /** No target while the repository or its base commit is unresolved. */
    private CodingRequest.RepositoryTarget repositoryTarget(String repositoryKey, String baseSha) {
        if (repositoryKey == null || baseSha == null) {
            return null;
        }
        var repository = policy.find(repositoryKey).orElseThrow();
        return new CodingRequest.RepositoryTarget(
                repositoryKey, repository.sourceRepo(), repository.baseBranch(), baseSha);
    }
}
