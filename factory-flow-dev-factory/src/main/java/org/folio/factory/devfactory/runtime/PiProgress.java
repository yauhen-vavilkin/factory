package org.folio.factory.devfactory.runtime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Bounded, redacted activity projection; usage continues after the display limit. */
final class PiProgress implements Consumer<String> {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PiUsage usage = new PiUsage();
    private final String secret;
    private final Consumer<Map<String, Object>> observer;
    private int emitted;
    private int diagnosticsEmitted;

    PiProgress(String secret, Consumer<Map<String, Object>> observer) {
        this.secret = secret;
        this.observer = observer;
    }

    @Override
    public void accept(String line) {
        if (line.length() > 512 * 1024 || line.isBlank()) return;
        JsonNode frame;
        try {
            frame = mapper.readTree(line);
        } catch (RuntimeException ignored) {
            return;
        }
        String type = frame.path("type").asString("");
        if (usage.record(frame)) {
            var event = usage.snapshot();
            event.put("activity", "coding_usage");
            observer.accept(event);
        }
        if (type.equals("message_end")) {
            var message = frame.path("message");
            if (message.path("role").asString("").equals("assistant")
                    && List.of("error", "aborted")
                            .contains(message.path("stopReason").asString(""))) {
                var event = new LinkedHashMap<String, Object>();
                event.put("activity", "provider_error");
                event.put("stopReason", message.path("stopReason").asString(""));
                event.put(
                        "reason",
                        PiProtocol.providerCause(message.path("errorMessage").asString("")));
                diagnostic(event);
            }
            return;
        }
        if (type.equals("auto_retry_start") || type.equals("auto_retry_end")) {
            var event = new LinkedHashMap<String, Object>();
            event.put("activity", type);
            if (frame.path("attempt").isIntegralNumber())
                event.put("attempt", frame.path("attempt").asInt());
            if (frame.path("maxAttempts").isIntegralNumber())
                event.put("maxAttempts", frame.path("maxAttempts").asInt());
            if (type.equals("auto_retry_end"))
                event.put("success", frame.path("success").asBoolean(false));
            String raw =
                    frame.path(type.equals("auto_retry_start") ? "errorMessage" : "finalError")
                            .asString("");
            if (!raw.isBlank()) event.put("reason", PiProtocol.providerCause(raw));
            diagnostic(event);
            return;
        }
        if (emitted >= 200) return;
        if (!List.of(
                        "agent_start",
                        "agent_end",
                        "turn_start",
                        "tool_execution_start",
                        "tool_execution_end",
                        "compaction_start",
                        "compaction_end")
                .contains(type)) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("activity", type);
        if (type.startsWith("tool_execution")) {
            String tool = frame.path("toolName").asString("");
            event.put("tool", safe(tool));
            if (type.equals("tool_execution_end"))
                event.put("error", frame.path("isError").asBoolean(false));
            if (type.equals("tool_execution_start")) {
                if (List.of("read", "write", "edit", "find", "ls", "grep").contains(tool)) {
                    String path = frame.path("args").path("path").asString("");
                    if (!path.isBlank()) event.put("path", safe(path));
                }
                if (tool.equals("bash")) {
                    String command = frame.path("args").path("command").asString("").strip();
                    var words = command.split("\\s+");
                    StringBuilder summary = new StringBuilder();
                    for (String word : words) {
                        if (word.matches(
                                "(?:\\./)?(?:mvnw?|gradlew?|npm|npx|pytest|go|cargo)|test|verify|compile|build|clean|package|check|run|--offline|-o|-q|-B|-D(?:test|it.test)=[A-Za-z0-9_.*,#-]+")) {
                            if (!summary.isEmpty()) summary.append(' ');
                            summary.append(word);
                        }
                    }
                    event.put(
                            "command",
                            safe(summary.isEmpty() ? "Shell command" : summary.toString()));
                }
            }
        }
        emitted++;
        if (emitted == 200)
            event.put("notice", "Runtime activity limit reached; usage reporting continues");
        observer.accept(event);
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
