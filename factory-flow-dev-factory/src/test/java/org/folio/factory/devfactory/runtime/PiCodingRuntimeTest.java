package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiCodingRuntimeTest {
    private static final String SETTLED = "{\"type\":\"agent_settled\"}";
    private static final String USAGE_FRAME = """
            {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"stop","content":[{"type":"text","text":"private secret-key"}],"usage":{"input":10,"output":3,"cacheRead":2,"cacheWrite":1,"cost":{"total":0.125}}}}
            """;

    @Test void codemieProfileProducesPiReasoningAndChatCompletionsSettings() throws Exception {
        var coding = new DevRuntimeProperties.Coding("pi:image", "codemie", "gemini-3.8-flash",
                "http://host.docker.internal:4001/v1", "openai-completions", "codemie-proxy", "pi", "high", 65536);
        var captured = captureConfiguration(coding);
        var provider = tools.jackson.databind.json.JsonMapper.builder().build().readTree(captured.modelsJson())
                .path("providers").path("codemie");
        assertThat(provider.path("baseUrl").asString()).isEqualTo("http://host.docker.internal:4001/v1");
        assertThat(provider.path("api").asString()).isEqualTo("openai-completions");
        assertThat(provider.path("apiKey").asString()).isEqualTo("codemie-proxy");
        var model = provider.path("models").get(0);
        assertThat(model.path("id").asString()).isEqualTo("gemini-3.8-flash");
        assertThat(model.path("contextWindow").asInt()).isEqualTo(200000);
        assertThat(model.path("maxTokens").asInt()).isEqualTo(65536);
        assertThat(model.path("reasoning").asBoolean()).isTrue();
        assertThat(model.path("compat").path("supportsDeveloperRole").asBoolean()).isFalse();
        assertThat(model.path("compat").path("maxTokensField").asString()).isEqualTo("max_tokens");
        assertThat(captured.command()).containsSubsequence("--provider", "codemie", "--model", "gemini-3.8-flash",
                "--thinking", "high");
        assertThat(captured.events().toString()).doesNotContain("codemie-proxy");
    }

    @Test void glmProfileKeepsExistingPiDefaults() throws Exception {
        var coding = new DevRuntimeProperties.Coding("pi:image", "openai-compatible", "glm-5.3",
                "https://api.z.ai/api/coding/paas/v4", null, "test-key", "pi", null, null);
        var captured = captureConfiguration(coding);
        var provider = tools.jackson.databind.json.JsonMapper.builder().build().readTree(captured.modelsJson())
                .path("providers").path("openai-compatible");
        var model = provider.path("models").get(0);
        assertThat(model.path("contextWindow").asInt()).isEqualTo(200000);
        assertThat(model.path("maxTokens").asInt()).isEqualTo(16384);
        assertThat(model.has("reasoning")).isFalse();
        assertThat(model.has("compat")).isFalse();
        assertThat(captured.command()).doesNotContain("--thinking");
    }

    private static CapturedConfiguration captureConfiguration(DevRuntimeProperties.Coding coding) {
        var workload = mock(DockerWorkloads.Workload.class);
        var modelsJson = new AtomicReference<String>();
        var command = new AtomicReference<List<String>>();
        var events = new java.util.ArrayList<Map<String, Object>>();
        when(workload.execute(List.of("pi", "--version"), 30)).thenReturn(new Processes.Result(0, "0.85.1"));
        when(workload.execute(List.of("mkdir", "-p", "/tmp/factory-pi"), 30))
                .thenReturn(new Processes.Result(0, ""));
        doAnswer(invocation -> {
            modelsJson.set(Files.readString(Path.of((String) invocation.getArgument(0)).resolve("models.json")));
            return null;
        }).when(workload).copy(anyString(), eq("/tmp/factory-pi"));
        when(workload.execute(anyList(), eq(60), eq(PiCodingRuntime.EVENT_STREAM_LIMIT), any()))
                .thenAnswer(invocation -> {
                    command.set(List.copyOf(invocation.getArgument(0)));
                    return new Processes.Result(0, "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\","
                            + "\"provider\":\"" + coding.provider() + "\",\"model\":\"" + coding.model() + "\","
                            + "\"stopReason\":\"stop\",\"content\":[{\"type\":\"text\",\"text\":\"Done\"}]}}\n"
                            + SETTLED);
                });
        assertThat(new PiCodingRuntime(coding).code(workload, request(), 60, events::add).status())
                .isEqualTo(CodingOutcome.Status.COMPLETED);
        return new CapturedConfiguration(modelsJson.get(), command.get(), events);
    }

    private record CapturedConfiguration(String modelsJson, List<String> command, List<Map<String, Object>> events) { }

    @Test void recoveredProviderErrorUsesFinalAnswerAndAllReportedUsage() {
        String stream = """
                {"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"HTTP 429: retry later","usage":{"input":10,"cacheRead":2,"cacheWrite":0,"output":1}}}
                {"type":"agent_end","willRetry":true}
                {"type":"auto_retry_start","attempt":1,"maxAttempts":3,"errorMessage":"HTTP 429: retry later"}
                {"type":"agent_start"}
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"stop","content":[{"type":"text","text":"Implemented"}],"usage":{"input":20,"cacheRead":4,"cacheWrite":0,"output":3}}}
                {"type":"auto_retry_end","success":true,"attempt":1}
                {"type":"agent_end"}
                {"type":"agent_settled"}
                """;
        var result = PiCodingRuntime.parse(stream, "p", "m");
        assertThat(result.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(result.summary()).isEqualTo("Implemented");
        assertThat(result.metrics()).containsEntry("inputTokens", 30L)
                .containsEntry("cacheReadTokens", 6L)
                .containsEntry("outputTokens", 4L);
    }

    @Test void laterTerminalFailureDoesNotUseEarlierSuccessfulSummary() {
        String stream = """
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"stop","content":[{"type":"text","text":"Earlier answer"}],"usage":{"input":3,"output":2}}}
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"error","errorMessage":"HTTP 503: private secret-key","usage":{"input":7,"output":1}}}
                {"type":"agent_end"}
                {"type":"agent_settled"}
                """;
        var outcome = PiCodingRuntime.parse(stream, "p", "m");
        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.FAILED);
        assertThat(outcome.failure().code()).isEqualTo("PROVIDER_FAILED");
        assertThat(outcome.failure().message()).contains("HTTP 503", "provider unavailable")
                .doesNotContain("secret-key", "private");
        assertThat(outcome.metrics()).containsEntry("inputTokens", 10L).containsEntry("outputTokens", 3L);
    }

    @Test void multipleRetriesAndLaterToolWorkUseTheFinalAssistantResponse() {
        String stream = """
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"error","usage":{"input":2,"output":1}}}
                {"type":"agent_end","willRetry":true}
                {"type":"auto_retry_start","attempt":1,"maxAttempts":3}
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"error","usage":{"input":3,"output":1}}}
                {"type":"agent_end","willRetry":true}
                {"type":"auto_retry_start","attempt":2,"maxAttempts":3}
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"toolUse","usage":{"input":4,"output":2}}}
                {"type":"auto_retry_end","success":true,"attempt":2}
                {"type":"tool_execution_end","toolName":"bash","isError":false}
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"stop","content":[{"type":"text","text":"All checks passed"}],"usage":{"input":5,"output":3}}}
                {"type":"agent_end"}
                {"type":"agent_settled"}
                """;
        var outcome = PiCodingRuntime.parse(stream, "p", "m");
        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(outcome.summary()).isEqualTo("All checks passed");
        assertThat(outcome.metrics()).containsEntry("inputTokens", 14L).containsEntry("outputTokens", 7L);
    }

    @Test void exhaustedAndCancelledRetriesKeepAllReportedUsage() {
        String prefix = """
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"error","errorMessage":"HTTP 429","usage":{"input":4,"output":1}}}
                {"type":"auto_retry_start","attempt":1,"maxAttempts":1,"errorMessage":"HTTP 429"}
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"error","errorMessage":"HTTP 429","usage":{"input":5,"output":2}}}
                """;
        var exhausted = PiCodingRuntime.parse(prefix
                + "{\"type\":\"auto_retry_end\",\"success\":false,\"attempt\":1,\"finalError\":\"HTTP 429\"}\n"
                + SETTLED, "p", "m");
        assertThat(exhausted.failure().code()).isEqualTo("PROVIDER_RETRIES_EXHAUSTED");
        assertThat(exhausted.failure().message()).contains("HTTP 429");
        assertThat(exhausted.metrics()).containsEntry("inputTokens", 9L).containsEntry("outputTokens", 3L);

        var cancelled = PiCodingRuntime.parse(prefix
                + "{\"type\":\"auto_retry_end\",\"success\":false,\"attempt\":1,\"finalError\":\"Retry cancelled\"}\n"
                + SETTLED, "p", "m");
        assertThat(cancelled.failure().code()).isEqualTo("PROVIDER_ABORTED");
        assertThat(cancelled.metrics()).containsEntry("inputTokens", 9L);
    }

    @Test void compactionRecoveryCountsSummarizationOnceAndIgnoresSnapshots() {
        String stream = """
                {"type":"message_update","usage":{"input":900,"output":900}}
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"error","errorMessage":"context overflow","usage":{"input":10,"cacheRead":1,"cacheWrite":0,"output":1}}}
                {"type":"agent_end","messages":[{"role":"assistant","usage":{"input":900,"output":900}}]}
                {"type":"compaction_end","aborted":false,"result":{"usage":{"input":5,"cacheRead":0,"cacheWrite":0,"output":2}}}
                {"type":"agent_start"}
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"stop","content":[{"type":"text","text":"Done"}],"usage":{"input":20,"cacheRead":3,"cacheWrite":0,"output":4}}}
                {"type":"agent_end"}
                {"type":"agent_settled"}
                """;
        var events = new java.util.ArrayList<Map<String, Object>>();
        var progress = new PiCodingRuntime.Progress("secret-key", events::add);
        stream.lines().forEach(progress);
        var outcome = PiCodingRuntime.parse(stream, "p", "m");
        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(outcome.metrics()).containsEntry("inputTokens", 35L)
                .containsEntry("cacheReadTokens", 4L).containsEntry("outputTokens", 7L);
        assertThat(progress.metrics()).containsEntry("inputTokens", 35L)
                .containsEntry("cacheReadTokens", 4L).containsEntry("outputTokens", 7L);
        assertThat(events.stream().filter(event -> "coding_usage".equals(event.get("activity"))))
                .hasSize(3);
    }

    @Test void agentEndAndUnfinishedRetryCannotBeAcceptedAsCompletion() {
        String stream = """
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","content":[{"type":"text","text":"Old answer"}],"usage":{"input":2,"output":1}}}
                {"type":"agent_end"}
                {"type":"auto_retry_start","attempt":1,"maxAttempts":3}
                {"type":"agent_start"}
                """;
        assertThat(PiCodingRuntime.parse(stream, "p", "m").failure().code()).isEqualTo("INCOMPLETE_RESULT");
        assertThat(PiCodingRuntime.parse("""
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"length","content":[{"type":"text","text":"Truncated"}]}}
                {"type":"agent_settled"}
                """, "p", "m").failure().code()).isEqualTo("INCOMPLETE_RESULT");
    }

    @Test void diagnosticEventsContinueAfterOrdinaryActivityLimitWithoutLeakingProviderText() {
        var events = new java.util.ArrayList<Map<String, Object>>();
        var progress = new PiCodingRuntime.Progress("secret-key", events::add);
        for (int i = 0; i < 200; i++) progress.accept("{\"type\":\"turn_start\"}");
        progress.accept("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\","
                + "\"stopReason\":\"error\",\"errorMessage\":\"HTTP 429: Bearer secret-key "
                + "private prompt text\",\"usage\":{\"input\":8,\"output\":1}}}");
        progress.accept("{\"type\":\"auto_retry_start\",\"attempt\":2,\"maxAttempts\":3,"
                + "\"errorMessage\":\"HTTP 429: secret-key\"}");
        assertThat(events).hasSize(203);
        assertThat(events.get(201)).containsEntry("activity", "provider_error")
                .containsEntry("reason", "HTTP 429 (rate limited)");
        assertThat(events.getLast()).containsEntry("activity", "auto_retry_start")
                .containsEntry("attempt", 2).containsEntry("maxAttempts", 3);
        assertThat(events.toString()).doesNotContain("secret-key", "Bearer", "private prompt text");
    }

    @Test void streamedUsageMatchesSuccessfulTotalsWithoutCountingSnapshotsTwice() {
        var events = new java.util.ArrayList<Map<String, Object>>();
        var progress = new PiCodingRuntime.Progress("secret-key", events::add);
        progress.accept(USAGE_FRAME);
        progress.accept(USAGE_FRAME);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).containsEntry("activity", "coding_usage")
                .containsEntry("inputTokens", 10L)
                .containsEntry("cacheReadTokens", 2L)
                .containsEntry("cacheWriteTokens", 1L)
                .containsEntry("outputTokens", 3L);
        var result = PiCodingRuntime.parse(USAGE_FRAME + USAGE_FRAME + SETTLED, "p", "m");
        var totals = new java.util.LinkedHashMap<>(result.metrics());
        totals.remove("provider");
        totals.remove("model");
        totals.put("activity", "coding_usage");
        assertThat(events.getLast()).isEqualTo(totals)
                .containsEntry("inputTokens", 20L)
                .containsEntry("cacheReadTokens", 4L)
                .containsEntry("cacheWriteTokens", 2L)
                .containsEntry("outputTokens", 6L)
                .doesNotContainKeys("promptTokens", "completionTokens");
        assertThat((java.math.BigDecimal) events.getLast().get("costUsd")).isEqualByComparingTo("0.25");
        assertThat(events.toString()).doesNotContain("private", "secret-key", "content", "provider", "model");
    }

    @Test void usageContinuesAfterActivityLimitAndIgnoresNonAssistantMessagesAndMissingUsage() {
        var events = new java.util.ArrayList<Map<String, Object>>();
        var progress = new PiCodingRuntime.Progress("secret-key", events::add);
        for (int i = 0; i < 300; i++) progress.accept("{\"type\":\"turn_start\"}");
        progress.accept(USAGE_FRAME.replace("assistant", "toolResult"));
        progress.accept("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":\"private\"}}");
        progress.accept("not-json");
        progress.accept(USAGE_FRAME.replace(",\"cost\":{\"total\":0.125}", ""));
        progress.accept(USAGE_FRAME.replace("\"input\":10", "\"input\":-1").replace("\"output\":3", "\"output\":-5"));
        assertThat(events).hasSize(202);
        assertThat(events.get(200)).doesNotContainKey("costUsd");
        assertThat(events.getLast()).containsEntry("inputTokens", 10L)
                .containsEntry("cacheReadTokens", 4L)
                .containsEntry("cacheWriteTokens", 2L)
                .containsEntry("outputTokens", 3L);
        assertThat((java.math.BigDecimal) events.getLast().get("costUsd")).isEqualByComparingTo("0.125");
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "exit", "provider", "success"})
    void emittedUsageSurvivesRuntimeFailureAndSuccessDoesNotAddItAgain(String outcome) {
        var workload = mock(DockerWorkloads.Workload.class);
        when(workload.execute(List.of("pi", "--version"), 30)).thenReturn(new Processes.Result(0, "0.85.1"));
        when(workload.execute(List.of("mkdir", "-p", "/tmp/factory-pi"), 30)).thenReturn(new Processes.Result(0, ""));
        when(workload.execute(anyList(), eq(60), eq(PiCodingRuntime.EVENT_STREAM_LIMIT), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> observer = invocation.getArgument(3);
                    String frame = outcome.equals("provider")
                            ? USAGE_FRAME.replace("\"stopReason\":\"stop\"", "\"stopReason\":\"error\"") : USAGE_FRAME;
                    observer.accept(USAGE_FRAME);
                    observer.accept(frame);
                    if (outcome.equals("timeout")) throw new IllegalStateException("Process timed out");
                    return new Processes.Result(outcome.equals("exit") ? 1 : 0,
                            USAGE_FRAME + frame + SETTLED);
                });
        var events = new java.util.ArrayList<Map<String, Object>>();
        var runtime = new PiCodingRuntime(new DevRuntimeProperties.Coding("pi:image", "p", "m", null, null, "secret-key", "pi", null, null));
        if (outcome.equals("success")) {
            var result = runtime.code(workload, request(), 60, events::add);
            assertThat(result.summary()).contains("[REDACTED]").doesNotContain("secret-key");
            assertThat(result.metrics())
                    .containsEntry("inputTokens", 20L)
                    .containsEntry("cacheReadTokens", 4L)
                    .containsEntry("cacheWriteTokens", 2L)
                    .containsEntry("outputTokens", 6L);
        } else assertThat(runtime.code(workload, request(), 60, events::add).status())
                .isEqualTo(CodingOutcome.Status.FAILED);
        assertThat(events).hasSize(outcome.equals("provider") ? 3 : 2);
        assertThat(events.get(1)).containsEntry("inputTokens", 20L)
                .containsEntry("cacheReadTokens", 4L)
                .containsEntry("cacheWriteTokens", 2L)
                .containsEntry("outputTokens", 6L);
        assertThat((java.math.BigDecimal) events.get(1).get("costUsd")).isEqualByComparingTo("0.25");
        assertThat(events.toString()).doesNotContain("private", "secret-key", "content");
    }

    @Test void extractsOnlyReportedUsageAndCost() {
        String message = """
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","stopReason":"stop","content":[{"type":"text","text":"Done"}],"usage":{"input":10,"output":3,"cacheRead":2,"cacheWrite":1,"cost":{"total":0.125}}}}
                """;
        var metrics = PiCodingRuntime.parse(message + message + SETTLED, "p", "m").metrics();
        assertThat(metrics).containsEntry("inputTokens", 20L)
                .containsEntry("cacheReadTokens", 4L)
                .containsEntry("cacheWriteTokens", 2L)
                .containsEntry("outputTokens", 6L)
                .doesNotContainKeys("promptTokens", "completionTokens");
        assertThat((java.math.BigDecimal) metrics.get("costUsd")).isEqualByComparingTo("0.25");
        assertThat(PiCodingRuntime.parse(message.replace(",\"cost\":{\"total\":0.125}", "")
                + SETTLED, "p", "m").metrics()).doesNotContainKey("costUsd");
        var withoutCache = PiCodingRuntime.parse(message.replace(",\"cacheRead\":2,\"cacheWrite\":1", "")
                + SETTLED, "p", "m").metrics();
        assertThat(withoutCache).containsEntry("inputTokens", 10L).containsEntry("outputTokens", 3L)
                .doesNotContainKeys("cacheReadTokens", "cacheWriteTokens");
    }

    @Test void progressOmitsTextOutputAndSecretsAndIsBounded() {
        var events = new java.util.ArrayList<java.util.Map<String, Object>>();
        var progress = new PiCodingRuntime.Progress("secret-key", events::add);
        progress.accept("{\"type\":\"message_update\",\"text\":\"hidden reasoning\"}");
        progress.accept("{\"type\":\"tool_execution_start\",\"toolName\":\"read\",\"args\":{\"path\":\"secret-key/" + "x".repeat(500) + "\",\"content\":\"private\"}}");
        progress.accept("{\"type\":\"tool_execution_start\",\"toolName\":\"bash\",\"args\":{\"command\":\"mvn test -Dtoken=secret-key -Dtest=FooTest\"}}");
        progress.accept("{\"type\":\"tool_execution_end\",\"toolName\":\"bash\",\"isError\":true,\"result\":\"private output\"}");
        assertThat(events.get(0).get("path").toString()).startsWith("[REDACTED]").hasSize(240);
        assertThat(events.get(1)).containsEntry("command", "mvn test -Dtest=FooTest");
        assertThat(events.get(2)).containsEntry("error", true);
        assertThat(events.toString()).doesNotContain("secret-key", "private", "hidden reasoning");
        for (int i = 0; i < 300; i++) progress.accept("{\"type\":\"turn_start\"}");
        assertThat(events).hasSize(200);
        assertThat(events.get(199)).containsKey("notice");
    }
    @Test void acknowledgementIsNotCompletionAndProviderErrorsCannotPass() {
        assertThat(PiCodingRuntime.parse("{\"type\":\"response\",\"command\":\"prompt\",\"success\":true}", "provider", "model").status())
                .isEqualTo(CodingOutcome.Status.FAILED);
        assertThat(PiCodingRuntime.parse("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"error\"}}\n{\"type\":\"agent_end\"}", "provider", "model").status())
                .isEqualTo(CodingOutcome.Status.FAILED);
        assertThat(PiCodingRuntime.parse("not-json", "provider", "model").failure().code())
                .isEqualTo("MALFORMED_FRAME");
    }
    @Test void completedAssistantMustMatchConfiguredModel() {
        String output = "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"provider\":\"provider\",\"model\":\"model\",\"stopReason\":\"stop\",\"content\":[{\"type\":\"text\",\"text\":\"Implemented\"}]}}\n" + SETTLED;
        assertThat(PiCodingRuntime.parse(output, "provider", "model").summary()).isEqualTo("Implemented");
        assertThat(PiCodingRuntime.parse(output, "provider", "other").failure().code())
                .isEqualTo("IDENTITY_MISMATCH");
    }

    @Test void materialDecisionKeepsTheConcreteQuestion() {
        String decision = "FACTORY_DECISION_REQUIRED: {\\\"kind\\\":\\\"PRODUCT_REQUIREMENTS\\\","
                + "\\\"question\\\":\\\"Which public API should change?\\\","
                + "\\\"options\\\":[\\\"A\\\",\\\"B\\\"],\\\"evidence\\\":\\\"Both APIs exist\\\"}";
        String output = "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"provider\":\"provider\",\"model\":\"model\",\"stopReason\":\"stop\",\"content\":[{\"type\":\"text\",\"text\":\"" + decision + "\"}]}}\n" + SETTLED;
        var result = PiCodingRuntime.parse(output, "provider", "model");
        assertThat(result.status()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        assertThat(result.decision().kind()).isEqualTo(CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS);
        assertThat(result.decision().question()).isEqualTo("Which public API should change?");
    }

    @Test void markerMentionedLaterInSuccessfulSummaryCompletesNormally() {
        String text = "All checks pass. Here's a summary of the completed work: tests green. "
                + "No FACTORY_DECISION_REQUIRED was needed for this task.";
        String output = "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"provider\":\"provider\","
                + "\"model\":\"model\",\"stopReason\":\"stop\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}\n" + SETTLED;
        assertThat(PiCodingRuntime.parse(output, "provider", "model").summary()).isEqualTo(text);
    }

    @Test void decisionProtocolRejectsTrailingText() {
        String decision = "FACTORY_DECISION_REQUIRED: {\\\"kind\\\":\\\"PRODUCT_REQUIREMENTS\\\","
                + "\\\"question\\\":\\\"Which API?\\\",\\\"options\\\":[],\\\"evidence\\\":\\\"Both exist\\\"} extra";
        String output = "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"provider\":\"provider\","
                + "\"model\":\"model\",\"stopReason\":\"stop\",\"content\":[{\"type\":\"text\",\"text\":\"" + decision
                + "\"}]}}\n" + SETTLED;
        assertThat(PiCodingRuntime.parse(output, "provider", "model").failure().code())
                .isEqualTo("INVALID_DECISION_RESULT");
    }

    private static CodingRequest request() {
        return new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults());
    }
}
