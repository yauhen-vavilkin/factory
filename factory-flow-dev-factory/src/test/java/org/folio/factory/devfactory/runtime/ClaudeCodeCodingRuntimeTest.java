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
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
    private static final String TOOL_USE = """
            {"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"mvn test"}}]}}
            """;
    private static final String TOOL_RESULT = """
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","is_error":false,"content":"BUILD SUCCESS"}]}}
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
        var transcript = new ArrayList<CodingTranscripts.Entry>();
        when(workload.stream(anyList(), eq(60), any(), any()))
                .thenAnswer(call -> {
                    List<String> argv = call.getArgument(0);
                    assertThat(argv).containsSubsequence("--model", "custom/Model-1")
                            .containsSubsequence("--permission-mode", "dontAsk", "--permission-prompts", "none")
                            .contains("--bare", "--print", "stream-json", "--include-partial-messages", "--no-session-persistence", "--allowedTools")
                            .doesNotContain("secret-key", "secret-token", "--continue", "--resume");
                    UnaryOperator<String> redactor = call.getArgument(2);
                    assertThat(redactor.apply("stderr secret-key")).isEqualTo("stderr [REDACTED]");
                    return feed(call.getArgument(3), INIT + RESULT, 0, "");
                });
        var request = request().withDecision(new CodingRequest.ConfirmedDecision(
                "decision", "PRODUCT_REQUIREMENTS", "Which API?", "Use public API"));
        var outcome = runtime.code(workload, request, 60, events::add, transcript::add);
        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(transcript.getFirst().kind()).isEqualTo("prompt");
        assertThat(transcript.getFirst().detail()).containsEntry("text", CodingProtocol.prompt(request));
        assertThat(transcript.getLast().kind()).isEqualTo("result");
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
        when(workload.stream(anyList(), eq(60), any(), any()))
                .thenAnswer(call -> {
                    Predicate<String> lines = call.getArgument(3);
                    lines.test(assistant("a", 5));
                    throw new IllegalStateException("private request secret-key secret-token");
                });
        var outcome = new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 60);
        assertThat(outcome.failure().code()).isEqualTo("RUNTIME_ERROR");
        assertThat(outcome.failure().message()).contains("Check that Docker is running");
        assertThat(outcome.toString()).doesNotContain("secret-key", "secret-token", "private request");
        assertThat(outcome.metrics()).containsEntry("inputTokens", 5L).containsEntry("usageIncomplete", true);
        verify(workload).execute(List.of("rm", "-rf", "/tmp/factory-claude"), 30);
    }

    @Test
    void dockerFailureBeforeTheCliStartsIsAPlainReason() {
        var workload = workload();
        doThrow(new IllegalStateException("Command failed (exit 1): no such container secret-key"))
                .when(workload).copy(anyString(), eq("/tmp/factory-claude"));
        var transcript = new ArrayList<CodingTranscripts.Entry>();
        var outcome = new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 60, e -> { },
                transcript::add);
        assertThat(outcome.failure().code()).isEqualTo("RUNTIME_ERROR");
        assertThat(outcome.failure().message()).startsWith("Factory could not prepare or start Claude Code")
                .doesNotContain("secret-key", "no such container");
        assertThat(transcript).extracting(CodingTranscripts.Entry::kind).containsExactly("note");
        verify(workload, never()).stream(anyList(), anyInt(), any(), any());
    }

    @Test
    void versionMismatchDoesNotCopyCredentialsOrStartTheCli() {
        var workload = workload();
        when(workload.execute(List.of("claude", "--version"), 30)).thenReturn(new Processes.Result(0, "other"));
        var outcome = new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 60);
        assertThat(outcome.failure().code()).isEqualTo("RUNTIME_VERSION");
        assertThat(outcome.failure().message()).contains("Claude Code " + ClaudeCodeCodingRuntime.VERSION,
                "Build the coding image again");
        verify(workload, never()).copy(anyString(), anyString());
    }

    @Test
    void streamStopsAtTheFirstLineThatFailsTheAttempt() {
        var workload = workload();
        var answers = new ArrayList<Boolean>();
        when(workload.stream(anyList(), eq(60), any(), any())).thenAnswer(call -> {
            Predicate<String> lines = call.getArgument(3);
            for (String line : List.of(INIT.strip(), "not json", RESULT.strip())) {
                boolean keepReading = lines.test(line);
                answers.add(keepReading);
                if (!keepReading) break;
            }
            return new Processes.Streamed(137, false, false, "");
        });
        var outcome = new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 60);
        assertThat(answers).containsExactly(true, false);
        assertThat(outcome.failure().code()).isEqualTo("MALFORMED_FRAME");
        assertThat(outcome.failure().message()).contains("not a valid event", "Retry the task");
    }

    @Test
    void aReaderThatIsStillBusyNeverHasItsStateReadForTheOutcome() {
        var workload = workload();
        when(workload.stream(anyList(), eq(60), any(), any()))
                .thenReturn(new Processes.Streamed(0, false, false, "", true));
        var outcome = new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 60);
        assertThat(outcome.failure().code()).isEqualTo("OUTPUT_UNREAD");
        assertThat(outcome.failure().message()).contains("could not finish reading its output", "Retry the task");
    }

    @Test
    void timeLimitAndOversizedLineStopTheAttemptWithPlainReasons() {
        var workload = workload();
        when(workload.stream(anyList(), eq(1800), any(), any()))
                .thenAnswer(call -> {
                    feed(call.getArgument(3), INIT, -1, "");
                    return new Processes.Streamed(-1, true, false, "");
                });
        var timedOut = new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 1800);
        assertThat(timedOut.failure().code()).isEqualTo("TIME_LIMIT");
        assertThat(timedOut.failure().message()).contains("time limit of 30 minutes", "Retry with a lower effort");

        when(workload.stream(anyList(), eq(60), any(), any()))
                .thenReturn(new Processes.Streamed(137, false, true, ""));
        var oversized = new ClaudeCodeCodingRuntime(config("api-key")).code(workload, request(), 60);
        assertThat(oversized.failure().code()).isEqualTo("FRAME_TOO_LARGE");
        assertThat(oversized.failure().message()).contains("larger than 8 MB", "Retry the task");
    }

    @Test
    void strictTerminalResultIsRequiredAndProcessFailureCannotAuthorizeSuccess() {
        assertThat(parse(INIT + assistant("a", 10), 0).failure().code()).isEqualTo("INCOMPLETE_RESULT");
        assertThat(parse(INIT + RESULT, 1).failure().code()).isEqualTo("RUNTIME_EXIT");
        assertThat(parse(INIT + RESULT + RESULT, 0).failure().code()).isEqualTo("INVALID_RESULT_SEQUENCE");
        assertThat(parse(INIT + "not json", 0).failure().code()).isEqualTo("MALFORMED_FRAME");
        assertThat(parse(INIT + "x".repeat(512 * 1024 + 1), 0).failure().code()).isEqualTo("MALFORMED_FRAME");
        assertThat(parse(INIT.replace("custom/Model-1", "wrong") + RESULT, 0).failure().code()).isEqualTo("IDENTITY_MISMATCH");
        assertThat(parse(RESULT, 0).failure().code()).isEqualTo("INCOMPLETE_RESULT");
        assertThat(parse(INIT + RESULT.replace("\"is_error\":false,", ""), 0).status()).isEqualTo(CodingOutcome.Status.FAILED);
        assertThat(parse(INIT + RESULT.replace("success", "error_max_turns"), 1).failure().code()).isEqualTo("TURN_LIMIT");
        assertThat(parse(INIT + RESULT.replace("\"is_error\":false", "\"is_error\":true"), 0).failure().code()).isEqualTo("PROVIDER_FAILED");
    }

    @Test
    void terminalUsageReplacesIntermediateSnapshotsAndLongRunsKeepEveryActivityEvent() {
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
        // 250 tool starts, plus agent_start and agent_end: the old cap of 200 events is gone.
        assertThat(events.stream().filter(e -> !"coding_usage".equals(e.get("activity"))).count()).isEqualTo(252);
        assertThat(events.toString()).contains("command=private [REDACTED]").doesNotContain("secret-key");
        assertThat(events.getLast()).containsEntry("activity", "agent_end");
    }

    @Test
    void unsuccessfulResultsKeepTheCliSubtypeAndSayWhatToDo() {
        var turnLimit = parse(INIT + """
                {"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":11,"errors":["Reached maximum number of turns (10)"]}
                """, 1, 10);
        assertThat(turnLimit.failure().code()).isEqualTo("TURN_LIMIT");
        assertThat(turnLimit.failure().message())
                .isEqualTo("Claude Code used all 10 turns before it finished the task."
                        + " Retry with more turns or a different effort.");

        var budget = parse(INIT + """
                {"type":"result","subtype":"error_max_budget_usd","is_error":true,"errors":["Budget exceeded"]}
                """, 1, 10);
        assertThat(budget.failure().code()).isEqualTo("BUDGET_LIMIT");
        assertThat(budget.failure().message()).contains("spending limit", "Retry with a lower effort",
                "CLI message: Budget exceeded");

        var provider = parse(INIT + """
                {"type":"result","subtype":"error_during_execution","is_error":true,"errors":["API Error: 401 bad key secret-key"]}
                """, 1, 10);
        assertThat(provider.failure().code()).isEqualTo("PROVIDER_FAILED");
        assertThat(provider.failure().message())
                .startsWith("Claude Code stopped with an error (subtype error_during_execution).")
                .contains("Check the provider", "Error: API Error: 401 bad key [REDACTED]")
                .doesNotContain("secret-key");

        var apiError = parse(INIT + """
                {"type":"result","subtype":"success","is_error":true,"api_error_status":429,"result":"API Error: 429 %s"}
                """.formatted("x".repeat(1000)), 1, 10);
        assertThat(apiError.failure().message()).contains("(subtype success, API status 429)", "Error: API Error: 429 x");
        assertThat(apiError.failure().message().length()).isLessThan(480);
    }

    @Test
    void exitCodesKeepARedactedBoundedStderrTail() {
        var protocol = new ClaudeCodeProtocol("custom/Model-1", 10, s -> s.replace("secret-key", "[REDACTED]"),
                e -> { }, CodingTranscripts.NONE);
        protocol.accept(INIT);
        var exited = protocol.outcome(new Processes.Streamed(2, false, false,
                "y".repeat(5000) + "\nfatal: token secret-key rejected"), 60);
        assertThat(exited.failure().code()).isEqualTo("RUNTIME_EXIT");
        assertThat(exited.failure().message())
                .startsWith("Claude Code exited with code 2 before it returned a result. Check the coding runtime")
                .endsWith("fatal: token [REDACTED] rejected")
                .doesNotContain("secret-key");
        assertThat(exited.failure().message().length()).isLessThan(480);

        var docker = new ClaudeCodeProtocol("custom/Model-1", 10, s -> s, e -> { }, CodingTranscripts.NONE)
                .outcome(new Processes.Streamed(126, false, false, "OCI runtime exec failed"), 60);
        assertThat(docker.failure().code()).isEqualTo("RUNTIME_ERROR");
        assertThat(docker.failure().message()).contains(
                "Docker could not start Claude Code in the coding container (exit 126)", "OCI runtime exec failed");
    }

    @Test
    void anExceptionInsideTheParserFailsTheAttemptWithAPlainReason() {
        var protocol = new ClaudeCodeProtocol("custom/Model-1", 10, s -> {
            if (s.contains("boom")) throw new IllegalArgumentException("private");
            return s;
        }, e -> { }, CodingTranscripts.NONE);
        protocol.accept(INIT);
        protocol.accept("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"boom\"}]}}");
        assertThat(protocol.failed()).isTrue();
        var outcome = protocol.outcome(0);
        assertThat(outcome.failure().code()).isEqualTo("PARSER_ERROR");
        assertThat(outcome.failure().message()).contains("IllegalArgumentException", "Retry the task")
                .doesNotContain("private");
    }

    @Test
    void listenerAndTranscriptFailuresNeverChangeTheOutcome() {
        var protocol = new ClaudeCodeProtocol("custom/Model-1", 10, s -> s,
                e -> { throw new IllegalStateException("lease lost"); },
                entry -> { throw new IllegalStateException("database down"); });
        (INIT + TOOL_USE + TOOL_RESULT + RESULT).lines().forEach(protocol);
        assertThat(protocol.outcome(0).status()).isEqualTo(CodingOutcome.Status.COMPLETED);
    }

    @Test
    void transcriptKeepsTheConversationInOrderWithoutThinkingOrPartialDeltas() {
        var transcript = new ArrayList<CodingTranscripts.Entry>();
        var protocol = new ClaudeCodeProtocol("custom/Model-1", 10, s -> s.replace("secret-key", "[REDACTED]"),
                e -> { }, transcript::add);
        protocol.prompt("Implement TASK-1 secret-key");
        (INIT + """
                {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"PARTIAL_DELTA_SENTINEL"}}}
                {"type":"assistant","message":{"id":"m1","content":[{"type":"thinking","thinking":"HIDDEN_REASONING_SENTINEL"}]}}
                {"type":"assistant","message":{"id":"m1","content":[{"type":"redacted_thinking","data":"HIDDEN_REASONING_SENTINEL"}]}}
                {"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"I will run the tests secret-key"}]}}
                """ + TOOL_USE + TOOL_RESULT + RESULT).lines().forEach(protocol);
        assertThat(protocol.outcome(0).status()).isEqualTo(CodingOutcome.Status.COMPLETED);

        assertThat(transcript).extracting(CodingTranscripts.Entry::kind)
                .containsExactly("prompt", "text", "tool_call", "tool_result", "result");
        assertThat(transcript.get(0).detail()).containsEntry("text", "Implement TASK-1 [REDACTED]");
        assertThat(transcript.get(1).detail()).containsEntry("text", "I will run the tests [REDACTED]");
        assertThat(transcript.get(2).detail()).containsEntry("id", "toolu_1").containsEntry("tool", "Bash");
        assertThat(transcript.get(2).detail().get("input").toString()).contains("\"command\" : \"mvn test\"");
        assertThat(transcript.get(3).detail()).containsEntry("id", "toolu_1").containsEntry("tool", "Bash")
                .containsEntry("error", false).containsEntry("text", "BUILD SUCCESS").containsKey("durationMs");
        assertThat(transcript.get(4).detail()).containsEntry("subtype", "success").containsEntry("error", false)
                .containsEntry("text", "Done [REDACTED]")
                .containsEntry("usage", Map.of("inputTokens", 40L, "outputTokens", 5L, "cacheReadTokens", 3L,
                        "cacheWriteTokens", 2L));
        assertThat(transcript.toString())
                .doesNotContain("HIDDEN_REASONING_SENTINEL", "PARTIAL_DELTA_SENTINEL", "secret-key");
    }

    @Test
    void toolResultsAreCutWithAMarkerAndTheTranscriptCapNeverFailsTheAttempt() {
        var transcript = new ArrayList<CodingTranscripts.Entry>();
        var protocol = new ClaudeCodeProtocol("custom/Model-1", 10, s -> s, e -> { }, transcript::add);
        protocol.accept(INIT);
        String big = "z".repeat(ClaudeCodeProtocol.TOOL_RESULT_LIMIT + 100);
        protocol.accept(TOOL_USE);
        protocol.accept(TOOL_RESULT.replace("BUILD SUCCESS", big));
        String cut = transcript.get(1).detail().get("text").toString();
        assertThat(cut).startsWith("z".repeat(ClaudeCodeProtocol.TOOL_RESULT_LIMIT))
                .endsWith("[cut: 100 more characters]");

        String text = "w".repeat(1024 * 1024);
        for (int i = 0; i < 6; i++)
            protocol.accept("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}");
        protocol.accept(TOOL_USE);
        protocol.accept(RESULT);
        assertThat(protocol.outcome(0).status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(transcript.getLast().kind()).isEqualTo("note");
        assertThat(transcript.getLast().detail()).containsEntry("text", "Transcript cut at 5 MB; the attempt went on.");
        assertThat(transcript.stream().filter(entry -> entry.kind().equals("note")).count()).isEqualTo(1);
    }

    @Test
    void toolEventsCarryTelemetryMatchedByToolUseId() {
        var events = new ArrayList<Map<String, Object>>();
        var protocol = new ClaudeCodeProtocol("custom/Model-1", 10, s -> s.replace("secret-key", "[REDACTED]"),
                events::add, CodingTranscripts.NONE);
        protocol.accept(INIT);
        for (String[] tool : new String[][] {
                {"toolu_r", "Read", "{\"file_path\":\"/workspace/src/Foo.java\"}"},
                {"toolu_g", "Grep", "{\"pattern\":\"TODO\"}"},
                {"toolu_f", "WebFetch", "{\"url\":\"https://example.org/doc\"}"},
                {"toolu_b", "Bash", "{\"command\":\"mvn test -Dtoken=secret-key\"}"}})
            protocol.accept("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\""
                    + tool[0] + "\",\"name\":\"" + tool[1] + "\",\"input\":" + tool[2] + "}]}}");
        protocol.accept("""
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_b","is_error":true,"content":[{"type":"text","text":"Exit code 1\\nBUILD FAILURE secret-key"}]}]}}
                """);
        protocol.accept("""
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_r","content":"file text"}]}}
                """);
        var tools = events.stream().filter(e -> e.get("activity").toString().startsWith("tool_")).toList();
        assertThat(tools.get(0)).containsEntry("tool", "Read").containsEntry("path", "/workspace/src/Foo.java");
        assertThat(tools.get(1)).containsEntry("tool", "Grep").containsEntry("pattern", "TODO");
        assertThat(tools.get(2)).containsEntry("tool", "WebFetch").containsEntry("url", "https://example.org/doc");
        assertThat(tools.get(3)).containsEntry("tool", "Bash").containsEntry("command", "mvn test -Dtoken=[REDACTED]");
        assertThat(tools.get(4)).containsEntry("activity", "tool_execution_end").containsEntry("tool", "Bash")
                .containsEntry("command", "mvn test -Dtoken=[REDACTED]").containsEntry("error", true)
                .containsEntry("errorText", "Exit code 1 BUILD FAILURE [REDACTED]").containsKey("durationMs");
        assertThat(tools.get(5)).containsEntry("tool", "Read").containsEntry("path", "/workspace/src/Foo.java")
                .containsEntry("error", false).doesNotContainKey("errorText");
        assertThat(events.toString()).doesNotContain("secret-key", "file text");
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
        return parse(text, exitCode, 0);
    }

    private static CodingOutcome parse(String text, int exitCode, int maxTurns) {
        var protocol = new ClaudeCodeProtocol("custom/Model-1", maxTurns, s -> s.replace("secret-key", "[REDACTED]"),
                e -> {}, CodingTranscripts.NONE);
        text.lines().forEach(protocol);
        return protocol.outcome(exitCode);
    }

    private static Processes.Streamed feed(Predicate<String> lines, String text, int exitCode, String stderr) {
        for (String line : text.lines().toList()) {
            if (!lines.test(line)) break;
        }
        return new Processes.Streamed(exitCode, false, false, stderr);
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
