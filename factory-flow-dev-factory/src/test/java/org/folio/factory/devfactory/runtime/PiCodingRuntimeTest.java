package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PiCodingRuntimeTest {
    @Test void extractsOnlyReportedUsageAndCost() {
        String message = """
                {"type":"message_end","message":{"role":"assistant","provider":"p","model":"m","content":[{"type":"text","text":"Done"}],"usage":{"input":10,"output":3,"cacheRead":2,"cacheWrite":1,"cost":{"total":0.125}}}}
                """;
        var metrics = PiCodingRuntime.parse(message + message + "{\"type\":\"agent_end\"}", "p", "m").metrics();
        assertThat(metrics).containsEntry("promptTokens", 26L).containsEntry("completionTokens", 6L);
        assertThat((java.math.BigDecimal) metrics.get("costUsd")).isEqualByComparingTo("0.25");
        assertThat(PiCodingRuntime.parse(message.replace(",\"cost\":{\"total\":0.125}", "")
                + "{\"type\":\"agent_end\"}", "p", "m").metrics()).doesNotContainKey("costUsd");
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
                .hasMessageContaining("Which public API should change?");
    }
}
