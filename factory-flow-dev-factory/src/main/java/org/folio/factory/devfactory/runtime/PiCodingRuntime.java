package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.candidate.CandidateFreezer;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Pi owns the coding loop. One-shot JSON mode exits only after its run finishes. */
public class PiCodingRuntime implements CodingRuntime {
    static final int EVENT_STREAM_LIMIT = 16 * 1024 * 1024;
    private final DevRuntimeProperties.Coding config;
    private final JsonMapper json = JsonMapper.builder().build();
    public PiCodingRuntime(DevRuntimeProperties.Coding config) { this.config = config; }
    @Override public Result code(DockerWorkloads.Workload workload, String task, int timeoutSeconds) {
        config.requireConfigured();
        if (!workload.execute(List.of("pi", "--version"), 30).requireSuccess().output().strip().equals("0.85.1"))
            throw new IllegalStateException("Coding image must contain Pi 0.85.1");
        var temporary = CandidateFreezer.temporary("factory-dev-pi-");
        try {
            var provider = new java.util.LinkedHashMap<String, Object>();
            if (config.baseUrl() != null && !config.baseUrl().isBlank()) provider.put("baseUrl", config.baseUrl());
            provider.put("api", config.api());
            provider.put("apiKey", config.apiKey());
            provider.put("models", List.of(Map.of("id", config.model(), "name", config.model(), "contextWindow", 200000, "maxTokens", 16384)));
            Files.writeString(temporary.resolve("models.json"), json.writeValueAsString(Map.of("providers", Map.of(config.provider(), provider))));
            Files.writeString(temporary.resolve("task.txt"), task);
            workload.execute(List.of("mkdir", "-p", "/tmp/factory-pi"), 30).requireSuccess();
            workload.copy(temporary.resolve(".").toString(), "/tmp/factory-pi");
            var execution = workload.execute(List.of("env", "PI_CODING_AGENT_DIR=/tmp/factory-pi", "pi", "--print", "--mode", "json",
                    "--no-session", "--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes", "--offline",
                    "--provider", config.provider(), "--model", config.model(), "@/tmp/factory-pi/task.txt"), timeoutSeconds,
                    EVENT_STREAM_LIMIT);
            if (execution.exitCode() != 0) throw new IllegalStateException("Pi failed (exit " + execution.exitCode() + "); candidate not accepted");
            return parse(execution.output(), config.provider(), config.model());
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot prepare Pi input", e); }
        finally { CandidateFreezer.delete(temporary); }
    }
    static Result parse(String output, String provider, String model) {
        var mapper = JsonMapper.builder().build();
        boolean ended = false;
        String summary = "";
        for (String line : output.split("\n")) {
            if (line.isBlank()) continue;
            if (line.length() > 512 * 1024) throw new IllegalStateException("Pi frame exceeds limit");
            var event = mapper.readTree(line);
            String type = event.path("type").asString("");
            if (type.equals("agent_end")) ended = true;
            if (type.equals("auto_retry_end") && !event.path("success").asBoolean(true))
                throw new IllegalStateException("Pi provider retries exhausted");
            if (type.equals("message_end")) {
                var message = event.path("message");
                if (!message.path("role").asString("").equals("assistant")) continue;
                if (List.of("error", "aborted").contains(message.path("stopReason").asString("")))
                    throw new IllegalStateException("Pi provider failed or aborted");
                if (!message.path("provider").asString(provider).equals(provider) || !message.path("model").asString(model).equals(model))
                    throw new IllegalStateException("Pi provider/model identity mismatch");
                StringBuilder text = new StringBuilder();
                for (var block : message.path("content")) if (block.path("type").asString("").equals("text")) text.append(block.path("text").asString(""));
                if (!text.isEmpty()) summary = text.toString();
            }
        }
        if (!ended || summary.isBlank()) throw new IllegalStateException("Pi did not return a completed coding result");
        if (summary.contains("FACTORY_DECISION_REQUIRED")) {
            String detail = summary.substring(0, Math.min(1600, summary.length())).replaceAll("[\\r\\n]+", " ").strip();
            throw new IllegalStateException("CODING_DECISION_REQUIRED: " + detail);
        }
        return new Result(summary.substring(0, Math.min(8000, summary.length())));
    }
}
