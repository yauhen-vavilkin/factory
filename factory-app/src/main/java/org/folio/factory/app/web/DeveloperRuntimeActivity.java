package org.folio.factory.app.web;

import org.folio.factory.app.web.DeveloperRunView.ActivityRow;
import org.folio.factory.app.web.DeveloperRunView.ActivityTone;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Display projection of runtime events: one row per tool call and one row for the starting build, without
 * the build's own log lines. A row names a command by its first line and a file by its last three folders, so a
 * heredoc body, a whole script or an error output never reaches the page; the transcript has the full text.
 */
final class DeveloperRuntimeActivity {
    /** The page keeps the newest rows; the transcript has all of them. */
    static final int KEPT_ROWS = 200;

    private final JsonMapper json;
    private final boolean feedbackFlow;
    private final ToolRows toolRows = new ToolRows();
    private final List<Draft> rows = new ArrayList<>();
    private JsonNode configuration;
    private boolean runtimeObserved;
    private String notice = "";
    private BuildStart buildStart;

    /** The row of the running starting build and when it started. */
    private record BuildStart(int row, Instant at) {
    }

    /** A row while the events are read: a call that has not ended yet keeps its present-tense verb. */
    private static final class Draft {
        private final Instant at;
        private final String present;
        private final String past;
        private final String target;
        private final boolean code;
        private String result;
        private ActivityTone tone = ActivityTone.NORMAL;

        Draft(Instant at, String present, String past, String target) {
            this(at, present, past, target, present != null);
        }

        Draft(Instant at, String present, String past, String target, boolean code) {
            this.at = at;
            this.present = present;
            this.past = past;
            this.target = target;
            this.code = code;
        }
    }

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

    /** Newest first. A call that has not ended "runs now" while the run works. */
    DeveloperRunView.Activity view(boolean live) {
        var open = toolRows.openRows();
        if (buildStart != null) {
            open = new ArrayList<>(open);
            open.add(buildStart.row());
        }
        List<ActivityRow> shown = new ArrayList<>(Math.min(rows.size(), KEPT_ROWS));
        for (int i = rows.size() - 1; i >= 0 && shown.size() < KEPT_ROWS; i--) {
            shown.add(row(rows.get(i), live && open.contains(i)));
        }
        return new DeveloperRunView.Activity(List.copyOf(shown), rows.size(), notice.isBlank() ? null : notice);
    }

    private static ActivityRow row(Draft draft, boolean running) {
        var time = PageTime.clock(draft.at);
        if (running) {
            return new ActivityRow(time, draft.present, draft.target, draft.code, "running now", ActivityTone.LIVE);
        }
        return new ActivityRow(time, draft.past, draft.target, draft.code, draft.result, draft.tone);
    }

    private void record(AuditEvent event) {
        var detail = UiFormat.parse(event.getDetail(), json);
        String action = detail.path("activity").asString("");
        boolean initialRuntime = !feedbackFlow || detail.path("round").asInt(-1) < 0;
        if (detail.has("notice")) {
            notice = detail.path("notice").asString("");
        }
        switch (action) {
            case "coding_configured" -> {
                if (initialRuntime && configuration.isEmpty()) configuration = detail;
            }
            case "pi_starting", "coding_starting" -> {
                if (initialRuntime) configuration = detail;
                runtimeObserved = true;
            }
            case "pi_usage", "coding_usage", "baseline_progress", "turn_start" -> { }
            case "tool_execution_start" -> toolStarted(event, detail);
            case "tool_execution_end" -> toolEnded(event, detail);
            case "baseline_started" -> buildStarted(event, detail);
            case "baseline_completed" -> buildEnded(event, detail);
            case "feedback_phase" -> feedbackRow(event, detail);
            default -> eventRow(event, detail, action);
        }
    }

    private void toolStarted(AuditEvent event, JsonNode detail) {
        var verbs = Verbs.of(detail);
        toolRows.started(detail, rows.size(), event.getOccurredAt());
        rows.add(new Draft(event.getOccurredAt(), verbs.present(), verbs.past(), verbs.target()));
    }

    /**
     * The end replaces its start row. An end that matches no single start gets a row of its own with what the end
     * names: Claude Code names the command, Pi only the tool, and a guess could name the wrong command.
     */
    private void toolEnded(AuditEvent event, JsonNode detail) {
        var verbs = Verbs.of(detail);
        var ending = toolRows.ended(detail, event.getOccurredAt());
        if (ending.isPresent()) {
            finish(rows.get(ending.get().row()), ending.get().result(), ending.get().failed());
            return;
        }
        var row = new Draft(event.getOccurredAt(), verbs.present(), verbs.past(), verbs.target());
        var millis = detail.path("durationMs").isIntegralNumber() ? detail.path("durationMs").asLong() : null;
        finish(row, ToolRows.result(detail, millis), detail.path("error").asBoolean(false));
        rows.add(row);
    }

    private void buildStarted(AuditEvent event, JsonNode detail) {
        var command = Verbs.firstLine(detail.path("command").asString(""));
        buildStart = new BuildStart(rows.size(), event.getOccurredAt());
        rows.add(new Draft(event.getOccurredAt(), "Building", "Built",
                command.isBlank() ? "the project" : command));
    }

