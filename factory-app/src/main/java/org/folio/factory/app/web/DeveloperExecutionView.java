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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DeveloperExecutionView {
    private static final Map<String, String> TECHNICAL_STEPS = Map.of(
            "read-task", "Read Jira task",
            "select-repository", "Select repository",
            "prepare-task", "Prepare task checkout",
            "implement", "Run starting build and Pi",
            "verify", "Independently verify candidate",
            "publish", "Publish pull request");
    private static final List<Phase> PHASES = List.of(
            new Phase("Prepare task", 0, 2, List.of("read-task", "select-repository", "prepare-task")),
            new Phase("Implement changes", 3, 3, List.of("implement")),
            new Phase("Verify changes", 4, 4, List.of("verify")),
            new Phase("Create pull request", 5, 5, List.of("publish")));
    private final JsonMapper json;
    DeveloperExecutionView(JsonMapper json) { this.json = json; }

    Map<String, Object> build(PipelineExecution execution, List<Artifact> artifacts, List<AuditEvent> events, Instant now) {
        Map<String, Artifact> latest = new LinkedHashMap<>();
        for (var artifact : artifacts) latest.merge(artifact.getName(), artifact,
                (old, fresh) -> old.getVersion() > fresh.getVersion() ? old : fresh);
        var brief = artifact(latest, "dev_task_brief.md");
        var readiness = artifact(latest, "dev_readiness.json");
        var candidate = artifact(latest, "dev_candidate.json");
        var verification = artifact(latest, "dev_verification.json");
        var result = artifact(latest, "dev_result.json");
        var delivery = artifact(latest, "dev_delivery.json");
        List<Map<String, String>> fields = new ArrayList<>();
        add(fields, "Jira task", first(brief.path("issue_key"), parse(execution.getTriggerPayload()).path("issueKey")));
        add(fields, "Summary", brief.path("issue").path("summary"));
        var repo = brief.path("repository");
        add(fields, "Selected repository", first(repo.path("key"), candidate.path("repository")));
        add(fields, "Source repository", repo.path("source_repo"));
        add(fields, "Base branch", repo.path("base_branch"));
        add(fields, "Starting commit", first(repo.path("base_sha"), candidate.path("baseSha")));
        add(fields, "Expected verification plan", repo.path("verification_plan"));
        add(fields, "Starting build", startingBuildState(readiness.path("state").asString("")));
        add(fields, "Starting build command", readiness.path("command"));
        add(fields, "Verification plan", verification.path("planId"));
        add(fields, "Verification command", verification.path("argv"));
        add(fields, "Verification", first(verification.path("result"), verification.path("state")));
        add(fields, "Verification exit code", verification.path("exitCode"));
        add(fields, "Executed tests", verification.path("testCount"));
        add(fields, "Outcome", result.path("state"));
        add(fields, "Delivery", delivery.path("state"));
        add(fields, "Delivery repository", delivery.path("deliveryRepository"));
        add(fields, "Delivery branch", delivery.path("deliveryBranch"));
        add(fields, "Delivered commit", delivery.path("deliveryCommitSha"));

        List<Map<String, String>> activity = new ArrayList<>();
        boolean started = false;
        JsonNode config = json.createObjectNode();
        for (var event : events) if (event.getEventType() == AuditEventType.RUNTIME_PROGRESS) {
            var detail = parse(event.getDetail());
            String action = detail.path("activity").asString("");
            if (action.equals("agent_start")) started = true;
            if (action.equals("pi_starting")) config = detail;
            StringBuilder text = new StringBuilder(activityLabel(action));
            for (String key : List.of("tool", "path", "command", "message", "exitCode", "error", "notice", "summary")) {
                if (detail.has(key)) text.append(" · ").append(detail.path(key).asString());
            }
            activity.add(Map.of("time", UiFormat.format(event.getOccurredAt()), "text", text.toString()));
        }
        add(fields, "Pi", started ? "Started" : "Not started");
        add(fields, "Coding image", config.path("image"));
        add(fields, "Provider", config.path("provider"));
        add(fields, "Model", config.path("model"));
        String reason = first(result.path("reason"), brief.path("reason"), candidate.path("reason"));
        if (reason.equals("Intake is not ready")) reason = first(brief.path("reason"), result.path("reason"));
        if (reason.isBlank() && "FAIL".equals(verification.path("result").asString("")))
            reason = "Independent verification failed: exit " + verification.path("exitCode").asString("")
                    + ", tests " + verification.path("testCount").asString("")
                    + ", failures " + verification.path("failureCount").asString("")
                    + ", errors " + verification.path("errorCount").asString("");
        if (reason.isBlank()) reason = first(delivery.path("reason"), verification.path("reason"));
        if (reason.isBlank() && execution.getErrorMessage() != null) reason = execution.getErrorMessage();
        String pr = delivery.path("pullRequestUrl").asString("");
        if (!pr.matches("https://[^\\s]+")) pr = "";
        var phases = phases(execution, events, now, brief, readiness, candidate, verification, result, delivery);
        add(fields, "Current phase", currentPhase(phases));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("fields", fields);
        view.put("reason", reason);
        view.put("pullRequestUrl", pr);
        view.put("activity", activity.subList(Math.max(0, activity.size() - 12), activity.size()));
        view.put("startingBuildOutput", readiness.path("output").asString(""));
        view.put("phases", phases);
        Instant elapsedEnd = execution.getStatus().isTerminal() && execution.getCompletedAt() != null
                ? execution.getCompletedAt() : now;
        view.put("elapsed", duration(execution.getCreatedAt(), elapsedEnd));
        view.put("elapsedStart", execution.getStatus().isTerminal() ? null : execution.getCreatedAt());
        return view;
    }

    static String technicalStepLabel(String id) { return TECHNICAL_STEPS.getOrDefault(id, id); }

    static String technicalStepDuration(String id, List<AuditEvent> events, PipelineExecution execution, Instant now) {
        var timing = timing(Set.of(id), events, execution, now);
        return timing.seen() ? timing.seconds() + "s" : "Not started";
    }

    private List<Map<String, Object>> phases(PipelineExecution execution, List<AuditEvent> events, Instant now,
                                              JsonNode brief, JsonNode readiness, JsonNode candidate,
                                              JsonNode verification, JsonNode result, JsonNode delivery) {
        int failedPhase = failedPhase(brief, readiness, candidate, verification, result, delivery);
        List<Map<String, Object>> rows = new ArrayList<>(PHASES.size());
        for (int i = 0; i < PHASES.size(); i++) {
            var phase = PHASES.get(i);
            var timing = timing(new HashSet<>(phase.stepIds()), events, execution, now);
            String state = phaseState(phase, execution, timing.seen());
            if (failedPhase >= 0) {
                if (i == failedPhase) state = "failed";
                else if (i > failedPhase) state = "pending";
                else state = "done";
            }
            String duration = i > failedPhase && failedPhase >= 0 ? "Not started"
                    : timing.seen() ? timing.seconds() + "s" : "Not started";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "PHASE");
            row.put("label", phase.label());
            row.put("sublabel", duration);
            row.put("state", state);
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
                || (!candidateState.isBlank() && !"CANDIDATE_UNVERIFIED".equals(candidateState))) return 1;
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
            default -> action.replace('_', ' ');
        };
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
        return start == null ? "—" : Math.max(0, Duration.between(start, end).toSeconds()) + "s";
    }

    private record Phase(String label, int firstStep, int lastStep, List<String> stepIds) { }
    private record Timing(long seconds, boolean seen, boolean running) { }
}
