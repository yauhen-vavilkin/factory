package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.candidate.CandidateFreezer;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Pi adapter for the Factory-owned coding request and outcome contracts. */
public class PiCodingRuntime implements CodingRuntime {
    /** Built-in API types of the pinned Pi 0.85.1 image (pi-ai KnownApi); every API a model entry can name. */
    public static final List<String> APIS = List.of("openai-completions", "openai-responses", "anthropic-messages",
            "mistral-conversations", "azure-openai-responses", "openai-codex-responses", "bedrock-converse-stream",
            "google-generative-ai", "google-vertex", "pi-messages");
    static final int EVENT_STREAM_LIMIT = 16 * 1024 * 1024;
    private static final String PI_VERSION = "0.85.1";
    private static final String PI_DIRECTORY = "/tmp/factory-pi";
    private final DevRuntimeProperties.Pi config;
    private final JsonMapper json = JsonMapper.builder().build();

    public PiCodingRuntime(DevRuntimeProperties.Pi config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "pi";
    }

    @Override
    public String image() {
        return config.image();
    }

    /** Run profiles apply to Claude Code only; Pi always uses its one configuration. */
    @Override
    public void requireConfigured(CodingRequest.RunSettings settings) {
        config.requireConfigured();
    }

    @Override
    public Map<String, Object> identity(CodingRequest.RunSettings settings) {
        var identity = new LinkedHashMap<>(CodingRuntime.super.identity(settings));
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
            DockerWorkloads.Workload workload,
            CodingRequest request,
            int timeoutSeconds,
            Consumer<Map<String, Object>> progress,
            CodingTranscripts.Sink transcript) {
        requireConfigured(request.runSettings());
        request.requireReady();
        requirePiVersion(workload);
        var temporary = CandidateFreezer.temporary("factory-dev-pi-");
        try {
            writeInputs(temporary, request);
            workload.execute(List.of("mkdir", "-p", PI_DIRECTORY), 30, Processes.OUTPUT_LIMIT, null).requireSuccess();
            workload.copy(temporary.resolve(".").toString(), PI_DIRECTORY);
            return run(workload, timeoutSeconds, new PiProgress(config.apiKey(), progress));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare Pi input", e);
        } finally {
            CandidateFreezer.cleanup(temporary);
        }
    }

    private static void requirePiVersion(DockerWorkloads.Workload workload) {
        var version = workload.execute(List.of("pi", "--version"), 30, Processes.OUTPUT_LIMIT, null)
                .requireSuccess().output().strip();
        if (!version.equals(PI_VERSION)) {
            throw new IllegalStateException("Coding image must contain Pi " + PI_VERSION);
        }
    }

    private void writeInputs(Path temporary, CodingRequest request) throws IOException {
        var models = Map.of("providers", Map.of(config.provider(), providerSettings()));
        Files.writeString(temporary.resolve("models.json"), json.writeValueAsString(models));
        Files.writeString(temporary.resolve("task.txt"), CodingProtocol.prompt(request));
    }

    private Map<String, Object> providerSettings() {
        var provider = new LinkedHashMap<String, Object>();
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            provider.put("baseUrl", config.baseUrl());
        }
        provider.put("api", config.api());
        provider.put("apiKey", config.apiKey());
        provider.put("models", List.of(modelSettings()));
        return provider;
    }

    private Map<String, Object> modelSettings() {
        var model = new LinkedHashMap<String, Object>();
        model.put("id", config.model());
        model.put("name", config.model());
        model.put("contextWindow", 200000);
        model.put("maxTokens", config.maxOutputTokens());
        if (config.reasoningEffort() != null) {
            model.put("reasoning", true);
        }
        if (config.provider().equals("codemie") && config.model().equals("gemini-3.8-flash")) {
            model.put("compat", Map.of("supportsDeveloperRole", false, "maxTokensField", "max_tokens"));
        }
        return model;
    }

    private CodingOutcome run(DockerWorkloads.Workload workload, int timeoutSeconds, PiProgress observer) {
        Processes.Result execution;
        try {
            execution = workload.execute(command(), timeoutSeconds, EVENT_STREAM_LIMIT, observer);
        } catch (RuntimeException e) {
            return CodingOutcome.failed("RUNTIME_ERROR", redact(safeFailure(e)), metrics(observer));
        }
        if (execution.exitCode() != 0) {
            return CodingOutcome.failed(
                    "RUNTIME_EXIT", "Pi exited with code " + execution.exitCode(), metrics(observer));
        }
        var parsed = PiProtocol.parse(execution.output(), config.provider(), config.model());
        return sanitize(new CodingOutcome(
                parsed.status(), parsed.summary(), parsed.decision(), parsed.failure(), metrics(observer)));
    }

    private List<String> command() {
        var command = new ArrayList<>(List.of(
                "env",
                "PI_CODING_AGENT_DIR=" + PI_DIRECTORY,
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
        if (config.reasoningEffort() != null) {
            command.addAll(List.of("--thinking", config.reasoningEffort()));
        }
        command.add("@" + PI_DIRECTORY + "/task.txt");
        return command;
    }

    private Map<String, Object> metrics(PiProgress observer) {
        return PiProtocol.withIdentity(observer.metrics(), config.provider(), config.model());
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
