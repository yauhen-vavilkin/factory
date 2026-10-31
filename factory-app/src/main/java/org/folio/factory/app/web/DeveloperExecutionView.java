package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DeveloperExecutionView {
    private static final String STARTING_BUILD_NETWORK_FAILURE =
            "Starting build hit a network/download failure";
    private static final List<String> OUTCOME_ARTIFACTS =
            List.of("dev_delivery.json", "dev_result.json", "dev_candidate.json", "dev_task_brief.md");
    private static final Set<String> OUTCOME_STATES = Set.of("DELIVERED", "VERIFIED", "DEVELOPMENT_FAILED",
            "VERIFICATION_FAILED", "DELIVERY_BLOCKED", "UNSUPPORTED", "BLOCKED", "BLOCKED_ENVIRONMENT",
            "NEEDS_DECISION", "REPOSITORY_MISMATCH");
    private static final Set<String> PREPARED_CANDIDATE_STATES = Set.of("CANDIDATE_UNVERIFIED", "NEEDS_DECISION");
    private static final Map<String, String> TECHNICAL_STEPS = Map.of(
            "read-task", "Read Jira task",
            "select-repository", "Select repository",
            "prepare-task", "Prepare task checkout",
            "implement", "Implementation",
            "clarify-implementation", "Clarify implementation",
            "continue-implementation", "Continue implementation",
            "verify", "Independently verify candidate",
            "publish", "Publish pull request");
    private static final List<Phase> LEGACY_PHASES = List.of(
            new Phase("Prepare task", 0, 2, List.of("read-task", "select-repository", "prepare-task")),
            new Phase("Implement changes", 3, 3, List.of("implement")),
            new Phase("Verify changes", 4, 4, List.of("verify")),
            new Phase("Create pull request", 5, 5, List.of("publish")));
    private static final List<Phase> CURRENT_PHASES = List.of(
            new Phase("Prepare task", 0, 2, List.of("read-task", "select-repository", "prepare-task")),
            new Phase("Implement changes", 3, 5,
                    List.of("implement", "clarify-implementation", "continue-implementation")),
            new Phase("Verify changes", 6, 6, List.of("verify")),
            new Phase("Create pull request", 7, 7, List.of("publish")));
    private final JsonMapper json;

    /** Asks only for the artifacts the status depends on, so a run list row does not load every version. */
    String productStatus(PipelineExecution execution, Function<String, Optional<Artifact>> latest) {
        return status(execution, parsed(latest));
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

    /** The run page view; {@code productStatus} comes from the same parse of the artifacts. */
    Map<String, Object> build(PipelineExecution execution, List<Artifact> artifacts, List<AuditEvent> events,
            Instant now) {
        var parsed = parsed(latest(artifacts));
        var evidence = evidence(parsed);
        var activity = DeveloperRuntimeActivity.from(
                events, json, FeedbackArtifacts.VERSION.equals(execution.getFlowVersion()));
        var retryErrorSuperseded = retryErrorSuperseded(execution, evidence.readiness(), activity);
        var reason = reason(execution, evidence, retryErrorSuperseded);
        var phases = phases(execution, events, now, evidence);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("productStatus", status(execution, parsed));
        putTask(view, execution, evidence);
        putFields(view, technicalFields(evidence), runtimeFields(activity.configuration()));
        view.put("reason", reason);
        view.put("reasonLabel", reasonLabel(execution, evidence.codingOutcome(), reason, retryErrorSuperseded));
        putPullRequest(view, evidence.delivery());
        putActivity(view, activity, executionHistory(events));
        putProgress(view, evidence, phases);
        return view;
    }

    private static Evidence evidence(Function<String, JsonNode> artifacts) {
        return new Evidence(artifacts.apply("dev_task_brief.md"), artifacts.apply("dev_readiness.json"),
                artifacts.apply("dev_candidate.json"), artifacts.apply("dev_coding_outcome.json"),
                artifacts.apply("dev_verification.json"), artifacts.apply("dev_result.json"),
                artifacts.apply("dev_delivery.json"));
    }

    private static List<Map<String, String>> technicalFields(Evidence evidence) {
        var repo = evidence.brief().path("repository");
        var candidate = evidence.candidate();
        var verification = evidence.verification();
        var result = evidence.result();
        List<Map<String, String>> fields = new ArrayList<>();
        add(fields, "Repository key", first(repo.path("key"), candidate.path("repository")));
        add(fields, "Starting commit", first(repo.path("base_sha"), candidate.path("baseSha")));
        add(fields, "Expected verification plan", repo.path("verification_plan"));
        add(fields, "Starting build command", evidence.readiness().path("command"));
        add(fields, "Verification plan", verification.path("planId"));
        add(fields, "Verification command", verification.path("argv"));
        add(fields, "Verification exit code", verification.path("exitCode"));
        add(fields, "Verification failure category", verification.path("failureKind"));
        add(fields, "Required suites without executed evidence", verification.path("missingRequiredReports"));
        add(fields, "Automatic repair", result.path("repair"));
        add(fields, "Repair decision", result.path("repairReason"));
        add(fields, "Final candidate tree", candidate.path("treeSha"));
        add(fields, "Final candidate patch SHA-256", candidate.path("patchSha256"));
        return fields;
    }

    private static List<Map<String, String>> runtimeFields(JsonNode config) {
        List<Map<String, String>> fields = new ArrayList<>();
        add(fields, "Profile", first(config.path("profileLabel"), config.path("profile")));
        add(fields, "Model", config.path("model"));
        add(fields, "Effort", config.path("effort"));
        add(fields, "Max turns", config.path("maxTurns"));
        add(fields, "Provider", config.path("provider"));
        add(fields, "Runtime", config.path("runtime"));
        add(fields, "Image", config.path("image"));
        return fields;
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

    private void putTask(Map<String, Object> view, PipelineExecution execution, Evidence evidence) {
        var brief = evidence.brief();
        var repo = brief.path("repository");
        view.put("taskKey",
                first(brief.path("issue_key"), UiFormat.parse(execution.getTriggerPayload(), json).path("issueKey")));
        view.put("summary", brief.path("issue").path("summary").asString("Developer Flow execution"));
        view.put("repository",
                first(repo.path("source_repo"), repo.path("key"), evidence.candidate().path("repository")));
        view.put("branch", repo.path("base_branch").asString(""));
    }

    private static void putFields(Map<String, Object> view, List<Map<String, String>> technicalFields,
                                  List<Map<String, String>> runtimeFields) {
        view.put("technicalFields", technicalFields);
        Map<String, String> techFacts = new LinkedHashMap<>();
        technicalFields.forEach(field -> techFacts.putIfAbsent(field.get("label"), field.get("value")));
        view.put("techFacts", techFacts);
        view.put("runtimeFields", runtimeFields);
    }

    private static void putPullRequest(Map<String, Object> view, JsonNode delivery) {
        var recordedUrl = delivery.path("pullRequestUrl").asString("");
        var url = recordedUrl.matches("https://[^\\s]+") ? recordedUrl : "";
        view.put("pullRequestUrl", url);
        view.put("pullRequestDraft", !url.isBlank() && delivery.path("pullRequestDraft").asBoolean(false));
        var title = delivery.path("pullRequestTitle").asString("");
        view.put("pullRequestLabel", title.isBlank() ? url : title);
    }

    private static void putActivity(Map<String, Object> view, DeveloperRuntimeActivity activity,
                                    List<Map<String, String>> history) {
        view.put("activityNotice", activity.notice());
        view.put("activity", activity.recent());
        view.put("providerDiagnostics", activity.diagnostics());
        view.put("history", history);
        view.put("historyCount", history.size());
    }

    private static void putProgress(Map<String, Object> view, Evidence evidence, List<Map<String, Object>> phases) {
        var readiness = evidence.readiness();
        var verification = evidence.verification();
        var delivery = evidence.delivery();
        view.put("startingBuildOutput", readiness.path("output").asString(""));
        view.put("phases", phases);
        view.put("currentPhase", currentPhase(phases));
        view.put("livePhase", phases.stream().filter(phase -> "current".equals(phase.get("state")))
                .findFirst().orElse(null));
        view.put("startingBuild", outcomeState(startingBuildState(readiness.path("state").asString("")), phases, 1));
        view.put("verification",
                outcomeState(first(verification.path("result"), verification.path("state")), phases, 2));
        view.put("testCount", verification.isEmpty() ? "" : counter(verification, "testCount"));
        view.put("delivery", outcomeState(delivery.path("state").asString(""), phases, 3));
        view.put("deliveryRepository", delivery.path("deliveryRepository").asString(""));
        view.put("deliveryBranch", delivery.path("deliveryBranch").asString(""));
        view.put("deliveryCommit", delivery.path("deliveryCommitSha").asString(""));
    }

    private static String reasonLabel(
            PipelineExecution execution, JsonNode codingOutcome, String reason, boolean retryErrorSuperseded) {
        if ("NEEDS_DECISION".equals(codingOutcome.path("status").asString(""))) {
            return "Decision required";
        }
        if (execution.getStatus() == ExecutionStatus.PENDING && !reason.isBlank()) {
            return "Retry pending";
        }
        if (execution.getStatus() == ExecutionStatus.RUNNING
                && execution.getErrorMessage() != null && !retryErrorSuperseded) {
            return "Previous attempt";
        }
        return "Stop reason";
    }

    private static String counter(JsonNode receipt, String field) {
        return receipt.hasNonNull(field) ? receipt.path(field).asString() : "unknown";
    }

    private static List<Map<String, String>> executionHistory(List<AuditEvent> events) {
        return events.stream()
                .filter(event -> event.getEventType() != AuditEventType.RUNTIME_PROGRESS
                        && event.getEventType() != AuditEventType.ARTIFACT_WRITTEN)
                .sorted(Comparator.comparing(AuditEvent::getOccurredAt).reversed())
                .map(event -> {
                    String step = event.getStepId() == null ? "" : technicalStepLabel(event.getStepId());
                    String text = UiFormat.eventLabel(event.getEventType());
                    if (!step.isBlank()) text += " · " + step;
                    return Map.of("time", UiFormat.format(event.getOccurredAt()), "text", text,
                            "tone", event.getEventType() == AuditEventType.STEP_FAILED ? "failed" : "normal");
                })
                .limit(24)
                .toList();
    }

    static String technicalStepLabel(String id) {
        return TECHNICAL_STEPS.getOrDefault(id, id);
    }

    static Long technicalStepDurationSeconds(String id, List<AuditEvent> events,
                                             PipelineExecution execution, Instant now) {
        var timing = timing(Set.of(id), events, execution, now);
        return timing.seen() ? timing.seconds() : null;
    }

    private static List<Map<String, Object>> phases(PipelineExecution execution, List<AuditEvent> events,
                                                     Instant now, Evidence evidence) {
        var failedPhase = failedPhase(evidence);
        var definitions = execution.getFlowVersion().startsWith("0.6.") ? LEGACY_PHASES : CURRENT_PHASES;
        List<Map<String, Object>> rows = new ArrayList<>(definitions.size());
        for (int i = 0; i < definitions.size(); i++) {
            var phase = definitions.get(i);
            var timing = timing(new HashSet<>(phase.stepIds()), events, execution, now);
            var state = failedPhase >= 0
                    ? stateAroundFailure(i, failedPhase) : phaseState(phase, execution, timing.seen());
            var blockedByFailure = failedPhase >= 0 && i > failedPhase;
            var duration = !timing.seen() || blockedByFailure ? "Not started" : UiFormat.duration(timing.seconds());
            var elapsedStart = "current".equals(state) && timing.running() ? now.minusSeconds(timing.seconds()) : null;
            rows.add(phaseRow(phase.label(), duration, state, phaseStateLabel(state, execution), elapsedStart));
        }
        return rows;
    }

    private static Map<String, Object> phaseRow(String label, String duration, String state, String stateLabel,
                                                Instant elapsedStart) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("sublabel", duration);
        row.put("state", state);
        row.put("stateLabel", stateLabel);
        if (elapsedStart != null) {
            row.put("elapsedStart", elapsedStart);
        }
        return row;
    }

    private static int failedPhase(Evidence evidence) {
        var preparation = evidence.brief().path("state").asString("");
        if (!preparation.isBlank() && !"INTAKE_READY".equals(preparation)) {
            return 0;
        }
        var candidateState = evidence.candidate().path("state").asString("");
        if ("BASELINE_FAILED".equals(evidence.readiness().path("state").asString(""))
                || (!candidateState.isBlank() && !PREPARED_CANDIDATE_STATES.contains(candidateState))) {
            return 1;
        }
        if ("FAIL".equals(evidence.verification().path("result").asString(""))
                || "VERIFICATION_FAILED".equals(evidence.result().path("state").asString(""))) {
            return 2;
        }
        return "DELIVERY_BLOCKED".equals(evidence.delivery().path("state").asString("")) ? 3 : -1;
    }

    private static String stateAroundFailure(int index, int failedPhase) {
        if (index == failedPhase) {
            return "failed";
        }
        return index > failedPhase ? "pending" : "done";
    }

    private static String phaseState(Phase phase, PipelineExecution execution, boolean phaseStarted) {
        int current = execution.getCurrentStepIndex();
        if (execution.getStatus() == ExecutionStatus.PENDING
                && current == 0 && !phaseStarted) return "pending";
        if (current > phase.lastStep()) return "done";
        if (current < phase.firstStep()) return "pending";
        return execution.getStatus().isTerminal()
                && execution.getStatus() != ExecutionStatus.COMPLETED
                ? "failed" : "current";
    }

    private static Timing timing(Set<String> stepIds, List<AuditEvent> events,
                                 PipelineExecution execution, Instant now) {
        Map<String, Instant> starts = new HashMap<>();
        var total = Duration.ZERO;
        var seen = false;
        for (var event : events) {
            if (event.getStepId() == null || !stepIds.contains(event.getStepId())) {
                continue;
            }
            switch (event.getEventType()) {
                case STEP_STARTED -> {
                    starts.put(event.getStepId(), event.getOccurredAt());
                    seen = true;
                }
                case STEP_COMPLETED, STEP_FAILED ->
                        total = total.plus(closedTime(starts.remove(event.getStepId()), event.getOccurredAt()));
                default -> { }
            }
        }
        var running = !starts.isEmpty() && !execution.getStatus().isTerminal();
        total = total.plus(openTime(starts.values(), timingEnd(execution, now)));
        return new Timing(Math.max(0, total.toSeconds()), seen, running);
    }

    private static Duration closedTime(Instant start, Instant end) {
        return start != null && !end.isBefore(start) ? Duration.between(start, end) : Duration.ZERO;
    }

    private static Duration openTime(Collection<Instant> starts, Instant end) {
        var total = Duration.ZERO;
        for (var start : starts) {
            if (end != null && !end.isBefore(start)) {
                total = total.plus(Duration.between(start, end));
            }
        }
        return total;
    }

    private static Instant timingEnd(PipelineExecution execution, Instant now) {
        if (!execution.getStatus().isTerminal()) {
            return now;
        }
        return execution.getCompletedAt() != null ? execution.getCompletedAt() : execution.getUpdatedAt();
    }

    private static String currentPhase(List<Map<String, Object>> phases) {
        for (String state : List.of("failed", "current", "pending")) {
            for (var phase : phases) {
                if (state.equals(phase.get("state"))) return phase.get("label").toString();
            }
        }
        return "Complete";
    }

    private static String phaseStateLabel(String state, PipelineExecution execution) {
        return switch (state) {
            case "done" -> "Passed";
            case "failed" -> "Failed";
            case "pending" -> "Waiting";
            case "current" -> switch (execution.getStatus()) {
                case RUNNING -> "Working";
                case PENDING -> "Retry pending";
                case AWAITING_HITL -> "Awaiting decision";
                default -> "Current";
            };
            default -> state;
        };
    }

    private static String outcomeState(String evidence, List<Map<String, Object>> phases, int phase) {
        return evidence.isBlank() ? phases.get(phase).get("stateLabel").toString() : evidence;
    }

    private static String startingBuildState(String state) {
        return switch (state) {
            case "BASELINE_PASSED" -> "Passed";
            case "BASELINE_FAILED" -> "Failed";
            default -> state;
        };
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

    private static void add(List<Map<String, String>> fields, String label, JsonNode value) {
        if (value.isArray()) {
            List<String> words = new ArrayList<>();
            value.forEach(word -> words.add(word.asString("")));
            add(fields, label, String.join(" ", words));
        } else add(fields, label, first(value));
    }
    private static void add(List<Map<String, String>> fields, String label, String value) {
        if (!value.isBlank()) fields.add(Map.of("label", label, "value", value));
    }

    private record Phase(String label, int firstStep, int lastStep, List<String> stepIds) { }
    private record Timing(long seconds, boolean seen, boolean running) { }
    private record Evidence(JsonNode brief, JsonNode readiness, JsonNode candidate, JsonNode codingOutcome,
                            JsonNode verification, JsonNode result, JsonNode delivery) { }
}
