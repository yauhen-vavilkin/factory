package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.candidate.CandidateFreezer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** One stateless print-mode session in the existing Factory-owned coding workload. */
public final class ClaudeCodeCodingRuntime implements CodingRuntime {
    public static final String VERSION = "2.1.287";
    static final int EVENT_STREAM_LIMIT = 16 * 1024 * 1024;
    private static final String INPUT = "/tmp/factory-claude";
    private static final String TOOLS = "Bash,Read,Edit,Write,Glob,Grep";
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
        requireConfigured();
        request.requireReady();
        var version = workload.execute(List.of("claude", "--version"), 30);
        if (version.exitCode() != 0 || !version.output().strip().equals(VERSION + " (Claude Code)"))
            return CodingOutcome.failed("RUNTIME_VERSION", "Coding image must contain Claude Code " + VERSION, Map.of());
        var temporary = CandidateFreezer.temporary("factory-dev-claude-");
        var observer = new ClaudeCodeProtocol(config.model(), this::redact, progress);
        try {
            Files.writeString(temporary.resolve("environment"), environment());
            Files.setPosixFilePermissions(temporary.resolve("environment"), PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary.resolve("task.txt"), CodingProtocol.prompt(request));
            workload.execute(List.of("mkdir", "-p", INPUT), 30).requireSuccess();
            workload.copy(temporary.resolve(".").toString(), INPUT);
            var execution = workload.execute(command(), timeoutSeconds, EVENT_STREAM_LIMIT, observer);
            // Re-parse authoritative stdout. Best-effort progress callbacks cannot authorize success.
            var protocol = new ClaudeCodeProtocol(config.model(), this::redact, event -> {});
            execution.output().lines().forEach(protocol);
            return protocol.outcome(execution.exitCode());
        } catch (IOException | RuntimeException e) {
            // Transport/CLI diagnostics can echo credentials or request bodies; never persist them.
            return CodingOutcome.failed("RUNTIME_ERROR", "Claude Code input or execution failed; the attempt may be incomplete",
                    observer.metrics());
        } finally {
            CandidateFreezer.cleanup(temporary);
            // Only adapter input, outside the exported workspace. Caller still owns stop/export/close.
            workload.execute(List.of("rm", "-rf", INPUT), 30);
        }
    }

    private List<String> command() {
        var command = new ArrayList<>(List.of("/bin/sh", "-c",
                "set -a; . " + INPUT + "/environment; set +a; exec \"$@\" < " + INPUT + "/task.txt", "--",
                "claude", "--bare", "--print", "--output-format", "stream-json", "--verbose",
                "--no-session-persistence", "--setting-sources", "", "--strict-mcp-config",
                "--mcp-config", "{\"mcpServers\":{}}", "--tools", TOOLS, "--allowedTools", TOOLS,
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
                + "CLAUDE_CODE_MAX_OUTPUT_TOKENS=" + config.maxOutputTokens() + "\n"
                + "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1\nDISABLE_AUTOUPDATER=1\nDISABLE_UPDATES=1\n";
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
