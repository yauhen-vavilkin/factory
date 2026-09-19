package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiCodingRuntimeTest {
    private static final String USAGE_FRAME = """
            {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","content":[{"type":"text","text":"private secret-key"}],"usage":{"input":10,"output":3,"cacheRead":2,"cacheWrite":1,"cost":{"total":0.125}}}}
            """;

    @Test void streamedUsageMatchesSuccessfulTotalsWithoutCountingSnapshotsTwice() {
        var events = new java.util.ArrayList<Map<String, Object>>();
        var progress = new PiCodingRuntime.Progress("secret-key", events::add);
        progress.accept(USAGE_FRAME);
        progress.accept(USAGE_FRAME);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).containsEntry("activity", "pi_usage")
                .containsEntry("inputTokens", 10L)
                .containsEntry("cacheReadTokens", 2L)
                .containsEntry("cacheWriteTokens", 1L)
                .containsEntry("outputTokens", 3L);
        var result = PiCodingRuntime.parse(USAGE_FRAME + USAGE_FRAME + "{\"type\":\"agent_end\"}", "p", "m");
        var totals = new java.util.LinkedHashMap<>(result.metrics());
        totals.remove("provider");
        totals.remove("model");
        totals.put("activity", "pi_usage");
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
                            ? USAGE_FRAME.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"stopReason\":\"error\"") : USAGE_FRAME;
                    observer.accept(USAGE_FRAME);
                    observer.accept(frame);
                    if (outcome.equals("timeout")) throw new IllegalStateException("Process timed out");
                    return new Processes.Result(outcome.equals("exit") ? 1 : 0,
                            USAGE_FRAME + frame + "{\"type\":\"agent_end\"}");
                });
        var events = new java.util.ArrayList<Map<String, Object>>();
        var runtime = new PiCodingRuntime(new DevRuntimeProperties.Coding("pi:image", "p", "m", null, null, "secret-key"));
        if (outcome.equals("success")) {
            assertThat(runtime.code(workload, "private task", 60, events::add).metrics())
                    .containsEntry("inputTokens", 20L)
                    .containsEntry("cacheReadTokens", 4L)
                    .containsEntry("cacheWriteTokens", 2L)
                    .containsEntry("outputTokens", 6L);
        } else assertThatThrownBy(() -> runtime.code(workload, "private task", 60, events::add))
                .isInstanceOf(IllegalStateException.class);
        assertThat(events).hasSize(2);
        assertThat(events.getLast()).containsEntry("inputTokens", 20L)
                .containsEntry("cacheReadTokens", 4L)
                .containsEntry("cacheWriteTokens", 2L)
                .containsEntry("outputTokens", 6L);
        assertThat((java.math.BigDecimal) events.getLast().get("costUsd")).isEqualByComparingTo("0.25");
        assertThat(events.toString()).doesNotContain("private", "secret-key", "content");
    }

    @Test void extractsOnlyReportedUsageAndCost() {
        String message = """
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","content":[{"type":"text","text":"Done"}],"usage":{"input":10,"output":3,"cacheRead":2,"cacheWrite":1,"cost":{"total":0.125}}}}
                """;
        var metrics = PiCodingRuntime.parse(message + message + "{\"type\":\"agent_end\"}", "p", "m").metrics();
        assertThat(metrics).containsEntry("inputTokens", 20L)
                .containsEntry("cacheReadTokens", 4L)
                .containsEntry("cacheWriteTokens", 2L)
                .containsEntry("outputTokens", 6L)
                .doesNotContainKeys("promptTokens", "completionTokens");
        assertThat((java.math.BigDecimal) metrics.get("costUsd")).isEqualByComparingTo("0.25");
        assertThat(PiCodingRuntime.parse(message.replace(",\"cost\":{\"total\":0.125}", "")
                + "{\"type\":\"agent_end\"}", "p", "m").metrics()).doesNotContainKey("costUsd");
        var withoutCache = PiCodingRuntime.parse(message.replace(",\"cacheRead\":2,\"cacheWrite\":1", "")
                + "{\"type\":\"agent_end\"}", "p", "m").metrics();
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
        assertThatThrownBy(() -> PiCodingRuntime.parse("{\"type\":\"response\",\"command\":\"prompt\",\"success\":true}", "provider", "model"))
                .hasMessageContaining("completed");
        assertThatThrownBy(() -> PiCodingRuntime.parse("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"error\"}}\n{\"type\":\"agent_end\"}", "provider", "model"))
                .hasMessageContaining("failed");
    }
    @Test void completedAssistantMustMatchConfiguredModel() {
        String output = "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"provider\":\"provider\",\"model\":\"model\",\"content\":[{\"type\":\"text\",\"text\":\"Implemented\"}]}}\n{\"type\":\"agent_end\"}";
        assertThat(PiCodingRuntime.parse(output, "provider", "model").summary()).isEqualTo("Implemented");
        assertThatThrownBy(() -> PiCodingRuntime.parse(output, "provider", "other")).hasMessageContaining("identity mismatch");
    }

    @Test void materialDecisionKeepsTheConcreteQuestion() {
        String output = "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"provider\":\"provider\",\"model\":\"model\",\"content\":[{\"type\":\"text\",\"text\":\"FACTORY_DECISION_REQUIRED: Which public API should change?\"}]}}\n{\"type\":\"agent_end\"}";
        assertThatThrownBy(() -> PiCodingRuntime.parse(output, "provider", "model"))
                .hasMessageContaining("CODING_DECISION_REQUIRED")
                .hasMessageContaining("Which public API should change?");
    }

    @Test void markerMentionedLaterInSuccessfulSummaryCompletesNormally() {
        String text = "All checks pass. Here's a summary of the completed work: tests green. "
                + "No FACTORY_DECISION_REQUIRED was needed for this task.";
        String output = "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"provider\":\"provider\","
                + "\"model\":\"model\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}\n{\"type\":\"agent_end\"}";
        assertThat(PiCodingRuntime.parse(output, "provider", "model").summary()).isEqualTo(text);
    }
}
