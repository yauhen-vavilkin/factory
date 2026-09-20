package org.folio.factory.app.web;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.PipelineExecution;
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
    DeveloperExecutionView(JsonMapper json) { this.json = json; }

    String productStatus(PipelineExecution execution, List<Artifact> artifacts) {
        Map<String, Artifact> latest = new LinkedHashMap<>();
        for (var artifact : artifacts) latest.merge(artifact.getName(), artifact,
                (old, fresh) -> old.getVersion() > fresh.getVersion() ? old : fresh);
        String codingStatus = artifact(latest, "dev_coding_outcome.json").path("status").asString("");
        if ("NEEDS_DECISION".equals(codingStatus)
                && execution.getStatus() != org.folio.factory.core.domain.ExecutionStatus.REJECTED
                && execution.getStatus() != org.folio.factory.core.domain.ExecutionStatus.FAILED_ESCALATED)
            return "NEEDS_DECISION";
        if (execution.getStatus() != org.folio.factory.core.domain.ExecutionStatus.COMPLETED)
            return execution.getStatus().name();
        for (String name : List.of("dev_delivery.json", "dev_result.json", "dev_candidate.json", "dev_task_brief.md")) {
            String state = artifact(latest, name).path("state").asString("");
            if (Set.of("DELIVERED", "VERIFIED", "DEVELOPMENT_FAILED", "VERIFICATION_FAILED",
                    "DELIVERY_BLOCKED", "UNSUPPORTED", "BLOCKED", "BLOCKED_ENVIRONMENT", "NEEDS_DECISION").contains(state))
                return state;
        }
        if ("FAIL".equals(artifact(latest, "dev_verification.json").path("result").asString("")))
            return "VERIFICATION_FAILED";
        if ("BASELINE_FAILED".equals(artifact(latest, "dev_readiness.json").path("state").asString("")))
            return "BLOCKED_ENVIRONMENT";
        return "OUTCOME_UNAVAILABLE";
    }

    Map<String, Object> build(PipelineExecution execution, List<Artifact> artifacts, List<AuditEvent> events, Instant now) {
        Map<String, Artifact> latest = new LinkedHashMap<>();
        for (var artifact : artifacts) latest.merge(artifact.getName(), artifact,
                (old, fresh) -> old.getVersion() > fresh.getVersion() ? old : fresh);
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

        List<Map<String, String>> recentActivity = new ArrayList<>();
        Map<String, String> latestActivity = null;
        String activityNotice = "";
        boolean runtimeObserved = false;
        JsonNode config = json.createObjectNode();
        String previousHeartbeat = null;
        var bashActivity = new BashActivity();
        var orderedEvents = events.stream()
                .sorted(Comparator.comparing(AuditEvent::getOccurredAt))
                .toList();
        for (var event : orderedEvents) {
            if (event.getEventType() != AuditEventType.RUNTIME_PROGRESS) continue;
            var detail = parse(event.getDetail());
            String action = detail.path("activity").asString("");
            if (action.equals("pi_usage") || action.equals("coding_usage")) continue;
            if (action.equals("agent_start")) runtimeObserved = true;
            if (action.equals("pi_starting") || action.equals("coding_starting")) {
                config = detail;
                runtimeObserved = true;
            }
            String text = runtimeActivityText(detail, bashActivity);
            var row = Map.of("time", UiFormat.format(event.getOccurredAt()), "text", text);
            latestActivity = row;
            if (detail.has("notice")) activityNotice = detail.path("notice").asString("");
            if (action.equals("turn_start")) {
                previousHeartbeat = null;
                continue;
            }
            boolean heartbeat = action.equals("baseline_progress")
                    && detail.path("message").asString("").equals("Starting build is still running");
            // Keep the latest timestamp for a consecutive heartbeat run, without changing the audit trail.
            if (heartbeat && text.equals(previousHeartbeat)) recentActivity.set(recentActivity.size() - 1, row);
            else recentActivity.add(row);
            previousHeartbeat = heartbeat ? text : null;
        }
        add(technicalFields, "Coding image", config.path("image"));
        add(technicalFields, "Coding runtime", config.path("runtime"));
        add(technicalFields, "Provider", config.path("provider"));
        add(technicalFields, "Model", config.path("model"));
        String executionError = execution.getErrorMessage();
        boolean retryErrorSuperseded = executionError != null
                && executionError.startsWith(STARTING_BUILD_NETWORK_FAILURE)
                && ("BASELINE_PASSED".equals(readiness.path("state").asString(""))
                    || runtimeObserved || execution.getCurrentStepIndex() > 3);
        String reason = first(result.path("reason"), brief.path("reason"), candidate.path("reason"));
        if (reason.isBlank() && "NEEDS_DECISION".equals(codingOutcome.path("status").asString("")))
            reason = codingOutcome.path("decision").path("question").asString("");
        if (reason.isBlank() && "FAILED".equals(codingOutcome.path("status").asString("")))
            reason = codingOutcome.path("failure").path("message").asString("");
        if (reason.equals("Intake is not ready")) reason = first(brief.path("reason"), result.path("reason"));
        if (reason.isBlank() && "FAIL".equals(verification.path("result").asString("")))
            reason = "Independent verification failed: exit " + verification.path("exitCode").asString("")
                    + ", tests " + verification.path("testCount").asString("")
                    + ", failures " + verification.path("failureCount").asString("")
                    + ", errors " + verification.path("errorCount").asString("");
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
        view.put("reason", reason);
        String reasonLabel = "NEEDS_DECISION".equals(codingOutcome.path("status").asString(""))
                ? "Decision required" : execution.getStatus() == org.folio.factory.core.domain.ExecutionStatus.PENDING
                && !reason.isBlank() ? "Retry pending" : execution.getStatus() == org.folio.factory.core.domain.ExecutionStatus.RUNNING
                && executionError != null && !retryErrorSuperseded ? "Previous attempt" : "Stop reason";
        view.put("reasonLabel", reasonLabel);
        view.put("pullRequestUrl", pr);
        view.put("latestActivity", latestActivity);
        view.put("activityNotice", activityNotice);
        view.put("activity", recentActivity.subList(Math.max(0, recentActivity.size() - 6), recentActivity.size()));
        var history = executionHistory(events);
        view.put("history", history);
        view.put("historyCount", history.size());
        view.put("startingBuildOutput", readiness.path("output").asString(""));
        view.put("phases", phases);
        view.put("currentPhase", currentPhase(phases));
        view.put("startingBuild", outcomeState(startingBuildState(readiness.path("state").asString("")), phases, 1));
        view.put("verification", outcomeState(first(verification.path("result"), verification.path("state")), phases, 2));
        view.put("testCount", verification.path("testCount").asString(""));
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

    static String technicalStepLabel(String id) { return TECHNICAL_STEPS.getOrDefault(id, id); }

    static String technicalStepDuration(String id, List<AuditEvent> events, PipelineExecution execution, Instant now) {
        var timing = timing(Set.of(id), events, execution, now);
        return timing.seen() ? UiFormat.duration(timing.seconds()) : "Not started";
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
            String duration = i > failedPhase && failedPhase >= 0 ? "Not started"
                    : timing.seen() ? UiFormat.duration(timing.seconds()) : "Not started";
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
        if (execution.getStatus() == org.folio.factory.core.domain.ExecutionStatus.PENDING
                && current == 0 && !phaseStarted) return "pending";
        if (current > phase.lastStep()) return "done";
        if (current < phase.firstStep()) return "pending";
        return execution.getStatus().isTerminal()
                && execution.getStatus() != org.folio.factory.core.domain.ExecutionStatus.COMPLETED
                ? "failed" : "current";
    }

    private static Timing timing(Set<String> stepIds, List<AuditEvent> events,
                                 PipelineExecution execution, Instant now) {
        Map<String, Instant> starts = new HashMap<>();
        Duration total = Duration.ZERO;
        boolean seen = false;
        for (var event : events) if (event.getStepId() != null && stepIds.contains(event.getStepId())) {
            if (event.getEventType() == AuditEventType.STEP_STARTED) {
                starts.put(event.getStepId(), event.getOccurredAt());
                seen = true;
            } else if ((event.getEventType() == AuditEventType.STEP_COMPLETED
                    || event.getEventType() == AuditEventType.STEP_FAILED)) {
                Instant start = starts.remove(event.getStepId());
                if (start != null && !event.getOccurredAt().isBefore(start))
                    total = total.plus(Duration.between(start, event.getOccurredAt()));
            }
        }
        boolean running = !starts.isEmpty() && !execution.getStatus().isTerminal();
        Instant end = execution.getStatus().isTerminal()
                ? execution.getCompletedAt() != null ? execution.getCompletedAt() : execution.getUpdatedAt()
                : now;
        for (Instant start : starts.values()) if (end != null && !end.isBefore(start))
            total = total.plus(Duration.between(start, end));
        return new Timing(Math.max(0, total.toSeconds()), seen, running);
    }

    private static String currentPhase(List<Map<String, Object>> phases) {
        for (var phase : phases) if ("failed".equals(phase.get("state"))) return phase.get("label").toString();
        for (var phase : phases) if ("current".equals(phase.get("state"))) return phase.get("label").toString();
        for (var phase : phases) if ("pending".equals(phase.get("state"))) return phase.get("label").toString();
        return "Complete";
    }

    private static String phaseStateLabel(String state, PipelineExecution execution) {
        return switch (state) {
            case "done" -> "Done";
            case "failed" -> "Failed";
            case "pending" -> "Pending";
            case "current" -> switch (execution.getStatus()) {
                case RUNNING -> "Running";
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

    private static String activityLabel(String action) {
        return switch (action) {
            case "baseline_started" -> "Starting build started";
            case "baseline_progress" -> "Starting build progress";
            case "baseline_completed" -> "Starting build completed";
            case "baseline_retryable_failure" -> "Starting build network failure";
            default -> action.replace('_', ' ');
        };
    }

    private static String runtimeActivityText(JsonNode detail, BashActivity bashActivity) {
        String action = detail.path("activity").asString("");
        return switch (action) {
            case "agent_start" -> "Coding agent started";
            case "agent_end" -> "Coding agent finished";
            case "turn_start" -> "Coding agent working";
            case "auto_retry_start" -> "Coding runtime retrying request";
            case "auto_retry_end" -> "Coding runtime retry finished";
            case "compaction_start" -> "Coding runtime compacting context";
            case "compaction_end" -> "Coding runtime compaction finished";
            case "tool_execution_start" -> toolStartText(detail, bashActivity);
            case "tool_execution_end" -> toolEndText(detail, bashActivity);
            default -> genericActivityText(detail, action);
        };
    }

    private static String toolStartText(JsonNode detail, BashActivity bashActivity) {
        String tool = detail.path("tool").asString("").strip();
        String path = fileName(detail.path("path").asString(""));
        if (tool.equals("bash")) {
            String command = detail.path("command").asString("Shell command").strip();
            if (command.isBlank()) command = "Shell command";
            bashActivity.started(command);
            return "Running " + command;
        }
        return switch (tool) {
            case "read" -> "Reading " + fileOrFallback(path);
            case "write" -> "Writing " + fileOrFallback(path);
            case "edit" -> "Editing " + fileOrFallback(path);
            case "find" -> path.isBlank() ? "Finding files" : "Finding files in " + path;
            case "ls" -> path.isBlank() ? "Listing files" : "Listing " + path;
            case "grep" -> path.isBlank() ? "Searching files" : "Searching " + path;
            default -> tool.isBlank() ? "Running tool" : "Running " + tool;
        };
    }

    private static String toolEndText(JsonNode detail, BashActivity bashActivity) {
        String tool = detail.path("tool").asString("").strip();
        String subject = tool;
        if (tool.equals("bash")) subject = bashActivity.finishedSubject();
        if (subject == null || subject.isBlank()) subject = tool.isBlank() ? "Tool" : tool;
        return subject + (detail.path("error").asBoolean(false) ? " failed" : " completed");
    }

    private static String genericActivityText(JsonNode detail, String action) {
        StringBuilder text = new StringBuilder(activityLabel(action));
        for (String key : List.of("tool", "path", "command", "message", "exitCode", "error", "summary")) {
            if (detail.has(key)) text.append(" · ").append(detail.path(key).asString());
        }
        return text.toString();
    }

    private static String fileName(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1)
            normalized = normalized.substring(0, normalized.length() - 1);
        int separator = normalized.lastIndexOf('/');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }

    private static String fileOrFallback(String path) {
        return path.isBlank() ? "file" : path;
    }

    private static final class BashActivity {
        private int active;
        private String command;
        private boolean ambiguous;

        void started(String nextCommand) {
            if (active == 0) command = nextCommand;
            else ambiguous = true;
            active++;
        }

        String finishedSubject() {
            String subject = active == 1 && !ambiguous ? command : "Shell command";
            if (active > 0) active--;
            if (active == 0) {
                command = null;
                ambiguous = false;
            }
            return subject;
        }
    }

    private JsonNode artifact(Map<String, Artifact> artifacts, String name) {
        var artifact = artifacts.get(name);
        if (artifact == null) return json.createObjectNode();
        try { return name.endsWith(".md") ? new FrontmatterCodec().parse(artifact.getContent()).metadata() : parse(artifact.getContent()); }
        catch (RuntimeException ignored) { return json.createObjectNode(); }
    }
    private JsonNode parse(String value) {
        try { return value == null ? json.createObjectNode() : json.readTree(value); }
        catch (RuntimeException ignored) { return json.createObjectNode(); }
    }
    private static String first(JsonNode... nodes) {
        for (var node : nodes) if (!node.isMissingNode() && !node.isNull() && !node.asString("").isBlank()) return node.asString();
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
