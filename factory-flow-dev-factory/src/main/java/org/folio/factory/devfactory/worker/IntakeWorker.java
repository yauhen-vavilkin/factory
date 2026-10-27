package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.decision.DecisionArtifacts;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Answer;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Option;
import org.folio.factory.devfactory.decision.DecisionArtifacts.Request;
import org.folio.factory.devfactory.intake.IssueSnapshot;
import org.folio.factory.devfactory.pilot.PilotAttemptPolicy;
import org.folio.factory.devfactory.pilot.PilotExecutionContext;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.springframework.web.client.HttpClientErrorException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the Jira issue (read-only), finds the configured repositories that may
 * implement it and records whether a human decision is needed. Also seeds the
 * decision answer with the recommended option so the review can APPROVE it.
 * Pilot executions consume the approved bounded snapshot instead: no second
 * Jira read, and ambiguity — or an admitted repository that cannot be verified
 * against the current mapping — stops instead of seeding a recommendation or
 * continuing on the re-derived mapping.
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
    private final CodingRuntime runtime;
    private final AuditLog audit;
    private final PilotAttemptPolicy pilotPolicy;

    public IntakeWorker(JiraConnector jira, RepositoryPolicy policy, FrontmatterCodec codec,
                        CodingRuntime runtime, AuditLog audit) {
        this(jira, policy, codec, runtime, audit, PilotAttemptPolicy.none());
    }

    public IntakeWorker(JiraConnector jira, RepositoryPolicy policy, FrontmatterCodec codec,
                        CodingRuntime runtime, AuditLog audit, PilotAttemptPolicy pilotPolicy) {
        this.jira = jira;
        this.policy = policy;
        this.codec = codec;
        this.runtime = runtime;
        this.audit = audit;
        this.pilotPolicy = pilotPolicy;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        Optional<PilotExecutionContext> pilot = pilotPolicy.find(context.executionId());
        recordRuntimeConfiguration(context, pilot.map(PilotExecutionContext::runSettings).orElse(null));
        if (pilot.isPresent()) {
            return pilotOutcome(context, pilot.get());
        }
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

    private AgentResult pilotOutcome(AgentContext context, PilotExecutionContext pilot) {
        Map<String, Object> snapshot = pilot.approvedSnapshot();
        String key = Objects.toString(snapshot.get("key"), "");
        if (!IssueSnapshot.ISSUE_KEY.matcher(key).matches()) {
            return stop(context, outcome(key, UNSUPPORTED, "INVALID_ISSUE_KEY", snapshot, List.of()),
                    "the approved snapshot carries no valid issue key");
        }
        List<String> components = IssueSnapshot.components(snapshot);
        List<String> candidates = policy.candidates(IssueSnapshot.projectKey(key),
                components == null ? List.of() : components);
        if (candidates.isEmpty()) {
            return stop(context, outcome(key, UNSUPPORTED, "NO_REPOSITORY_MAPPING", snapshot, candidates),
                    "the approved issue has no repository mapping");
        }
        if (candidates.size() > 1) {
            return stop(context, outcome(key, BLOCKED, "AMBIGUOUS_REPOSITORY_MAPPING", snapshot, candidates),
                    "ambiguous repository mapping requires a human choice: " + String.join(", ", candidates));
        }
        String admitted = pilot.repositoryKey();
        if (admitted == null || admitted.isBlank()) {
            return mappingStop(context, key, snapshot, candidates,
                    "the repository mapping recorded at admission cannot be verified");
        }
        if (!admitted.equals(candidates.getFirst())) {
            return mappingStop(context, key, snapshot, candidates,
                    "repository routing changed since admission: approved " + admitted
                            + " but the approved issue now maps to " + candidates.getFirst());
        }
        return outcome(key, MAPPED, null, snapshot, candidates);
    }

    private AgentResult mappingStop(AgentContext context, String key, Map<String, Object> snapshot,
                                    List<String> candidates, String detail) {
        return stop(context, outcome(key, BLOCKED, "REPOSITORY_MAPPING_CHANGED", snapshot, candidates), detail);
    }

    private AgentResult stop(AgentContext context, AgentResult result, String reason) {
        pilotPolicy.recordStop(context.executionId(), context.stepId(), reason);
        return result;
    }

    private void recordRuntimeConfiguration(AgentContext context, CodingRequest.RunSettings settings) {
        var identity = new LinkedHashMap<>(runtime.identity(settings));
        identity.put("activity", "coding_configured");
        audit.record(context.executionId(), AuditEventType.RUNTIME_PROGRESS, context.stepId(), ID, identity);
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
