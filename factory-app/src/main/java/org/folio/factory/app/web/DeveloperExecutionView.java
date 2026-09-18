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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DeveloperExecutionView {
    private static final Map<String, String> STAGES = Map.of("intake", "Read Jira task",
            "intake-decision", "Resolve task decision", "intake-resolve", "Pin source revision",
            "develop", "Baseline and Pi coding", "verify", "Independent verification", "deliver", "Delivery");
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
        add(fields, "Repository selection", first(repo.path("key"), candidate.path("repository")));
        add(fields, "Source repository", repo.path("source_repo"));
        add(fields, "Base branch", repo.path("base_branch"));
        add(fields, "Pinned base SHA", first(repo.path("base_sha"), candidate.path("baseSha")));
        add(fields, "Expected verification plan", repo.path("verification_plan"));
        add(fields, "Baseline", readiness.path("state"));
        add(fields, "Baseline command", readiness.path("command"));
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
            StringBuilder text = new StringBuilder(action.replace('_', ' '));
            for (String key : List.of("tool", "path", "command", "exitCode", "error", "notice")) {
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
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("fields", fields);
        view.put("reason", reason);
        view.put("pullRequestUrl", pr);
        view.put("activity", activity.subList(Math.max(0, activity.size() - 12), activity.size()));
        view.put("elapsed", duration(execution.getCreatedAt(), execution.getCompletedAt() == null ? now : execution.getCompletedAt()));
        view.put("elapsedStart", execution.getStatus().isTerminal() ? null : execution.getCreatedAt());
        return view;
    }

    static String stageLabel(String id) { return STAGES.getOrDefault(id, id); }

    static String stageDuration(String id, List<AuditEvent> events, PipelineExecution execution, Instant now) {
        Instant start = null;
        long seconds = 0;
        boolean seen = false;
        for (var event : events) if (id.equals(event.getStepId())) {
            if (event.getEventType() == AuditEventType.STEP_STARTED) { start = event.getOccurredAt(); seen = true; }
            if ((event.getEventType() == AuditEventType.STEP_COMPLETED || event.getEventType() == AuditEventType.STEP_FAILED) && start != null) {
                seconds += Math.max(0, Duration.between(start, event.getOccurredAt()).toSeconds()); start = null;
            }
        }
        if (start != null) seconds += Math.max(0, Duration.between(start,
                execution.getCompletedAt() == null ? now : execution.getCompletedAt()).toSeconds());
        return seen ? seconds + "s" : "Not started";
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
}
