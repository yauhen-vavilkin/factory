package org.folio.factory.core.registry;

import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FlowRegistryCycleTest {

    private FlowRegistry registryFor(String pattern) {
        return new FlowRegistry(new FlowDescriptorParser(), mock(FlowRegistryEntryRepository.class), pattern);
    }

    @Test
    void mutuallyReferencingSubFlowsFailStartup() {
        FlowRegistry registry = registryFor("classpath:cyclic-flows/pair/*.yaml");

        assertThatThrownBy(registry::loadFlows)
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("Sub-flow cycle detected")
                .hasMessageContaining("cycle-a")
                .hasMessageContaining("cycle-b");
    }

    @Test
    void selfReferencingSubFlowFailsStartup() {
        FlowRegistry registry = registryFor("classpath:cyclic-flows/self/*.yaml");

        assertThatThrownBy(registry::loadFlows)
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("Sub-flow cycle detected: self-loop -> self-loop");
    }
}
