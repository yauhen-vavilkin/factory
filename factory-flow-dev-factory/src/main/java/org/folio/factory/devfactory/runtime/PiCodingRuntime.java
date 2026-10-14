package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.candidate.CandidateFreezer;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Pi adapter for the Factory-owned coding request and outcome contracts. */
public class PiCodingRuntime implements CodingRuntime {
    static final int EVENT_STREAM_LIMIT = 16 * 1024 * 1024;
    private final DevRuntimeProperties.Pi config;
    private final JsonMapper json = JsonMapper.builder().build();

    public PiCodingRuntime(DevRuntimeProperties.Coding config) {
        this.config = config.piProfile();
    }

    @Override
    public String id() {
        return "pi";
    }

    @Override
    public String image() {
        return config.image();
    }

    @Override
    public void requireConfigured() {
        config.requireConfigured();
    }

    @Override
    public Map<String, Object> identity() {
        var identity = new LinkedHashMap<>(CodingRuntime.super.identity());
        if (config.provider() != null && !config.provider().isBlank())
            identity.put("provider", config.provider());
        if (config.model() != null && !config.model().isBlank())
            identity.put("model", config.model());
        return Map.copyOf(identity);
    }

    @Override
    public String redact(String value) {
        return config.apiKey() == null || config.apiKey().isBlank()
                ? value
                : value.replace(config.apiKey(), "[REDACTED]");
    }

    @Override
    public CodingOutcome code(
            DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds) {
        return code(workload, request, timeoutSeconds, event -> {});
    }

    @Override
    public CodingOutcome code(
            DockerWorkloads.Workload workload,
            CodingRequest request,
            int timeoutSeconds,
            Consumer<Map<String, Object>> progress) {
        requireConfigured();
        request.requireReady();
        if (!workload.execute(List.of("pi", "--version"), 30)
                .requireSuccess()
                .output()
                .strip()
                .equals("0.85.1"))
            throw new IllegalStateException("Coding image must contain Pi 0.85.1");
        var temporary = CandidateFreezer.temporary("factory-dev-pi-");
        try {
            var provider = new LinkedHashMap<String, Object>();
            if (config.baseUrl() != null && !config.baseUrl().isBlank())
                provider.put("baseUrl", config.baseUrl());
            provider.put("api", config.api());
            provider.put("apiKey", config.apiKey());
            var model = new LinkedHashMap<String, Object>();
            model.put("id", config.model());
            model.put("name", config.model());
            model.put("contextWindow", 200000);
            model.put("maxTokens", config.maxOutputTokens());
            if (config.reasoningEffort() != null) model.put("reasoning", true);
            if (config.provider().equals("codemie") && config.model().equals("gemini-3.8-flash"))
                model.put(
                        "compat",
                        Map.of("supportsDeveloperRole", false, "maxTokensField", "max_tokens"));
            provider.put("models", List.of(model));
            Files.writeString(
                    temporary.resolve("models.json"),
                    json.writeValueAsString(
                            Map.of("providers", Map.of(config.provider(), provider))));
            Files.writeString(temporary.resolve("task.txt"), CodingProtocol.prompt(request));
            workload.execute(List.of("mkdir", "-p", "/tmp/factory-pi"), 30).requireSuccess();
            workload.copy(temporary.resolve(".").toString(), "/tmp/factory-pi");
            var observer = new PiProgress(config.apiKey(), progress);
            Processes.Result execution;
            try {
                var command =
                        new ArrayList<>(
                                List.of(
                                        "env",
                                        "PI_CODING_AGENT_DIR=/tmp/factory-pi",
                                        "pi",
                                        "--print",
                                        "--mode",
                                        "json",
                                        "--no-session",
                                        "--no-extensions",
                                        "--no-skills",
                                        "--no-prompt-templates",
                                        "--no-themes",
                                        "--offline",
                                        "--provider",
                                        config.provider(),
                                        "--model",
                                        config.model()));
                if (config.reasoningEffort() != null)
                    command.addAll(List.of("--thinking", config.reasoningEffort()));
                command.add("@/tmp/factory-pi/task.txt");
                execution = workload.execute(command, timeoutSeconds, EVENT_STREAM_LIMIT, observer);
            } catch (RuntimeException e) {
                return CodingOutcome.failed(
                        "RUNTIME_ERROR",
                        redact(safeFailure(e)),
                        PiProtocol.withIdentity(
                                observer.metrics(), config.provider(), config.model()));
            }
            if (execution.exitCode() != 0) {
                return CodingOutcome.failed(
                        "RUNTIME_EXIT",
                        "Pi exited with code " + execution.exitCode(),
                        PiProtocol.withIdentity(
                                observer.metrics(), config.provider(), config.model()));
            }
            var parsed = PiProtocol.parse(execution.output(), config.provider(), config.model());
            return sanitize(
                    new CodingOutcome(
                            parsed.status(),
                            parsed.summary(),
                            parsed.decision(),
                            parsed.failure(),
                            PiProtocol.withIdentity(
                                    observer.metrics(), config.provider(), config.model())));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare Pi input", e);
        } finally {
            CandidateFreezer.cleanup(temporary);
        }
    }

    private CodingOutcome sanitize(CodingOutcome outcome) {
        return switch (outcome.status()) {
            case COMPLETED -> CodingOutcome.completed(redact(outcome.summary()), outcome.metrics());
            case NEEDS_DECISION -> {
                var decision = outcome.decision();
                yield CodingOutcome.needsDecision(
                        new CodingOutcome.Decision(
                                decision.kind(),
                                redact(decision.question()),
                                decision.options().stream().map(this::redact).toList(),
                                redact(decision.evidence())),
                        outcome.metrics());
            }
            case FAILED ->
                    CodingOutcome.failed(
                            outcome.failure().code(),
                            redact(outcome.failure().message()),
                            outcome.metrics());
        };
    }

    private static String safeFailure(RuntimeException error) {
        String message =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(2000, message.length()));
    }
}
