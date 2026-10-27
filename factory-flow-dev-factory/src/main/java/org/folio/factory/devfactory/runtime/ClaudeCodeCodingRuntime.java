package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.candidate.CandidateFreezer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One stateless print-mode session in the existing Factory-owned coding workload. */
public final class ClaudeCodeCodingRuntime implements CodingRuntime {
    public static final String VERSION = "2.1.287";
    private static final String INPUT = "/tmp/factory-claude";
    private static final String TOOLS = "Bash,Read,Edit,Write,Glob,Grep";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeCodingRuntime.class);
    private final DevRuntimeProperties.ClaudeCode config;

    public ClaudeCodeCodingRuntime(DevRuntimeProperties.ClaudeCode config) { this.config = config; }

    @Override public String id() { return "claude-code"; }
    @Override public String image() { return config.image(); }
    @Override public void requireConfigured() { config.requireConfigured(); }

    @Override public Map<String, Object> identity() {
        var identity = new LinkedHashMap<>(CodingRuntime.super.identity());
        identity.put("provider", "anthropic-messages");
        if (config.model() != null && !config.model().isBlank()) identity.put("model", config.model());
        return Map.copyOf(identity);
    }

    @Override public String redact(String value) {
        if (value == null) return null;
        for (String secret : new String[] {config.apiKey(), config.authToken()})
            if (secret != null && !secret.isBlank()) value = value.replace(secret, "[REDACTED]");
        return value;
    }

    @Override public CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds) {
        return code(workload, request, timeoutSeconds, event -> {});
    }

    @Override public CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds,
                                        Consumer<Map<String, Object>> progress) {
        return code(workload, request, timeoutSeconds, progress, CodingTranscripts.NONE);
    }

    @Override public CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds,
                                        Consumer<Map<String, Object>> progress, CodingTranscripts.Sink transcript) {
        requireConfigured();
        request.requireReady();
        var protocol = new ClaudeCodeProtocol(config.model(), config.maxTurns(), this::redact, progress, transcript);
        Path temporary = null;
        try {
            var version = workload.execute(List.of("claude", "--version"), 30);
            if (version.exitCode() != 0 || !version.output().strip().equals(VERSION + " (Claude Code)"))
                return protocol.fail("RUNTIME_VERSION", "The coding image does not contain Claude Code " + VERSION
                        + ". Build the coding image again or fix the image name in the Developer Flow settings,"
                        + " then retry.");
            temporary = CandidateFreezer.temporary("factory-dev-claude-");
            String prompt = CodingProtocol.prompt(request);
            Files.writeString(temporary.resolve("environment"), environment());
            Files.setPosixFilePermissions(temporary.resolve("environment"), PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary.resolve("task.txt"), prompt);
            workload.execute(List.of("mkdir", "-p", INPUT), 30).requireSuccess();
            workload.copy(temporary.resolve(".").toString(), INPUT);
            protocol.prompt(prompt);
            var run = workload.stream(command(), timeoutSeconds, this::redact, line -> {
                protocol.accept(line);
                return !protocol.failed();
            });
            if (run.outputUnread())
                // The reader still owns the parser state, so this outcome must not read it.
                return CodingOutcome.failed("OUTPUT_UNREAD", "Claude Code ended, but Factory could not finish"
                        + " reading its output in time, often because the Factory database was slow. Retry the task.",
                        Map.of());
            return protocol.outcome(run, timeoutSeconds);
        } catch (IOException | RuntimeException e) {
            // Diagnostics can echo request bodies: they go to the log redacted, never into the stored reason.
            log.warn("Claude Code could not run in the coding container: {}", redact(String.valueOf(e.getMessage())));
            return protocol.fail("RUNTIME_ERROR", "Factory could not prepare or start Claude Code in the coding"
                    + " container. Check that Docker is running and the coding image exists, then retry. The"
                    + " Factory log has the details.");
        } finally {
            CandidateFreezer.cleanup(temporary);
            // Only adapter input, outside the exported workspace. Caller still owns stop/export/close.
            try {
                workload.execute(List.of("rm", "-rf", INPUT), 30);
            } catch (RuntimeException ignored) {
                // The caller removes the whole container.
            }
        }
    }

    private List<String> command() {
        var command = new ArrayList<>(List.of("/bin/sh", "-c",
                "set -a; . " + INPUT + "/environment; set +a; exec \"$@\" < " + INPUT + "/task.txt", "--",
                "claude", "--bare", "--print", "--output-format", "stream-json", "--verbose",
                "--include-partial-messages", "--no-session-persistence", "--setting-sources", "", "--strict-mcp-config",
                "--mcp-config", "{\"mcpServers\":{}}", "--tools", TOOLS, "--allowedTools", TOOLS,
                "--permission-mode", "dontAsk", "--permission-prompts", "none",
                "--model", config.model(), "--max-turns", config.maxTurns().toString()));
        if (config.effort() != null && !config.effort().isBlank())
            command.addAll(List.of("--effort", config.effort()));
        return command;
    }

    private String environment() {
        // No credential on docker exec argv or container configuration; no host Claude state.
        return "unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN CLAUDE_CODE_OAUTH_TOKEN\n"
                + "ANTHROPIC_BASE_URL=" + quote(config.baseUrl()) + "\n"
                + ("api-key".equals(config.authentication()) ? "ANTHROPIC_API_KEY=" : "ANTHROPIC_AUTH_TOKEN=")
                + quote(config.secret()) + "\n"
                + modelEnvironment()
                + "CLAUDE_CODE_MAX_OUTPUT_TOKENS=" + config.maxOutputTokens() + "\n"
                + "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1\nDISABLE_AUTOUPDATER=1\nDISABLE_UPDATES=1\n";
    }

    private String modelEnvironment() {
        // Pin CLI aliases to the operator's literal ID, including its default selection.
        var environment = new StringBuilder();
        for (String name : List.of("ANTHROPIC_DEFAULT_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
                "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_FABLE_MODEL"))
            environment.append(name).append('=').append(quote(config.model())).append('\n');
        // The supported request-body setting also preserves IDs containing CLI context suffixes.
        environment.append("CLAUDE_CODE_EXTRA_BODY=")
                .append(quote(JSON.writeValueAsString(Map.of("model", config.model())))).append('\n');
        environment.append("CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP=1\n");
        // Otherwise the CLI decorates the special default selection as default[1m] in init.
        if ("default".equalsIgnoreCase(config.model()))
            environment.append("CLAUDE_CODE_DISABLE_1M_CONTEXT=1\n");
        return environment.toString();
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
