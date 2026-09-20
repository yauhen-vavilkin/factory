package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.candidate.CandidateFreezer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Pi adapter for the Factory-owned coding request and outcome contracts. */
public class PiCodingRuntime implements CodingRuntime {
    static final int EVENT_STREAM_LIMIT = 16 * 1024 * 1024;
    private final DevRuntimeProperties.Coding config;
    private final JsonMapper json = JsonMapper.builder().build();

    public PiCodingRuntime(DevRuntimeProperties.Coding config) { this.config = config; }

    @Override public String id() { return "pi"; }
    @Override public String image() { return config.image(); }
    @Override public void requireConfigured() { config.requireConfigured(); }
    @Override public Map<String, Object> identity() {
        return Map.of("runtime", id(), "image", image(), "provider", config.provider(), "model", config.model());
    }
    @Override public String redact(String value) {
        return config.apiKey() == null || config.apiKey().isBlank()
                ? value : value.replace(config.apiKey(), "[REDACTED]");
    }

    @Override public CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request,
                                        int timeoutSeconds) {
        return code(workload, request, timeoutSeconds, event -> { });
    }

    @Override public CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds,
                                        java.util.function.Consumer<Map<String, Object>> progress) {
        requireConfigured();
        request.requireReady();
        if (!workload.execute(List.of("pi", "--version"), 30).requireSuccess().output().strip().equals("0.85.1"))
            throw new IllegalStateException("Coding image must contain Pi 0.85.1");
        var temporary = CandidateFreezer.temporary("factory-dev-pi-");
        try {
            var provider = new java.util.LinkedHashMap<String, Object>();
            if (config.baseUrl() != null && !config.baseUrl().isBlank()) provider.put("baseUrl", config.baseUrl());
            provider.put("api", config.api());
            provider.put("apiKey", config.apiKey());
            provider.put("models", List.of(Map.of("id", config.model(), "name", config.model(),
                    "contextWindow", 200000, "maxTokens", 16384)));
            Files.writeString(temporary.resolve("models.json"),
                    json.writeValueAsString(Map.of("providers", Map.of(config.provider(), provider))));
            Files.writeString(temporary.resolve("task.txt"), prompt(request));
            workload.execute(List.of("mkdir", "-p", "/tmp/factory-pi"), 30).requireSuccess();
            workload.copy(temporary.resolve(".").toString(), "/tmp/factory-pi");
            var observer = new Progress(config.apiKey(), progress);
            Processes.Result execution;
            try {
                execution = workload.execute(List.of("env", "PI_CODING_AGENT_DIR=/tmp/factory-pi", "pi", "--print",
                                "--mode", "json", "--no-session", "--no-extensions", "--no-skills",
                                "--no-prompt-templates", "--no-themes", "--offline", "--provider",
                                config.provider(), "--model", config.model(), "@/tmp/factory-pi/task.txt"),
                        timeoutSeconds, EVENT_STREAM_LIMIT, observer);
            } catch (RuntimeException e) {
                return CodingOutcome.failed("RUNTIME_ERROR", redact(safeFailure(e)),
                        withIdentity(observer.metrics(), config.provider(), config.model()));
            }
            if (execution.exitCode() != 0) {
                return CodingOutcome.failed("RUNTIME_EXIT", "Pi exited with code " + execution.exitCode(),
                        withIdentity(observer.metrics(), config.provider(), config.model()));
            }
            return sanitize(parse(execution.output(), config.provider(), config.model()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot prepare Pi input", e);
        } finally {
            CandidateFreezer.cleanup(temporary);
        }
    }

    private String prompt(CodingRequest request) {
        return """
                Implement this task in /workspace. Inspect, understand, plan, edit, run targeted checks, debug, and self-review in one cohesive session.
                Keep changes focused. Do not push, perform Jira/GitHub writes, switch repositories, alter .git, or generate Factory verification receipts.
                The repository and base commit are authorized working inputs, not proof that the repository owns the requested change.
                If the selected repository appears wrong, do not switch it. Return the decision protocol below with kind REPOSITORY_MISMATCH.
                If a material product or requirements question cannot be resolved from the request and repository, return the protocol with kind PRODUCT_REQUIREMENTS.
                Otherwise complete the implementation. Your self-checks are diagnostic; Factory independently verifies the frozen candidate.

                For NEEDS_DECISION, the entire final response must be one line beginning FACTORY_DECISION_REQUIRED: followed by JSON:
                {"kind":"PRODUCT_REQUIREMENTS|REPOSITORY_MISMATCH","question":"one concrete question","options":["2-4 options when useful"],"evidence":"bounded repository evidence"}

                Factory CodingRequest JSON:
                """ + json.writeValueAsString(request);
    }

    private CodingOutcome sanitize(CodingOutcome outcome) {
        return switch (outcome.status()) {
            case COMPLETED -> CodingOutcome.completed(redact(outcome.summary()), outcome.metrics());
            case NEEDS_DECISION -> {
                var decision = outcome.decision();
                yield CodingOutcome.needsDecision(new CodingOutcome.Decision(decision.kind(),
                        redact(decision.question()), decision.options().stream().map(this::redact).toList(),
                        redact(decision.evidence())), outcome.metrics());
            }
            case FAILED -> CodingOutcome.failed(outcome.failure().code(),
                    redact(outcome.failure().message()), outcome.metrics());
        };
    }

    static CodingOutcome parse(String output, String provider, String model) {
        var mapper = JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        boolean ended = false;
        String summary = "";
        var usage = new Usage();
        for (String line : output.split("\n")) {
            if (line.isBlank()) continue;
            if (line.length() > 512 * 1024)
                return CodingOutcome.failed("FRAME_TOO_LARGE", "Pi frame exceeds limit",
                        withIdentity(usage.snapshot(), provider, model));
            tools.jackson.databind.JsonNode event;
            try {
                event = mapper.readTree(line);
            } catch (RuntimeException e) {
                return CodingOutcome.failed("MALFORMED_FRAME", "Pi returned malformed event output",
                        withIdentity(usage.snapshot(), provider, model));
            }
            String type = event.path("type").asString("");
            if (type.equals("agent_end")) ended = true;
            if (type.equals("auto_retry_end") && !event.path("success").asBoolean(true))
                return CodingOutcome.failed("PROVIDER_RETRIES_EXHAUSTED", "Pi provider retries exhausted",
                        withIdentity(usage.snapshot(), provider, model));
            if (type.equals("message_end")) {
                var message = event.path("message");
                if (!message.path("role").asString("").equals("assistant")) continue;
                if (List.of("error", "aborted").contains(message.path("stopReason").asString("")))
                    return CodingOutcome.failed("PROVIDER_FAILED", "Pi provider failed or aborted",
                            withIdentity(usage.snapshot(), provider, model));
                if (!message.path("provider").asString(provider).equals(provider)
                        || !message.path("model").asString(model).equals(model))
                    return CodingOutcome.failed("IDENTITY_MISMATCH", "Pi provider/model identity mismatch",
                            withIdentity(usage.snapshot(), provider, model));
                usage.add(message.path("usage"));
                StringBuilder text = new StringBuilder();
                for (var block : message.path("content"))
                    if (block.path("type").asString("").equals("text"))
                        text.append(block.path("text").asString(""));
                if (!text.isEmpty()) summary = text.toString();
            }
        }
        Map<String, Object> metrics = withIdentity(usage.snapshot(), provider, model);
        if (!ended || summary.isBlank())
            return CodingOutcome.failed("INCOMPLETE_RESULT", "Pi did not return a completed coding result", metrics);
        String normalized = summary.stripLeading();
        if (normalized.startsWith("FACTORY_DECISION_REQUIRED:")) {
            String detail = normalized.substring("FACTORY_DECISION_REQUIRED:".length()).strip();
            if (detail.contains("\n") || detail.contains("\r"))
                return CodingOutcome.failed("INVALID_DECISION_RESULT",
                        "Pi returned an invalid decision request", metrics);
            try {
                var decision = mapper.readTree(detail);
                var options = new java.util.ArrayList<String>();
                decision.path("options").forEach(option -> options.add(option.asString("")));
                return CodingOutcome.needsDecision(new CodingOutcome.Decision(
                        CodingOutcome.DecisionKind.valueOf(decision.path("kind").asString("")),
                        decision.path("question").asString(""), options,
                        decision.path("evidence").asString("")), metrics);
            } catch (RuntimeException e) {
                return CodingOutcome.failed("INVALID_DECISION_RESULT", "Pi returned an invalid decision request", metrics);
            }
        }
        return CodingOutcome.completed(summary.substring(0, Math.min(8000, summary.length())), metrics);
    }

    private static Map<String, Object> withIdentity(Map<String, Object> usage, String provider, String model) {
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("provider", provider);
        metrics.put("model", model);
        metrics.putAll(usage);
        return Map.copyOf(metrics);
    }

    private static String safeFailure(RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(2000, message.length()));
    }

    private static final class Usage {
        private long input, cacheRead, cacheWrite, output;
        private java.math.BigDecimal cost;
        private boolean inputPresent, cacheReadPresent, cacheWritePresent, outputPresent;

        boolean add(tools.jackson.databind.JsonNode usage) {
            if (!usage.isObject()) return false;
            boolean found = false;
            if (usage.path("input").isNumber()) {
                inputPresent = true; input += Math.max(0, usage.path("input").asLong(0)); found = true;
            }
            if (usage.path("cacheRead").isNumber()) {
                cacheReadPresent = true; cacheRead += Math.max(0, usage.path("cacheRead").asLong(0)); found = true;
            }
            if (usage.path("cacheWrite").isNumber()) {
                cacheWritePresent = true; cacheWrite += Math.max(0, usage.path("cacheWrite").asLong(0)); found = true;
            }
            if (usage.path("output").isNumber()) {
                outputPresent = true; output += Math.max(0, usage.path("output").asLong(0)); found = true;
            }
            var total = usage.path("cost").path("total");
            if (total.isNumber() && total.asDouble() >= 0 && Double.isFinite(total.asDouble())) {
                cost = (cost == null ? java.math.BigDecimal.ZERO : cost).add(total.decimalValue()); found = true;
            }
            return found;
        }

        Map<String, Object> snapshot() {
            Map<String, Object> metrics = new java.util.LinkedHashMap<>();
            if (inputPresent) metrics.put("inputTokens", input);
            if (cacheReadPresent) metrics.put("cacheReadTokens", cacheRead);
            if (cacheWritePresent) metrics.put("cacheWriteTokens", cacheWrite);
            if (outputPresent) metrics.put("outputTokens", output);
            if (cost != null) metrics.put("costUsd", cost);
            return metrics;
        }
    }

    static final class Progress implements java.util.function.Consumer<String> {
        private final JsonMapper mapper = JsonMapper.builder().build();
        private final Usage usage = new Usage();
        private final String secret;
        private final java.util.function.Consumer<Map<String, Object>> observer;
        private int emitted;
        Progress(String secret, java.util.function.Consumer<Map<String, Object>> observer) {
            this.secret = secret; this.observer = observer;
        }
        @Override public void accept(String line) {
            if (line.length() > 512 * 1024 || line.isBlank()) return;
            tools.jackson.databind.JsonNode frame;
            try { frame = mapper.readTree(line); }
            catch (RuntimeException ignored) { return; }
            String type = frame.path("type").asString("");
            if (type.equals("message_end")) {
                var message = frame.path("message");
                if (message.path("role").asString("").equals("assistant") && usage.add(message.path("usage"))) {
                    var event = usage.snapshot();
                    event.put("activity", "coding_usage");
                    observer.accept(event);
                }
                return;
            }
            if (emitted >= 200) return;
            if (!List.of("agent_start", "agent_end", "turn_start", "tool_execution_start", "tool_execution_end",
                    "auto_retry_start", "auto_retry_end", "compaction_start", "compaction_end").contains(type)) return;
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("activity", type);
            if (type.startsWith("tool_execution")) {
                String tool = frame.path("toolName").asString("");
                event.put("tool", safe(tool));
                if (type.equals("tool_execution_end")) event.put("error", frame.path("isError").asBoolean(false));
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
                            if (word.matches("(?:\\./)?(?:mvnw?|gradlew?|npm|npx|pytest|go|cargo)|test|verify|compile|build|clean|package|check|run|--offline|-o|-q|-B|-D(?:test|it.test)=[A-Za-z0-9_.*,#-]+")) {
                                if (!summary.isEmpty()) summary.append(' ');
                                summary.append(word);
                            }
                        }
                        event.put("command", safe(summary.isEmpty() ? "Shell command" : summary.toString()));
                    }
                }
            }
            emitted++;
            if (emitted == 200) event.put("notice", "Runtime activity limit reached; usage reporting continues");
            observer.accept(event);
        }
        String safe(String value) {
            String clean = secret == null || secret.isBlank() ? value : value.replace(secret, "[REDACTED]");
            clean = clean.replaceAll("[\\p{Cntrl}]", " ");
            return clean.substring(0, Math.min(240, clean.length()));
        }
        Map<String, Object> metrics() { return usage.snapshot(); }
    }
}
