package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.DevFactoryProperties.Repository;
import org.folio.factory.devfactory.decision.DecisionArtifacts;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Answer;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Request;
import org.folio.factory.devfactory.repository.BaseRefResolver;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Produces the trusted task brief: applies the validated decision (if any), then
 * pins the configured base branch to a full SHA. {@code INTAKE_READY} means the
 * issue and trusted configuration are sufficient; it says nothing about the
 * build environment.
 */
public class IntakeResolveWorker implements AgentWorker {

    public static final String ID = "dev-intake-resolve";
    public static final String TASK_BRIEF = "dev_task_brief.md";

    static final String INTAKE_READY = "INTAKE_READY";

    private final RepositoryPolicy policy;
    private final BaseRefResolver baseRefResolver;
    private final FrontmatterCodec codec;

    public IntakeResolveWorker(RepositoryPolicy policy, BaseRefResolver baseRefResolver, FrontmatterCodec codec) {
        this.policy = policy;
        this.baseRefResolver = baseRefResolver;
        this.codec = codec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode intake = codec.parse(context.requireInput(IntakeWorker.INTAKE).content()).metadata();
        String state = intake.path("state").asString("");
        if (!IntakeWorker.MAPPED.equals(state)) {
            return brief(intake, state, intake.path("reason").asString(""), null, null, null);
        }
        Request request = DecisionArtifacts.parseRequest(codec, context.requireInput(DecisionArtifacts.REQUEST).content());
        String choice;
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("required", request.required());
        if (request.required()) {
            Answer answer = validAnswer(context, request);
            choice = answer.choice();
            decision.put("request_id", request.requestId());
            decision.put("choice", choice);
            decision.put("recommended", request.recommended());
        } else {
            choice = intake.path("candidates").path(0).asString("");
        }

        Optional<Repository> repository = policy.find(choice);
        if (repository.isEmpty()) {
            return brief(intake, "BLOCKED", "REPOSITORY_NOT_CONFIGURED", decision, null, null);
        }
        Repository repo = repository.get();
        Optional<String> baseSha = baseRefResolver.resolve(repo.sourceRepo(), repo.baseBranch());
        if (baseSha.isEmpty()) {
            return brief(intake, "BLOCKED", "BASE_BRANCH_NOT_FOUND", decision, choice, null);
        }
        return brief(intake, INTAKE_READY, null, decision, choice, baseSha.get());
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
                              String repositoryKey, String baseSha) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", state);
        metadata.put("reason", reason == null || reason.isEmpty() ? null : reason);
        metadata.put("issue_key", intake.path("issue_key").asString(""));
        if (baseSha != null) {
            Repository repo = policy.find(repositoryKey).orElseThrow();
            Map<String, Object> repository = new LinkedHashMap<>();
            repository.put("key", repositoryKey);
            repository.put("source_repo", repo.sourceRepo());
            repository.put("base_branch", repo.baseBranch());
            repository.put("base_sha", baseSha);
            repository.put("build_image", repo.buildImage());
            repository.put("verification_plan", repo.verificationPlan());
            metadata.put("repository", repository);
        }
        metadata.put("decision", decision);
        metadata.put("issue", intake.path("issue"));

        StringBuilder body = new StringBuilder("# Developer task brief: ")
                .append(intake.path("issue_key").asString("")).append("\n\nState: **").append(state).append("**");
        if (metadata.get("reason") != null) {
            body.append(" (").append(reason).append(')');
        }
        body.append("\n\n");
        if (baseSha != null) {
            body.append("Repository `").append(repositoryKey).append("` at `").append(baseSha).append("`.\n\n");
        }
        body.append("Build environment readiness has not been checked yet.\n");
        return AgentResult.of(TASK_BRIEF, codec.render(metadata, body.toString()));
    }
}
