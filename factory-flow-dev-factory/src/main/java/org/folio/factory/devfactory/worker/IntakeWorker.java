package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.decision.DecisionArtifacts;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Answer;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Option;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Request;
import org.folio.factory.devfactory.intake.IssueSnapshot;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.springframework.web.client.HttpClientErrorException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the Jira issue (read-only), finds the configured repositories that may
 * implement it and records whether a human decision is needed. Also seeds the
 * decision answer with the recommended option so the review can APPROVE it.
 */
public class IntakeWorker implements AgentWorker {

    public static final String ID = "dev-intake";
    public static final String INTAKE = "dev_intake.md";

    static final String MAPPED = "MAPPED";
    static final String BLOCKED = "BLOCKED";
    static final String UNSUPPORTED = "UNSUPPORTED";

    private static final int MAX_ECHOED_INPUT = 64;

    private final JiraConnector jira;
    private final RepositoryPolicy policy;
    private final FrontmatterCodec codec;

    public IntakeWorker(JiraConnector jira, RepositoryPolicy policy, FrontmatterCodec codec) {
        this.jira = jira;
        this.policy = policy;
        this.codec = codec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String requested = context.triggerPayload() == null ? ""
                : context.triggerPayload().path("issueKey").asString("");
        if (!IssueSnapshot.ISSUE_KEY.matcher(requested).matches()) {
            String echoed = requested.length() <= MAX_ECHOED_INPUT ? requested
                    : requested.substring(0, MAX_ECHOED_INPUT) + "...";
            return outcome(echoed, UNSUPPORTED, "INVALID_ISSUE_KEY", null, List.of());
        }
        JiraIssue issue;
        try {
            issue = jira.getIssue(requested);
        } catch (HttpClientErrorException.NotFound e) {
            return outcome(requested, BLOCKED, "ISSUE_NOT_FOUND", null, List.of());
        }
        Map<String, Object> snapshot = IssueSnapshot.of(issue);
        String key = issue.key();
        if (!IssueSnapshot.ISSUE_KEY.matcher(key).matches()) {
            return outcome(requested, BLOCKED, "UNEXPECTED_ISSUE_KEY", null, List.of());
        }
        List<String> candidates = policy.candidates(IssueSnapshot.projectKey(key), IssueSnapshot.components(snapshot));
        if (candidates.isEmpty()) {
            return outcome(key, UNSUPPORTED, "NO_REPOSITORY_MAPPING", snapshot, candidates);
        }
        return outcome(key, MAPPED, null, snapshot, candidates);
    }

    private AgentResult outcome(String issueKey, String state, String reason, Map<String, Object> snapshot,
                                List<String> candidates) {
        Request request = MAPPED.equals(state) && candidates.size() > 1
                ? Request.of(issueKey, "Which configured repository should implement this issue?",
                        candidates.stream().map(this::option).toList())
                : Request.none(issueKey);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", state);
        metadata.put("reason", reason);
        metadata.put("issue_key", issueKey);
        metadata.put("candidates", candidates);
        metadata.put("issue", snapshot);
        String body = "# Developer intake: " + issueKey + "\n\nState: **" + state + "**"
                + (reason == null ? "" : " (" + reason + ")")
                + "\n\nCandidate repositories: " + (candidates.isEmpty() ? "none" : String.join(", ", candidates))
                + "\n";

        return new AgentResult(Map.of(
                INTAKE, codec.render(metadata, body),
                DecisionArtifacts.REQUEST, DecisionArtifacts.renderRequest(codec, request),
                DecisionArtifacts.ANSWER, DecisionArtifacts.renderAnswer(codec,
                        new Answer(request.requestId(), request.required() ? request.recommended() : "none"))),
                Map.of());
    }

    private Option option(String key) {
        var repository = policy.find(key).orElseThrow();
        return new Option(key, repository.sourceRepo() + " (" + repository.baseBranch() + ")");
    }
}
