package org.folio.factory.devfactory.runtime;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Bounded, redacted activity projection; usage continues after the display limit. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class PiProgress implements Consumer<String> {
    private static final int FRAME_LIMIT = 512 * 1024;
    private static final int ACTIVITY_LIMIT = 200;
    private static final List<String> ACTIVITIES = List.of(
            "agent_start",
            "agent_end",
            "turn_start",
            "tool_execution_start",
            "tool_execution_end",
            "compaction_start",
            "compaction_end");
    private static final List<String> PROVIDER_STOPS = List.of("error", "aborted");
    private static final List<String> PATH_TOOLS = List.of("read", "write", "edit", "find", "ls", "grep");
    private static final Pattern BUILD_WORD = Pattern.compile(
            "(?:\\./)?(?:mvnw?|gradlew?|npm|npx|pytest|go|cargo)|test|verify|compile|build|clean"
                    + "|package|check|run|--offline|-o|-q|-B"
                    + "|-D(?:test|it.test)=[A-Za-z0-9_.*,#-]+");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PiUsage usage = new PiUsage();
    private final String secret;
    private final Consumer<Map<String, Object>> observer;
    private int emitted;
    private int diagnosticsEmitted;

    @Override
    public void accept(String line) {
        if (line.length() > FRAME_LIMIT || line.isBlank()) {
            return;
        }
        JsonNode frame;
        try {
            frame = mapper.readTree(line);
        } catch (RuntimeException ignored) {
            return;
        }
        observe(frame);
    }

    private void observe(JsonNode frame) {
        var type = frame.path("type").asString("");
        if (usage.record(frame)) {
            publishUsage();
        }
        if (type.equals("message_end")) {
            reportProviderError(frame.path("message"));
            return;
        }
        if (type.equals("auto_retry_start") || type.equals("auto_retry_end")) {
            diagnostic(retryEvent(frame, type));
            return;
        }
        if (emitted < ACTIVITY_LIMIT && ACTIVITIES.contains(type)) {
            emitActivity(frame, type);
        }
    }

    private void publishUsage() {
        var event = usage.snapshot();
        event.put("activity", "coding_usage");
        observer.accept(event);
    }

    private void reportProviderError(JsonNode message) {
        var stopReason = message.path("stopReason").asString("");
        if (!message.path("role").asString("").equals("assistant") || !PROVIDER_STOPS.contains(stopReason)) {
            return;
        }
        var event = new LinkedHashMap<String, Object>();
        event.put("activity", "provider_error");
        event.put("stopReason", stopReason);
        event.put("reason", PiProtocol.providerCause(message.path("errorMessage").asString("")));
        diagnostic(event);
    }

    private static Map<String, Object> retryEvent(JsonNode frame, String type) {
        var event = new LinkedHashMap<String, Object>();
        event.put("activity", type);
        if (frame.path("attempt").isIntegralNumber()) {
            event.put("attempt", frame.path("attempt").asInt());
        }
        if (frame.path("maxAttempts").isIntegralNumber()) {
            event.put("maxAttempts", frame.path("maxAttempts").asInt());
        }
        if (type.equals("auto_retry_end")) {
            event.put("success", frame.path("success").asBoolean(false));
        }
        var raw = frame.path(type.equals("auto_retry_start") ? "errorMessage" : "finalError").asString("");
        if (!raw.isBlank()) {
            event.put("reason", PiProtocol.providerCause(raw));
        }
        return event;
    }

    private void emitActivity(JsonNode frame, String type) {
        var event = new LinkedHashMap<String, Object>();
        event.put("activity", type);
        if (type.startsWith("tool_execution")) {
            addToolDetails(event, frame, type);
        }
        emitted++;
        if (emitted == ACTIVITY_LIMIT) {
            event.put("notice", "Runtime activity limit reached; usage reporting continues");
        }
        observer.accept(event);
    }

    private void addToolDetails(Map<String, Object> event, JsonNode frame, String type) {
        var tool = frame.path("toolName").asString("");
        event.put("tool", safe(tool));
        if (type.equals("tool_execution_end")) {
            event.put("error", frame.path("isError").asBoolean(false));
        }
        if (!type.equals("tool_execution_start")) {
            return;
        }
        if (PATH_TOOLS.contains(tool)) {
            var path = frame.path("args").path("path").asString("");
            if (!path.isBlank()) {
                event.put("path", safe(path));
            }
        }
        if (tool.equals("bash")) {
            event.put("command", safe(commandSummary(frame.path("args").path("command").asString(""))));
        }
    }

    private static String commandSummary(String command) {
        var summary = new StringBuilder();
        for (var word : command.strip().split("\\s+")) {
            if (!BUILD_WORD.matcher(word).matches()) {
                continue;
            }
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(word);
        }
        return summary.isEmpty() ? "Shell command" : summary.toString();
    }

    private void diagnostic(Map<String, Object> event) {
        if (diagnosticsEmitted++ < 32) observer.accept(event);
    }

    String safe(String value) {
        String clean =
                secret == null || secret.isBlank() ? value : value.replace(secret, "[REDACTED]");
        clean = clean.replaceAll("[\\p{Cntrl}]", " ");
        return clean.substring(0, Math.min(240, clean.length()));
    }

    Map<String, Object> metrics() {
        return usage.snapshot();
    }
}
