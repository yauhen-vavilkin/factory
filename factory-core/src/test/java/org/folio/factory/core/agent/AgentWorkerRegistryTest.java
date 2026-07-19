package org.folio.factory.core.agent;

import org.folio.factory.core.registry.FlowDescriptorParser;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.FlowValidationException;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AgentWorkerRegistryTest {

    private static final FlowDescriptorParser PARSER = new FlowDescriptorParser();

    private static FlowDescriptor flowReferencing(String workerId) {
        return PARSER.parse("""
                id: f
                name: F
                version: 1.0.0
                agent_chain:
                  - step_id: s
                    type: AGENT
                    worker_id: %s
                """.formatted(workerId), "f.yaml");
    }

    private static AgentWorker worker(String id) {
        return new AgentWorker() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public AgentResult execute(AgentContext context) {
                return new AgentResult(Map.of(), Map.of());
            }
        };
    }

    @Test
    void resolvesWorkersById() {
        AgentWorkerRegistry registry = new AgentWorkerRegistry(List.of(worker("a"), worker("b")), mock(FlowRegistry.class));
        assertThat(registry.require("a").id()).isEqualTo("a");
        assertThatThrownBy(() -> registry.require("missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allReturnsImmutableViewOfEveryWorker() {
        AgentWorkerRegistry registry = new AgentWorkerRegistry(List.of(worker("a"), worker("b")), mock(FlowRegistry.class));
        Map<String, AgentWorker> all = registry.all();
        assertThat(all).containsOnlyKeys("a", "b");
        assertThatThrownBy(() -> all.put("c", worker("c"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateWorkerIds() {
        assertThatThrownBy(() -> new AgentWorkerRegistry(List.of(worker("a"), worker("a")), mock(FlowRegistry.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate agent worker id");
    }

    @Test
    void failsWhenFlowReferencesUnknownWorker() {
        AgentWorkerRegistry registry = new AgentWorkerRegistry(List.of(worker("known")), mock(FlowRegistry.class));
        assertThatThrownBy(() -> registry.validateAgainst(List.of(flowReferencing("unknown"))))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("unknown agent worker");
        registry.validateAgainst(List.of(flowReferencing("known")));
    }
}