    /** The end of the starting build replaces its start row: "Built mvn -B test · took 40 s". */
    private void buildEnded(AuditEvent event, JsonNode detail) {
        var exitCode = detail.path("exitCode").asInt(0);
        Draft row;
        Long millis = null;
        if (buildStart == null) {
            row = new Draft(event.getOccurredAt(), "Building", "Built", "the project");
            rows.add(row);
        } else {
            row = rows.get(buildStart.row());
            millis = Math.max(0, Duration.between(buildStart.at(), event.getOccurredAt()).toMillis());
            buildStart = null;
        }
        finish(row, exitCode == 0 ? millis == null ? null : "took " + UiFormat.milliseconds(millis)
                : "failed · exit " + exitCode, exitCode != 0);
    }

    private static void finish(Draft row, String result, boolean failed) {
        row.result = result;
        row.tone = failed ? ActivityTone.FAILED : ActivityTone.NORMAL;
    }

    private void eventRow(AuditEvent event, JsonNode detail, String action) {
        if (action.equals("agent_start")) {
            runtimeObserved = true;
        }
        var row = new Draft(event.getOccurredAt(), null, null, eventText(detail, action));
        row.tone = failedEvent(detail, action) ? ActivityTone.FAILED : ActivityTone.NORMAL;
        rows.add(row);
    }

    /** A phase of a PR feedback round: "PR feedback" then "Updating the existing PR", in words, not as code. */
    private void feedbackRow(AuditEvent event, JsonNode detail) {
        var phase = Verbs.firstLine(detail.path("message").asString(""));
        var target = phase.isBlank() ? "Next step" : phase;
        rows.add(new Draft(event.getOccurredAt(), "PR feedback", "PR feedback", target, false));
    }

    private static boolean failedEvent(JsonNode detail, String action) {
        return action.equals("provider_error")
                || action.equals("auto_retry_end") && detail.has("success") && !detail.path("success").asBoolean();
    }

    private static String eventText(JsonNode detail, String action) {
        return switch (action) {
            case "agent_start" -> "Coding agent started";
            case "agent_end" -> "Coding agent finished";
            case "provider_error" -> "Provider request failed" + providerDetail(detail);
            case "auto_retry_start" -> "Coding runtime retrying request" + retryAttempt(detail)
                    + providerDetail(detail);
            case "auto_retry_end" -> retryEndText(detail) + retryAttempt(detail) + providerDetail(detail);
            case "compaction_start" -> "Coding runtime compacting context";
            case "compaction_end" -> "Coding runtime compaction finished";
            default -> genericText(detail, action);
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
        String reason = Verbs.firstLine(detail.path("reason").asString(""));
        return reason.isBlank() ? "" : " · " + reason;
    }

    /** The event name and the first line of each known field. */
    private static String genericText(JsonNode detail, String action) {
        var label = action.equals("baseline_retryable_failure") ? "Starting build network failure"
                : action.replace('_', ' ');
        StringBuilder text = new StringBuilder(label);
        for (String key : List.of("tool", "path", "command", "message", "exitCode", "error", "summary")) {
            if (detail.has(key)) text.append(" · ").append(Verbs.firstLine(detail.path(key).asString()));
        }
        return text.toString();
    }

    /** The verbs and the target of one tool call. Pi reports lower-case tool names, Claude Code capitalised ones. */
    private record Verbs(String present, String past, String target) {
        private static final int PATH_PARTS = 3;

        static Verbs of(JsonNode detail) {
            var tool = detail.path("tool").asString("").strip();
            var path = shortPath(detail.path("path").asString(""));
            var pattern = detail.path("pattern").asString("").strip();
            return switch (tool.toLowerCase(Locale.ROOT)) {
                case "bash" -> new Verbs("Running", "Ran", command(detail));
                case "read" -> file("Reading", "Read", path);
                case "write" -> file("Writing", "Wrote", path);
                case "edit", "notebookedit" -> file("Editing", "Edited", path);
                case "find" -> new Verbs("Finding", "Found", or(path, "files"));
                case "ls" -> new Verbs("Listing", "Listed", or(path, "files"));
                case "glob" -> new Verbs("Finding", "Found", or(pattern, "files"));
                case "grep" -> new Verbs("Searching", "Searched", or(pattern, or(path, "files")));
                case "webfetch" -> new Verbs("Fetching", "Fetched", or(url(detail), "a web page"));
                default -> new Verbs("Running", "Ran", or(tool, "a tool"));
            };
        }

        static String firstLine(String text) {
            return text.strip().lines().findFirst().orElse("").strip();
        }

        private static Verbs file(String present, String past, String path) {
            return new Verbs(present, past, or(path, "a file"));
        }

        private static String command(JsonNode detail) {
            return or(firstLine(detail.path("command").asString("")), "shell command");
        }

        private static String url(JsonNode detail) {
            return detail.path("url").asString("").strip().replaceFirst("^https?://", "");
        }

        /** "…/integration/kong/KongGatewayService.java": the file and the two folders above it. */
        private static String shortPath(String path) {
            var parts = List.of(path.replace('\\', '/').strip().split("/+")).stream()
                    .filter(part -> !part.isBlank()).toList();
            if (parts.size() <= PATH_PARTS) {
                return path.strip();
            }
            return "…/" + String.join("/", parts.subList(parts.size() - PATH_PARTS, parts.size()));
        }

        private static String or(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
