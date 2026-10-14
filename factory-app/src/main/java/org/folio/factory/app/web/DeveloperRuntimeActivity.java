package org.folio.factory.app.web;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Display projection of runtime events, including consecutive heartbeats and overlapping tools. */
final class DeveloperRuntimeActivity {
    private final JsonMapper json;
    private final boolean feedbackFlow;
    private final BashActivity bashActivity = new BashActivity();
    private final List<Map<String, String>> activity = new ArrayList<>();
    private final List<Map<String, String>> diagnostics = new ArrayList<>();
    private JsonNode configuration;
    private boolean runtimeObserved;
    private String notice = "";
    private String previousHeartbeat;

    private DeveloperRuntimeActivity(JsonMapper json, boolean feedbackFlow) {
        this.json = json;
        this.feedbackFlow = feedbackFlow;
        configuration = json.createObjectNode();
    }

    static DeveloperRuntimeActivity from(List<AuditEvent> events, JsonMapper json, boolean feedbackFlow) {
        var result = new DeveloperRuntimeActivity(json, feedbackFlow);
        events.stream()
                .filter(event -> event.getEventType() == AuditEventType.RUNTIME_PROGRESS)
                .sorted(Comparator.comparing(AuditEvent::getOccurredAt))
                .forEach(result::record);
        return result;
    }

    JsonNode configuration() {
        return configuration;
    }

    boolean runtimeObserved() {
        return runtimeObserved;
    }

    String notice() {
        return notice;
    }

    List<Map<String, String>> recent() {
        return lastTwenty(activity).reversed();
    }

    List<Map<String, String>> diagnostics() {
        return lastTwenty(diagnostics);
    }

    private static List<Map<String, String>> lastTwenty(List<Map<String, String>> rows) {
        return rows.subList(Math.max(0, rows.size() - 20), rows.size());
    }

    private void record(AuditEvent event) {
        var detail = parse(event.getDetail());
        String action = detail.path("activity").asString("");
        boolean initialRuntime = !feedbackFlow || detail.path("round").asInt(-1) < 0;
        switch (action) {
            case "coding_configured" -> {
                if (initialRuntime && configuration.isEmpty()) configuration = detail;
                return;
            }
            case "pi_usage", "coding_usage" -> {
                return;
            }
            case "agent_start" -> runtimeObserved = true;
            case "pi_starting", "coding_starting" -> {
                if (initialRuntime) configuration = detail;
                runtimeObserved = true;
            }
            default -> {}
        }
        String text = runtimeActivityText(detail, bashActivity);
        var row = Map.of("time", UiFormat.format(event.getOccurredAt()), "text", text);
        if (action.equals("provider_error") || (action.startsWith("auto_retry_")
                && (detail.hasNonNull("reason") || detail.has("success")))) {
            diagnostics.add(row);
        }
        if (detail.has("notice")) notice = detail.path("notice").asString("");
        if (action.equals("turn_start")) {
            previousHeartbeat = null;
            return;
        }
        boolean heartbeat = action.equals("baseline_progress")
                && detail.path("message").asString("").equals("Starting build is still running");
        // Keep only the latest timestamp for consecutive heartbeats; the audit trail is unchanged.
        if (heartbeat && text.equals(previousHeartbeat)) {
            activity.set(activity.size() - 1, row);
        } else {
            activity.add(row);
        }
        previousHeartbeat = heartbeat ? text : null;
    }

    private JsonNode parse(String value) {
        try {
            return value == null ? json.createObjectNode() : json.readTree(value);
        } catch (RuntimeException ignored) {
            return json.createObjectNode();
        }
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
            case "provider_error" -> "Provider request failed" + providerDetail(detail);
            case "auto_retry_start" -> "Coding runtime retrying request" + retryAttempt(detail)
                    + providerDetail(detail);
            case "auto_retry_end" -> retryEndText(detail) + retryAttempt(detail) + providerDetail(detail);
            case "compaction_start" -> "Coding runtime compacting context";
            case "compaction_end" -> "Coding runtime compaction finished";
            case "tool_execution_start" -> toolStartText(detail, bashActivity);
            case "tool_execution_end" -> toolEndText(detail, bashActivity);
            default -> genericActivityText(detail, action);
        };
    }

    private static String retryAttempt(JsonNode detail) {
        if (!detail.path("attempt").isInt()) return "";
        String attempt = detail.path("attempt").asString();
        return detail.path("maxAttempts").isInt() ? " (" + attempt + "/"
                + detail.path("maxAttempts").asString() + ")" : " (attempt " + attempt + ")";
    }

    private static String retryEndText(JsonNode detail) {
        if (!detail.has("success")) return "Coding runtime retry finished";
        return detail.path("success").asBoolean(false)
                ? "Coding runtime retry succeeded" : "Coding runtime retry failed";
    }

    private static String providerDetail(JsonNode detail) {
        String reason = detail.path("reason").asString("");
        return reason.isBlank() ? "" : " · " + reason;
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
}
