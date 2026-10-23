package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeCodeCodingRuntimeTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"custom/Model-1\"}\n";
    private static final String RESULT = """
            {"type":"result","subtype":"success","is_error":false,"result":"Done secret-key","usage":{"input_tokens":40,"output_tokens":5,"cache_read_input_tokens":3,"cache_creation_input_tokens":2},"total_cost_usd":0.01}
            """;

    @ParameterizedTest
    @ValueSource(strings = {"api-key", "bearer-token"})
    void attemptUsesPrivateCredentialsExactModelAndConfirmedContext(String authentication) throws Exception {
        var runtime = new ClaudeCodeCodingRuntime(config(authentication));
        var workload = workload();
        var temporary = new AtomicReference<Path>();
        doAnswer(call -> {
            Path directory = Path.of((String) call.getArgument(0));
            temporary.set(directory);
            String environment = Files.readString(directory.resolve("environment"));
            assertThat(environment).contains("ANTHROPIC_BASE_URL='http://gateway.example/anthropic'");
            assertThat(environment).contains("ANTHROPIC_DEFAULT_MODEL='custom/Model-1'",
                    "CLAUDE_CODE_EXTRA_BODY='{\"model\":\"custom/Model-1\"}'",
                    "CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP=1");
            assertThat(environment).contains(authentication.equals("api-key")
                    ? "ANTHROPIC_API_KEY='secret-key'" : "ANTHROPIC_AUTH_TOKEN='secret-token'");
            assertThat(environment).doesNotContain(authentication.equals("api-key")
                    ? "ANTHROPIC_AUTH_TOKEN=" : "ANTHROPIC_API_KEY=");
            assertThat(Files.getPosixFilePermissions(directory.resolve("environment")))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.readString(directory.resolve("task.txt")))
                    .contains("Use public API", "REPOSITORY_MISMATCH", "Factory independently verifies");
            return null;
        }).when(workload).copy(anyString(), eq("/tmp/factory-claude"));
        var events = new ArrayList<Map<String, Object>>();
        when(workload.execute(anyList(), eq(60), eq(ClaudeCodeCodingRuntime.EVENT_STREAM_LIMIT), any()))
                .thenAnswer(call -> {
                    List<String> argv = call.getArgument(0);
                    assertThat(argv).containsSubsequence("--model", "custom/Model-1")
                            .containsSubsequence("--permission-mode", "dontAsk", "--permission-prompts", "none")
                            .contains("--bare", "--print", "stream-json", "--include-partial-messages", "--no-session-persistence", "--allowedTools")
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

    @Test
    void transportFailureRetainsPartialUsageAndNeverPersistsRawDiagnostics() {
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

    @Test
    void versionMismatchDoesNotCopyCredentialsOrStartTheCli() {
        var workload = workload();
        when(workload.execute(List.of("claude", "--version"), 30)).thenReturn(new Processes.Result(0, "other"));
        assertThat(new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 60).failure().code())
                .isEqualTo("RUNTIME_VERSION");
        verify(workload, never()).copy(anyString(), anyString());
    }

    @Test
    void strictTerminalResultIsRequiredAndProcessFailureCannotAuthorizeSuccess() {
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

    @Test
    void terminalUsageReplacesIntermediateSnapshotsAndProgressIsBounded() {
        var events = new ArrayList<Map<String, Object>>();
        var protocol = new ClaudeCodeProtocol("custom/Model-1", s -> s.replace("secret-key", "[REDACTED]"), events::add);
        protocol.accept(INIT);
        protocol.accept(assistant("a", 10));
        protocol.accept(assistant("a", 12));
        assertThat(protocol.metrics()).containsEntry("inputTokens", 12L).containsEntry("usageIncomplete", true);
        for (int i = 0; i < 250; i++) {
            protocol.accept("""
                    {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"private secret-key"}}]}}
                    """);
        }
        protocol.accept(RESULT);
        assertThat(protocol.metrics()).containsEntry("inputTokens", 40L).containsEntry("outputTokens", 5L)
                .doesNotContainKey("usageIncomplete");
        assertThat(events.stream().filter(e -> !"coding_usage".equals(e.get("activity"))).count()).isEqualTo(200);
        assertThat(events.toString()).doesNotContain("private", "secret-key");
        assertThat(events.getLast()).containsEntry("activity", "coding_usage");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"input_tokens\":0,\"output_tokens\":0}",
            "{\"input_tokens\":40}", "{\"output_tokens\":5}",
            "{\"input_tokens\":-1,\"output_tokens\":5}", "{\"input_tokens\":40,\"output_tokens\":-1}",
            "{\"input_tokens\":0,\"output_tokens\":5}", "{\"input_tokens\":40,\"output_tokens\":0}"
    })
    void missingOrSyntheticUsageCannotAuthorizeCompleteAccounting(String usage) {
        ObjectNode result = (ObjectNode) JSON.readTree(RESULT);
        result.set("usage", JSON.readTree(usage));
        var outcome = parse(INIT + result, 0);
        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(outcome.metrics()).containsEntry("usageIncomplete", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cache_read_input_tokens", "cache_creation_input_tokens"})
    void fullyCachedInputStillProvidesUsageEvidence(String cacheCounter) {
        var outcome = parse(INIT + RESULT.replace("\"input_tokens\":40", "\"input_tokens\":0")
                .replace("\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":2", "\"" + cacheCounter + "\":40"), 0);
        assertThat(outcome.metrics()).containsEntry("inputTokens", 0L).containsEntry("outputTokens", 5L)
                .doesNotContainKey("usageIncomplete");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"usage\":{\"input_tokens\":0,\"output_tokens\":0}"})
    void terminalTotalsCannotHideAnUnaccountedMessage(String usage) {
        String missing = "{\"type\":\"assistant\",\"message\":{\"id\":\"missing\"" + usage + "}}\n";
        var outcome = parse(INIT + missing + assistant("known", 40) + "\n" + RESULT, 0);
        assertThat(outcome.metrics()).containsEntry("inputTokens", 40L).containsEntry("outputTokens", 5L)
                .containsEntry("usageIncomplete", true);
    }

    @Test
    void finalMessageSnapshotCanCompleteEarlierMissingUsage() {
        String missing = "{\"type\":\"assistant\",\"message\":{\"id\":\"same\"}}\n";
        var outcome = parse(INIT + missing + assistant("same", 40) + "\n" + RESULT, 0);
        assertThat(outcome.metrics()).containsEntry("inputTokens", 40L).doesNotContainKey("usageIncomplete");
    }

    @Test
    void streamedFinalUsageCompletesTheIntermediateAssistantSnapshot() {
        String streamed = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"a","usage":{"input_tokens":40,"output_tokens":0,"cache_creation_input_tokens":2,"cache_creation":{"ephemeral_5m_input_tokens":2,"ephemeral_1h_input_tokens":0}}}}}
                {"type":"assistant","message":{"id":"a","usage":{"input_tokens":40,"output_tokens":0,"cache_creation_input_tokens":2,"cache_creation":{"ephemeral_5m_input_tokens":2,"ephemeral_1h_input_tokens":0}}}}
                {"type":"stream_event","event":{"type":"message_delta","usage":{"output_tokens":5}}}
                {"type":"stream_event","event":{"type":"message_stop"}}
                """;
        assertThat(parse(INIT + streamed + RESULT, 0).metrics())
                .containsEntry("inputTokens", 40L).containsEntry("outputTokens", 5L).doesNotContainKey("usageIncomplete");
        assertThat(parse(INIT + streamed, 1).metrics())
                .containsEntry("inputTokens", 40L)
                .containsEntry("outputTokens", 5L)
                .containsEntry("cacheWrite5mTokens", 2L)
                .containsEntry("cacheWrite1hTokens", 0L)
                .containsEntry("usageIncomplete", true);
    }

    @Test
    void decisionsUseTheExistingFactoryContractAndRedactSecrets() {
        String answer = "FACTORY_DECISION_REQUIRED: {\"kind\":\"REPOSITORY_MISMATCH\",\"question\":\"Which repo secret-key?\",\"options\":[],\"evidence\":\"No owner\"}";
        String result = JSON.writeValueAsString(Map.of("type", "result", "subtype", "success",
                "is_error", false, "result", answer));
        var outcome = parse(INIT + result, 0);
        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        assertThat(outcome.decision().kind()).isEqualTo(CodingOutcome.DecisionKind.REPOSITORY_MISMATCH);
        assertThat(outcome.decision().question()).contains("[REDACTED]");
        assertThat(parse(INIT + result.replace("REPOSITORY_MISMATCH", "UNKNOWN"), 0).failure().code())
                .isEqualTo("INVALID_DECISION_RESULT");
    }

    @Test
    void cacheTtlCountersSurviveTerminalReplacementWithoutDoubleCounting() {
        ObjectNode terminalResult = resultWithCacheTtl(1L, 1L);
        var protocol = new ClaudeCodeProtocol("custom/Model-1", text -> text, event -> {});
        protocol.accept(INIT);
        protocol.accept("""
                {"type":"assistant","message":{"id":"a","usage":{"input_tokens":40,"output_tokens":5,
                "cache_creation_input_tokens":2,"cache_creation":{"ephemeral_5m_input_tokens":2,"ephemeral_1h_input_tokens":0}}}}
                """);
        assertThat(protocol.metrics())
                .containsEntry("cacheWrite5mTokens", 2L)
                .containsEntry("cacheWrite1hTokens", 0L);

        protocol.accept(terminalResult.toString());

        assertThat(protocol.outcome(0).metrics()).containsEntry("cacheWriteTokens", 2L)
                .containsEntry("cacheWrite5mTokens", 1L).containsEntry("cacheWrite1hTokens", 1L)
                .doesNotContainKey("usageIncomplete");
    }

    @Test
    void terminalUsageDoesNotInheritCacheTtlFromIntermediateMessages() {
        var protocol = new ClaudeCodeProtocol("custom/Model-1", text -> text, event -> {});
        protocol.accept(INIT);
        protocol.accept("""
                {"type":"assistant","message":{"id":"a","usage":{"input_tokens":40,"output_tokens":5,
                "cache_creation_input_tokens":2,"cache_creation":{"ephemeral_5m_input_tokens":2,"ephemeral_1h_input_tokens":0}}}}
                """);

        protocol.accept(RESULT);

        assertThat(protocol.outcome(0).metrics())
                .containsEntry("cacheWriteTokens", 2L)
                .doesNotContainKeys("cacheWrite5mTokens", "cacheWrite1hTokens");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "null", "\"unknown\""})
    void invalidCacheTtlCountersRemainUnknown(String invalidCounter) {
        ObjectNode terminalResult = resultWithCacheTtl(2L, 0L);
        ObjectNode usage = (ObjectNode) terminalResult.path("usage");
        ObjectNode cacheCreation = (ObjectNode) usage.path("cache_creation");
        cacheCreation.set("ephemeral_5m_input_tokens", JSON.readTree(invalidCounter));

        CodingOutcome outcome = parse(INIT + terminalResult, 0);

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(outcome.metrics())
                .containsEntry("cacheWriteTokens", 2L)
                .containsEntry("cacheWrite1hTokens", 0L)
                .doesNotContainKey("cacheWrite5mTokens");
    }

    private static ObjectNode resultWithCacheTtl(long fiveMinuteTokens, long oneHourTokens) {
        ObjectNode result = (ObjectNode) JSON.readTree(RESULT);
        ObjectNode usage = (ObjectNode) result.path("usage");
        ObjectNode cacheCreation = JSON.createObjectNode()
                .put("ephemeral_5m_input_tokens", fiveMinuteTokens)
                .put("ephemeral_1h_input_tokens", oneHourTokens);
        usage.set("cache_creation", cacheCreation);
        return result;
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
