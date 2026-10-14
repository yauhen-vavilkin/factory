package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudeCodeCodingRuntimeTest {
    private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"custom/Model-1\"}\n";
    private static final String RESULT = """
            {"type":"result","subtype":"success","is_error":false,"result":"Done secret-key","usage":{"input_tokens":40,"output_tokens":5,"cache_read_input_tokens":3,"cache_creation_input_tokens":2},"total_cost_usd":0.01}
            """;

    @ParameterizedTest @ValueSource(strings = {"api-key", "bearer-token"})
    void attemptUsesPrivateCredentialsExactModelAndConfirmedContext(String authentication) throws Exception {
        var runtime = new ClaudeCodeCodingRuntime(config(authentication));
        var workload = workload();
        var temporary = new AtomicReference<Path>();
        doAnswer(call -> {
            Path directory = Path.of((String) call.getArgument(0));
            temporary.set(directory);
            String environment = Files.readString(directory.resolve("environment"));
            assertThat(environment).contains("ANTHROPIC_BASE_URL='http://gateway.example/anthropic'");
            assertThat(environment).contains(authentication.equals("api-key")
                    ? "ANTHROPIC_API_KEY='secret-key'" : "ANTHROPIC_AUTH_TOKEN='secret-token'");
            assertThat(environment).doesNotContain(authentication.equals("api-key")
                    ? "ANTHROPIC_AUTH_TOKEN=" : "ANTHROPIC_API_KEY=");
            assertThat(Files.getPosixFilePermissions(directory.resolve("environment")))
                    .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.readString(directory.resolve("task.txt")))
                    .contains("Use public API", "REPOSITORY_MISMATCH", "Factory independently verifies");
            return null;
        }).when(workload).copy(anyString(), eq("/tmp/factory-claude"));
        var events = new ArrayList<Map<String, Object>>();
        when(workload.execute(anyList(), eq(60), eq(ClaudeCodeCodingRuntime.EVENT_STREAM_LIMIT), any()))
                .thenAnswer(call -> {
                    List<String> argv = call.getArgument(0);
                    assertThat(argv).containsSubsequence("--model", "custom/Model-1")
                            .contains("--bare", "--print", "stream-json", "--no-session-persistence", "--allowedTools")
                            .doesNotContain("secret-key", "secret-token", "--continue", "--resume");
                    Consumer<String> progress = call.getArgument(3);
                    (INIT + RESULT).lines().forEach(progress);
                    return new Processes.Result(0, INIT + RESULT);
                });
        var outcome = runtime.code(workload, request().withDecision(new CodingRequest.ConfirmedDecision(
                "decision", "PRODUCT_REQUIREMENTS", "Which API?", "Use public API")), 60, events::add);
        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(outcome.summary()).isEqualTo("Done [REDACTED]");
        assertThat(outcome.metrics()).containsEntry("inputTokens", 40L).containsEntry("cacheReadTokens", 3L)
                .containsEntry("outputTokens", 5L).doesNotContainKey("usageIncomplete").doesNotContainKey("costUsd");
        assertThat(events.toString() + runtime.identity() + config(authentication))
                .doesNotContain("secret-key", "secret-token", "gateway.example");
        assertThat(Files.exists(temporary.get())).isFalse();
        verify(workload).execute(List.of("rm", "-rf", "/tmp/factory-claude"), 30);
        verify(workload, never()).stop(); // Orchestrator must still export partial edits before close.
        verify(workload, never()).close();
    }

    @Test void transportFailureRetainsPartialUsageAndNeverPersistsRawDiagnostics() {
        var workload = workload();
        when(workload.execute(anyList(), eq(60), eq(ClaudeCodeCodingRuntime.EVENT_STREAM_LIMIT), any()))
                .thenAnswer(call -> {
                    Consumer<String> progress = call.getArgument(3);
                    progress.accept(assistant("a", 5));
                    throw new IllegalStateException("private request secret-key secret-token");
                });
        var outcome = new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 60);
        assertThat(outcome.failure().code()).isEqualTo("RUNTIME_ERROR");
        assertThat(outcome.toString()).doesNotContain("secret-key", "secret-token", "private request");
        assertThat(outcome.metrics()).containsEntry("inputTokens", 5L).containsEntry("usageIncomplete", true);
        verify(workload).execute(List.of("rm", "-rf", "/tmp/factory-claude"), 30);
    }

    @Test void versionMismatchDoesNotCopyCredentialsOrStartTheCli() {
        var workload = workload();
        when(workload.execute(List.of("claude", "--version"), 30)).thenReturn(new Processes.Result(0, "other"));
        assertThat(new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 60).failure().code())
                .isEqualTo("RUNTIME_VERSION");
        verify(workload, never()).copy(anyString(), anyString());
    }

    @Test void strictTerminalResultIsRequiredAndProcessFailureCannotAuthorizeSuccess() {
        assertThat(parse(INIT + assistant("a", 10), 0).failure().code()).isEqualTo("INCOMPLETE_RESULT");
        assertThat(parse(INIT + RESULT, 1).failure().code()).isEqualTo("RUNTIME_EXIT");
        assertThat(parse(INIT + RESULT + RESULT, 0).failure().code()).isEqualTo("INVALID_RESULT_SEQUENCE");
        assertThat(parse(INIT + "not json", 0).failure().code()).isEqualTo("MALFORMED_FRAME");
        assertThat(parse(INIT + "x".repeat(512 * 1024 + 1), 0).failure().code()).isEqualTo("FRAME_TOO_LARGE");
        assertThat(parse(INIT.replace("custom/Model-1", "wrong") + RESULT, 0).failure().code()).isEqualTo("IDENTITY_MISMATCH");
        assertThat(parse(RESULT, 0).failure().code()).isEqualTo("INCOMPLETE_RESULT");
        assertThat(parse(INIT + RESULT.replace("\"is_error\":false,", ""), 0).status()).isEqualTo(CodingOutcome.Status.FAILED);
        assertThat(parse(INIT + RESULT.replace("success", "error_max_turns"), 1).failure().code()).isEqualTo("TURN_LIMIT");
        assertThat(parse(INIT + RESULT.replace("\"is_error\":false", "\"is_error\":true"), 0).failure().code()).isEqualTo("PROVIDER_FAILED");
    }

    @Test void terminalUsageReplacesIntermediateSnapshotsAndProgressIsBounded() {
        var events = new ArrayList<Map<String, Object>>();
        var protocol = new ClaudeCodeProtocol("custom/Model-1", s -> s.replace("secret-key", "[REDACTED]"), events::add);
        protocol.accept(INIT);
        protocol.accept(assistant("a", 10));
        protocol.accept(assistant("a", 12));
        assertThat(protocol.metrics()).containsEntry("inputTokens", 12L).containsEntry("usageIncomplete", true);
        for (int i = 0; i < 250; i++) protocol.accept("""
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"private secret-key"}}]}}
                """);
        protocol.accept(RESULT);
        assertThat(protocol.metrics()).containsEntry("inputTokens", 40L).containsEntry("outputTokens", 5L);
        assertThat(events.stream().filter(e -> !"coding_usage".equals(e.get("activity"))).count()).isEqualTo(200);
        assertThat(events.toString()).doesNotContain("private", "secret-key");
        assertThat(events.getLast()).containsEntry("activity", "coding_usage");
    }

    @Test void decisionsUseTheExistingFactoryContractAndRedactSecrets() {
        String answer = "FACTORY_DECISION_REQUIRED: {\"kind\":\"REPOSITORY_MISMATCH\",\"question\":\"Which repo secret-key?\",\"options\":[],\"evidence\":\"No owner\"}";
        String result = JsonMapper.builder().build().writeValueAsString(Map.of("type", "result", "subtype", "success",
                "is_error", false, "result", answer));
        var outcome = parse(INIT + result, 0);
        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        assertThat(outcome.decision().kind()).isEqualTo(CodingOutcome.DecisionKind.REPOSITORY_MISMATCH);
        assertThat(outcome.decision().question()).contains("[REDACTED]");
        assertThat(parse(INIT + result.replace("REPOSITORY_MISMATCH", "UNKNOWN"), 0).failure().code())
                .isEqualTo("INVALID_DECISION_RESULT");
    }

    private static CodingOutcome parse(String text, int exitCode) {
        var protocol = new ClaudeCodeProtocol("custom/Model-1", s -> s.replace("secret-key", "[REDACTED]"), e -> {});
        text.lines().forEach(protocol);
        return protocol.outcome(exitCode);
    }

    private static String assistant(String id, int input) {
        return "{\"type\":\"assistant\",\"message\":{\"id\":\"" + id + "\",\"usage\":{\"input_tokens\":" + input + ",\"output_tokens\":2}}}";
    }

    private static DockerWorkloads.Workload workload() {
        var workload = mock(DockerWorkloads.Workload.class);
        when(workload.execute(anyList(), eq(30))).thenReturn(new Processes.Result(0, ""));
        when(workload.execute(List.of("claude", "--version"), 30))
                .thenReturn(new Processes.Result(0, ClaudeCodeCodingRuntime.VERSION + " (Claude Code)"));
        return workload;
    }

    private static DevRuntimeProperties.ClaudeCode config(String authentication) {
        return new DevRuntimeProperties.ClaudeCode(null, null, "http://gateway.example/anthropic", "custom/Model-1",
                authentication, "secret-key", "secret-token", 10, 4096, "high");
    }

    static CodingRequest request() {
        return new CodingRequest("TASK-1", "Implement task", "Description", List.of(), List.of(), List.of(), List.of(),
                new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)), null);
    }
}
