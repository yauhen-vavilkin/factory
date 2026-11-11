package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.folio.factory.app.web.UiFormat.blankToNull;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DeveloperExecutionView {
    private static final String STARTING_BUILD_NETWORK_FAILURE =
            "Starting build hit a network/download failure";
    private static final List<String> OUTCOME_ARTIFACTS =
            List.of("dev_delivery.json", "dev_result.json", "dev_candidate.json", "dev_task_brief.md");
    private static final Set<String> OUTCOME_STATES = Set.of("DELIVERED", "VERIFIED", "DEVELOPMENT_FAILED",
            "VERIFICATION_FAILED", "DELIVERY_BLOCKED", "UNSUPPORTED", "BLOCKED", "BLOCKED_ENVIRONMENT",
            "NEEDS_DECISION", "REPOSITORY_MISMATCH");
    private static final JsonNode MISSING = MissingNode.getInstance();
    private final JsonMapper json;

    /** What the run list shows of a Developer Flow run; taskKey is blank when the run names none. */
    record ListFacts(String productStatus, String taskKey, RunFacts.PullRequest pullRequest) {
    }

    /** The status, task key and pull request of a run list row; a row does not load every artifact version. */
    ListFacts listFacts(PipelineExecution execution, Function<String, Optional<Artifact>> latest) {
        var parsed = parsed(latest);
        var key = first(parsed.apply("dev_task_brief.md").path("issue_key"),
                UiFormat.parse(execution.getTriggerPayload(), json).path("issueKey"));
        return new ListFacts(status(execution, parsed), key, pullRequest(parsed.apply("dev_delivery.json")));
    }

    /** The latest version of each artifact by name, as {@code ArtifactStore.getLatest} returns it. */
    static Function<String, Optional<Artifact>> latest(List<Artifact> artifacts) {
        Map<String, Artifact> latest = new HashMap<>();
        for (var artifact : artifacts) {
            latest.merge(artifact.getName(), artifact,
                    (old, fresh) -> old.getVersion() > fresh.getVersion() ? old : fresh);
        }
        return name -> Optional.ofNullable(latest.get(name));
    }

    private String status(PipelineExecution execution, Function<String, JsonNode> artifacts) {
        return feedbackStatus(execution, artifacts).orElseGet(() -> runStatus(execution, artifacts));
    }

    private Optional<String> feedbackStatus(PipelineExecution execution, Function<String, JsonNode> artifacts) {
        if (!FeedbackArtifacts.VERSION.equals(execution.getFlowVersion())) {
            return Optional.empty();
        }
        var feedback = artifacts.apply(FeedbackArtifacts.STATE);
        var reason = feedback.path("completionReason").asString("");
        var status = execution.getStatus();
        if (status == ExecutionStatus.COMPLETED && "MERGED".equals(reason)) {
            return Optional.of("COMPLETED");
        }
        if (status == ExecutionStatus.CANCELLED && "CLOSED_WITHOUT_MERGE".equals(reason)) {
            return Optional.of("CANCELLED");
        }
        if (status == ExecutionStatus.AWAITING_HITL && feedback.has("pr") && !feedback.path("pr").isNull()) {
            return Optional.of("AWAITING_PR");
        }
        return Optional.empty();
    }

    private static String runStatus(PipelineExecution execution, Function<String, JsonNode> artifacts) {
        var status = execution.getStatus();
        var codingStatus = artifacts.apply("dev_coding_outcome.json").path("status").asString("");
        if ("NEEDS_DECISION".equals(codingStatus)
                && status != ExecutionStatus.REJECTED && status != ExecutionStatus.FAILED_ESCALATED) {
            return "NEEDS_DECISION";
        }
        if (status != ExecutionStatus.COMPLETED) {
            return status.name();
        }
        return completedStatus(artifacts);
    }

    private static String completedStatus(Function<String, JsonNode> artifacts) {
        for (var name : OUTCOME_ARTIFACTS) {
            var state = artifacts.apply(name).path("state").asString("");
            if (OUTCOME_STATES.contains(state)) {
                return state;
            }
        }
        if ("FAIL".equals(artifacts.apply("dev_verification.json").path("result").asString(""))) {
            return "VERIFICATION_FAILED";
        }
        if ("BASELINE_FAILED".equals(artifacts.apply("dev_readiness.json").path("state").asString(""))) {
            return "BLOCKED_ENVIRONMENT";
        }
        return "OUTCOME_UNAVAILABLE";
    }

    /** The facts of the run page; {@code productStatus} comes from the same parse of the artifacts. */
    RunFacts build(PipelineExecution execution, List<Artifact> artifacts, List<AuditEvent> events, Instant now) {
        var parsed = parsed(latest(artifacts));
        var evidence = evidence(parsed);
        var activity = DeveloperRuntimeActivity.from(events, json,
                FeedbackArtifacts.VERSION.equals(execution.getFlowVersion()));
        var superseded = retryErrorSuperseded(execution, evidence.readiness(), activity);
        var reason = reason(execution, evidence, superseded);
        return new RunFacts(status(execution, parsed), task(execution, evidence),
                settings(activity.configuration()), reason,
                reasonKind(execution, evidence.codingOutcome(), reason, superseded),
                pullRequest(evidence.delivery()), activity.view(execution.getStatus() == ExecutionStatus.RUNNING),
                RunSteps.of(execution, events, now, evidence), technical(evidence));
    }

    private static Evidence evidence(Function<String, JsonNode> artifacts) {
        return new Evidence(artifacts.apply("dev_task_brief.md"), artifacts.apply("dev_readiness.json"),
                artifacts.apply("dev_candidate.json"), artifacts.apply("dev_coding_outcome.json"),
                artifacts.apply("dev_verification.json"), artifacts.apply("dev_result.json"),
                artifacts.apply("dev_delivery.json"));
    }

    /**
     * "glm-5.3 · max": the model as chosen in the run form and its effort. Runs started before 2026-11-03 named a
     * run profile instead, and Pi names only the model ID.
     */
    private static String settings(JsonNode config) {
        var model = first(config.path("modelId"), config.path("profileLabel"), config.path("profile"),
                config.path("model"));
        var effort = first(config.path("effort"));
        if (model.isBlank()) {
            return "";
        }
        return effort.isBlank() ? model : model + " · " + effort;
    }

    private static boolean retryErrorSuperseded(PipelineExecution execution, JsonNode readiness,
                                                DeveloperRuntimeActivity activity) {
        var error = execution.getErrorMessage();
        return error != null
                && error.startsWith(STARTING_BUILD_NETWORK_FAILURE)
                && ("BASELINE_PASSED".equals(readiness.path("state").asString(""))
                    || activity.runtimeObserved() || execution.getCurrentStepIndex() > 3);
    }

    private static String reason(PipelineExecution execution, Evidence evidence, boolean retryErrorSuperseded) {
        var verification = evidence.verification();
        var reason = recordedReason(evidence);
        if (reason.isBlank() && "FAIL".equals(verification.path("result").asString(""))) {
            reason = verificationFailure(verification);
        }
        if (reason.isBlank()) {
            reason = first(evidence.delivery().path("reason"), verification.path("reason"));
        }
        var error = execution.getErrorMessage();
        if (reason.isBlank() && error != null && !retryErrorSuperseded) {
            reason = error;
        }
        return reason;
    }

    private static String recordedReason(Evidence evidence) {
        var brief = evidence.brief();
        var result = evidence.result();
        var reason = first(result.path("reason"), brief.path("reason"), evidence.candidate().path("reason"));
        if (reason.isBlank()) {
            reason = codingOutcomeReason(evidence.codingOutcome());
        }
        return reason.equals("Intake is not ready") ? first(brief.path("reason"), result.path("reason")) : reason;
    }

    private static String codingOutcomeReason(JsonNode codingOutcome) {
        return switch (codingOutcome.path("status").asString("")) {
            case "NEEDS_DECISION" -> codingOutcome.path("decision").path("question").asString("");
            case "FAILED" -> codingOutcome.path("failure").path("message").asString("");
            default -> "";
        };
    }

    private static String verificationFailure(JsonNode verification) {
        return "Independent verification failed: exit " + verification.path("exitCode").asString("")
                + ", tests " + counter(verification, "testCount")
                + ", failures " + counter(verification, "failureCount")
                + ", errors " + counter(verification, "errorCount");
    }

    private RunFacts.Task task(PipelineExecution execution, Evidence evidence) {
        var brief = evidence.brief();
        return new RunFacts.Task(
                first(brief.path("issue_key"), UiFormat.parse(execution.getTriggerPayload(), json).path("issueKey")),
                brief.path("issue").path("summary").asString(""), repository(evidence));
    }

    /** The repository the run checked out, as the brief or the candidate names it. */
    private static String repository(Evidence evidence) {
        var repo = evidence.brief().path("repository");
        return first(repo.path("source_repo"), repo.path("key"), evidence.candidate().path("repository"));
    }

    private static RunFacts.PullRequest pullRequest(JsonNode delivery) {
        var recordedUrl = delivery.path("pullRequestUrl").asString("");
        var url = recordedUrl.matches("https://[^\\s]+") ? recordedUrl : "";
        return new RunFacts.PullRequest(url, !url.isBlank() && delivery.path("pullRequestDraft").asBoolean(false));
    }

    /** The checkout, the starting build, the checks when they ran and what was delivered, without raw states. */
    private static DeveloperRunView.Technical technical(Evidence evidence) {
        var repo = evidence.brief().path("repository");
        var repository = repository(evidence);
        var branch = repo.path("base_branch").asString("");
        var readiness = evidence.readiness();
        var checks = checksRan(evidence.verification()) ? evidence.verification() : MISSING;
        var delivery = evidence.delivery();
        var deliveredRepository = first(delivery.path("deliveryRepository"));
        return new DeveloperRunView.Technical(
                blankToNull(branch.isBlank() || repository.isBlank() ? repository : repository + "@" + branch),
                blankToNull(first(repo.path("base_sha"), evidence.candidate().path("baseSha"))),
                blankToNull(words(readiness.path("command"))), buildResult(readiness.path("state").asString("")),
                buildOutput(readiness),
                blankToNull(first(checks.path("planId"))), blankToNull(words(checks.path("argv"))),
                blankToNull(first(checks.path("exitCode"))),
                deliveredRepository.equals(repository) ? null : blankToNull(deliveredRepository),
                blankToNull(first(delivery.path("deliveryBranch"))),
                blankToNull(first(delivery.path("deliveryCommitSha"))));
    }

    private static boolean checksRan(JsonNode verification) {
        return !"NOT_RUN".equals(verification.path("state").asString(""))
                && (verification.hasNonNull("exitCode") || verification.path("argv").isArray());
    }

    /**
     * The starting build output, only when the build did not pass. A cut output starts in the middle of a line, so
     * it starts at the next whole line.
     */
    private static String buildOutput(JsonNode readiness) {
        if ("BASELINE_PASSED".equals(readiness.path("state").asString(""))) {
            return null;
        }
        var output = readiness.path("output").asString("");
        var newline = output.indexOf('\n');
        var cut = output.length() >= MavenBaselineOutput.KEPT_TAIL && newline >= 0;
        return blankToNull(cut ? output.substring(newline + 1) : output);
    }

    private static String buildResult(String state) {
        return switch (state) {
            case "BASELINE_PASSED" -> "Passed";
            case "BASELINE_FAILED" -> "Failed";
            default -> null;
        };
    }

    private static RunFacts.ReasonKind reasonKind(
            PipelineExecution execution, JsonNode codingOutcome, String reason, boolean retryErrorSuperseded) {
        if ("NEEDS_DECISION".equals(codingOutcome.path("status").asString(""))) {
            return RunFacts.ReasonKind.QUESTION;
        }
        if (execution.getStatus() == ExecutionStatus.PENDING && !reason.isBlank()) {
            return RunFacts.ReasonKind.RETRY_PENDING;
        }
        if (execution.getStatus() == ExecutionStatus.RUNNING
                && execution.getErrorMessage() != null && !retryErrorSuperseded) {
            return RunFacts.ReasonKind.RETRYING;
        }
        return RunFacts.ReasonKind.STOP;
    }

    private static String counter(JsonNode receipt, String field) {
        return receipt.hasNonNull(field) ? receipt.path(field).asString() : "unknown";
    }

    /** Parses each artifact at most once, and only when it is asked for. */
    private Function<String, JsonNode> parsed(Function<String, Optional<Artifact>> latest) {
        Map<String, JsonNode> parsed = new HashMap<>();
        return name -> parsed.computeIfAbsent(name,
                key -> latest.apply(key).map(this::content).orElseGet(json::createObjectNode));
    }

    private JsonNode content(Artifact artifact) {
        try {
            return artifact.getName().endsWith(".md")
                    ? new FrontmatterCodec().parse(artifact.getContent()).metadata()
                    : UiFormat.parse(artifact.getContent(), json);
        } catch (RuntimeException ignored) {
            return json.createObjectNode();
        }
    }

    private static String first(JsonNode... nodes) {
        for (var node : nodes) {
            if (!node.isMissingNode() && !node.isNull() && !node.asString("").isBlank()) {
                return node.asString();
            }
        }
        return "";
    }

    private static String words(JsonNode value) {
        if (!value.isArray()) {
            return first(value);
        }
        List<String> words = new ArrayList<>();
        value.forEach(word -> words.add(word.asString("")));
        return String.join(" ", words);
    }


    /** The latest version of each artifact the run page reads, parsed. */
    record Evidence(JsonNode brief, JsonNode readiness, JsonNode candidate, JsonNode codingOutcome,
                            JsonNode verification, JsonNode result, JsonNode delivery) { }
}
