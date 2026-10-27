package org.folio.factory.app.web;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DeveloperExecutionView {
    private static final String STARTING_BUILD_NETWORK_FAILURE =
            "Starting build hit a network/download failure";
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
            new Phase("Implement changes", 3, 5, List.of("implement", "clarify-implementation", "continue-implementation")),
            new Phase("Verify changes", 6, 6, List.of("verify")),
            new Phase("Create pull request", 7, 7, List.of("publish")));
    private final JsonMapper json;

    DeveloperExecutionView(JsonMapper json) {
        this.json = json;
    }

    String productStatus(PipelineExecution execution, List<Artifact> artifacts) {
        var latest = latestArtifacts(artifacts);
        if (FeedbackArtifacts.VERSION.equals(execution.getFlowVersion())) {
            var feedback = artifact(latest, FeedbackArtifacts.STATE);
            String reason = feedback.path("completionReason").asString("");
            if (execution.getStatus() == ExecutionStatus.COMPLETED
                    && "MERGED".equals(reason)) return "COMPLETED";
            if (execution.getStatus() == ExecutionStatus.CANCELLED
                    && "CLOSED_WITHOUT_MERGE".equals(reason)) return "CANCELLED";
            if (execution.getStatus() == ExecutionStatus.AWAITING_HITL
                    && feedback.has("pr") && !feedback.path("pr").isNull()) return "AWAITING_PR";
        }
        String codingStatus = artifact(latest, "dev_coding_outcome.json").path("status").asString("");
        if ("NEEDS_DECISION".equals(codingStatus)
                && execution.getStatus() != ExecutionStatus.REJECTED
                && execution.getStatus() != ExecutionStatus.FAILED_ESCALATED)
            return "NEEDS_DECISION";
        if (execution.getStatus() != ExecutionStatus.COMPLETED)
            return execution.getStatus().name();
        for (String name : List.of("dev_delivery.json", "dev_result.json", "dev_candidate.json", "dev_task_brief.md")) {
            String state = artifact(latest, name).path("state").asString("");
            if (Set.of("DELIVERED", "VERIFIED", "DEVELOPMENT_FAILED", "VERIFICATION_FAILED",
                    "DELIVERY_BLOCKED", "UNSUPPORTED", "BLOCKED", "BLOCKED_ENVIRONMENT", "NEEDS_DECISION",
                    "REPOSITORY_MISMATCH").contains(state))
                return state;
        }
        if ("FAIL".equals(artifact(latest, "dev_verification.json").path("result").asString("")))
            return "VERIFICATION_FAILED";
        if ("BASELINE_FAILED".equals(artifact(latest, "dev_readiness.json").path("state").asString("")))
            return "BLOCKED_ENVIRONMENT";
        return "OUTCOME_UNAVAILABLE";
    }

    Map<String, Object> build(PipelineExecution execution, List<Artifact> artifacts, List<AuditEvent> events, Instant now) {
        var latest = latestArtifacts(artifacts);
        var brief = artifact(latest, "dev_task_brief.md");
        var readiness = artifact(latest, "dev_readiness.json");
        var candidate = artifact(latest, "dev_candidate.json");
        var codingOutcome = artifact(latest, "dev_coding_outcome.json");
        var verification = artifact(latest, "dev_verification.json");
        var result = artifact(latest, "dev_result.json");
        var delivery = artifact(latest, "dev_delivery.json");
        var repo = brief.path("repository");
        List<Map<String, String>> technicalFields = new ArrayList<>();
        add(technicalFields, "Repository key", first(repo.path("key"), candidate.path("repository")));
        add(technicalFields, "Starting commit", first(repo.path("base_sha"), candidate.path("baseSha")));
        add(technicalFields, "Expected verification plan", repo.path("verification_plan"));
        add(technicalFields, "Starting build command", readiness.path("command"));
        add(technicalFields, "Verification plan", verification.path("planId"));
        add(technicalFields, "Verification command", verification.path("argv"));
        add(technicalFields, "Verification exit code", verification.path("exitCode"));
        add(technicalFields, "Verification failure category", verification.path("failureKind"));
        add(technicalFields, "Required suites without executed evidence", verification.path("missingRequiredReports"));
        add(technicalFields, "Automatic repair", result.path("repair"));
        add(technicalFields, "Repair decision", result.path("repairReason"));
        add(technicalFields, "Final candidate tree", candidate.path("treeSha"));
        add(technicalFields, "Final candidate patch SHA-256", candidate.path("patchSha256"));

        var activity = DeveloperRuntimeActivity.from(
                events, json, FeedbackArtifacts.VERSION.equals(execution.getFlowVersion()));
        var config = activity.configuration();
        String executionError = execution.getErrorMessage();
        boolean retryErrorSuperseded = executionError != null
                && executionError.startsWith(STARTING_BUILD_NETWORK_FAILURE)
                && ("BASELINE_PASSED".equals(readiness.path("state").asString(""))
                    || activity.runtimeObserved() || execution.getCurrentStepIndex() > 3);
        String reason = first(result.path("reason"), brief.path("reason"), candidate.path("reason"));
        if (reason.isBlank()) {
            reason = switch (codingOutcome.path("status").asString("")) {
                case "NEEDS_DECISION" -> codingOutcome.path("decision").path("question").asString("");
                case "FAILED" -> codingOutcome.path("failure").path("message").asString("");
                default -> reason;
            };
        }
        if (reason.equals("Intake is not ready")) reason = first(brief.path("reason"), result.path("reason"));
        if (reason.isBlank() && "FAIL".equals(verification.path("result").asString("")))
            reason = "Independent verification failed: exit " + verification.path("exitCode").asString("")
                    + ", tests " + counter(verification, "testCount")
                    + ", failures " + counter(verification, "failureCount")
                    + ", errors " + counter(verification, "errorCount");
        if (reason.isBlank()) reason = first(delivery.path("reason"), verification.path("reason"));
        if (reason.isBlank() && executionError != null && !retryErrorSuperseded) reason = executionError;
        String pr = delivery.path("pullRequestUrl").asString("");
        if (!pr.matches("https://[^\\s]+")) pr = "";
        var phases = phases(execution, events, now, brief, readiness, candidate, verification, result, delivery);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskKey", first(brief.path("issue_key"), parse(execution.getTriggerPayload()).path("issueKey")));
        view.put("summary", brief.path("issue").path("summary").asString("Developer Flow execution"));
        view.put("repository", first(repo.path("source_repo"), repo.path("key"), candidate.path("repository")));
        view.put("branch", repo.path("base_branch").asString(""));
        view.put("technicalFields", technicalFields);
        Map<String, String> techFacts = new LinkedHashMap<>();
        technicalFields.forEach(field -> techFacts.putIfAbsent(field.get("label"), field.get("value")));
        view.put("techFacts", techFacts);
        List<Map<String, String>> runtimeFields = new ArrayList<>();
        add(runtimeFields, "Profile", first(config.path("profileLabel"), config.path("profile")));
        add(runtimeFields, "Model", config.path("model"));
        add(runtimeFields, "Effort", config.path("effort"));
        add(runtimeFields, "Max turns", config.path("maxTurns"));
        add(runtimeFields, "Provider", config.path("provider"));
        add(runtimeFields, "Runtime", config.path("runtime"));
        add(runtimeFields, "Image", config.path("image"));
        view.put("runtimeFields", runtimeFields);
        view.put("reason", reason);
        view.put("reasonLabel", reasonLabel(execution, codingOutcome, reason, retryErrorSuperseded));
        view.put("pullRequestUrl", pr);
        String prTitle = delivery.path("pullRequestTitle").asString("");
        view.put("pullRequestLabel", prTitle.isBlank() ? pr : prTitle);
        view.put("activityNotice", activity.notice());
        view.put("activity", activity.recent());
        view.put("providerDiagnostics", activity.diagnostics());
        var history = executionHistory(events);
        view.put("history", history);
        view.put("historyCount", history.size());
        view.put("startingBuildOutput", readiness.path("output").asString(""));
        view.put("phases", phases);
        view.put("currentPhase", currentPhase(phases));
        view.put("livePhase", phases.stream().filter(phase -> "current".equals(phase.get("state")))
                .findFirst().orElse(null));
        view.put("startingBuild", outcomeState(startingBuildState(readiness.path("state").asString("")), phases, 1));
        view.put("verification", outcomeState(first(verification.path("result"), verification.path("state")), phases, 2));
        view.put("testCount", verification.isEmpty() ? "" : counter(verification, "testCount"));
        view.put("delivery", outcomeState(delivery.path("state").asString(""), phases, 3));
        view.put("deliveryRepository", delivery.path("deliveryRepository").asString(""));
        view.put("deliveryBranch", delivery.path("deliveryBranch").asString(""));
        view.put("deliveryCommit", delivery.path("deliveryCommitSha").asString(""));
        view.put("outcome", result.path("state").asString(""));
        Instant elapsedEnd = execution.getStatus().isTerminal() && execution.getCompletedAt() != null
                ? execution.getCompletedAt() : now;
        view.put("elapsed", duration(execution.getCreatedAt(), elapsedEnd));
        view.put("elapsedStart", execution.getStatus().isTerminal() ? null : execution.getCreatedAt());
        return view;
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

    static String technicalStepDuration(String id, List<AuditEvent> events, PipelineExecution execution, Instant now) {
        Long seconds = technicalStepDurationSeconds(id, events, execution, now);
        return seconds == null ? "Not started" : UiFormat.duration(seconds);
    }

    static Long technicalStepDurationSeconds(String id, List<AuditEvent> events,
                                             PipelineExecution execution, Instant now) {
        var timing = timing(Set.of(id), events, execution, now);
        return timing.seen() ? timing.seconds() : null;
    }

    private List<Map<String, Object>> phases(PipelineExecution execution, List<AuditEvent> events, Instant now,
                                              JsonNode brief, JsonNode readiness, JsonNode candidate,
                                              JsonNode verification, JsonNode result, JsonNode delivery) {
        int failedPhase = failedPhase(brief, readiness, candidate, verification, result, delivery);
        List<Phase> definitions = execution.getFlowVersion().startsWith("0.6.")
                ? LEGACY_PHASES : CURRENT_PHASES;
        List<Map<String, Object>> rows = new ArrayList<>(definitions.size());
        for (int i = 0; i < definitions.size(); i++) {
            var phase = definitions.get(i);
            var timing = timing(new HashSet<>(phase.stepIds()), events, execution, now);
            String state = phaseState(phase, execution, timing.seen());
            if (failedPhase >= 0) {
                if (i == failedPhase) state = "failed";
                else if (i > failedPhase) state = "pending";
                else state = "done";
            }
            boolean blockedByFailure = failedPhase >= 0 && i > failedPhase;
            String duration = !timing.seen() || blockedByFailure
                    ? "Not started" : UiFormat.duration(timing.seconds());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "PHASE");
            row.put("label", phase.label());
            row.put("sublabel", duration);
            row.put("state", state);
            row.put("stateLabel", phaseStateLabel(state, execution));
            row.put("attempts", null);
            row.put("tokens", null);
            if ("current".equals(state) && timing.running())
                row.put("elapsedStart", now.minusSeconds(timing.seconds()));
            rows.add(row);
        }
        return rows;
    }

    private static int failedPhase(JsonNode brief, JsonNode readiness, JsonNode candidate,
                                   JsonNode verification, JsonNode result, JsonNode delivery) {
        String preparation = brief.path("state").asString("");
        if (!preparation.isBlank() && !"INTAKE_READY".equals(preparation)) return 0;
        String candidateState = candidate.path("state").asString("");
        if ("BASELINE_FAILED".equals(readiness.path("state").asString(""))
                || (!candidateState.isBlank() && !Set.of("CANDIDATE_UNVERIFIED", "NEEDS_DECISION").contains(candidateState))) return 1;
        if ("FAIL".equals(verification.path("result").asString(""))
                || "VERIFICATION_FAILED".equals(result.path("state").asString(""))) return 2;
        if ("DELIVERY_BLOCKED".equals(delivery.path("state").asString(""))) return 3;
        return -1;
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
        Duration total = Duration.ZERO;
        boolean seen = false;
        for (var event : events) {
            if (event.getStepId() == null || !stepIds.contains(event.getStepId())) continue;
            switch (event.getEventType()) {
                case STEP_STARTED -> {
                    starts.put(event.getStepId(), event.getOccurredAt());
                    seen = true;
                }
                case STEP_COMPLETED, STEP_FAILED -> {
                    Instant start = starts.remove(event.getStepId());
                    if (start != null && !event.getOccurredAt().isBefore(start)) {
                        total = total.plus(Duration.between(start, event.getOccurredAt()));
                    }
                }
                default -> {}
            }
        }
        boolean running = !starts.isEmpty() && !execution.getStatus().isTerminal();
        Instant end = now;
        if (execution.getStatus().isTerminal()) {
            end = execution.getCompletedAt() != null
                    ? execution.getCompletedAt() : execution.getUpdatedAt();
        }
        for (Instant start : starts.values()) {
            if (end != null && !end.isBefore(start)) {
                total = total.plus(Duration.between(start, end));
            }
        }
        return new Timing(Math.max(0, total.toSeconds()), seen, running);
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

    private static Map<String, Artifact> latestArtifacts(List<Artifact> artifacts) {
        Map<String, Artifact> latest = new LinkedHashMap<>();
        for (var artifact : artifacts) {
            latest.merge(artifact.getName(), artifact,
                    (old, fresh) -> old.getVersion() > fresh.getVersion() ? old : fresh);
        }
        return latest;
    }

    private JsonNode artifact(Map<String, Artifact> artifacts, String name) {
        var artifact = artifacts.get(name);
        if (artifact == null) return json.createObjectNode();
        try {
            return name.endsWith(".md")
                    ? new FrontmatterCodec().parse(artifact.getContent()).metadata()
                    : parse(artifact.getContent());
        } catch (RuntimeException ignored) {
            return json.createObjectNode();
        }
    }

    private JsonNode parse(String value) {
        try {
            return value == null ? json.createObjectNode() : json.readTree(value);
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
    private static String duration(Instant start, Instant end) {
        return start == null ? "—" : UiFormat.duration(Duration.between(start, end).toSeconds());
    }

    private record Phase(String label, int firstStep, int lastStep, List<String> stepIds) { }
    private record Timing(long seconds, boolean seen, boolean running) { }
}
