package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PiCodingRuntimeTest {
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
