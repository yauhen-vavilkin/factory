package org.folio.factory.core.registry;

import org.folio.factory.core.domain.FlowRegistryEntry;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@org.junit.jupiter.api.Tag("integration")
@Testcontainers
class FlowRegistryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    FlowRegistry registry;

    @Autowired
    FlowRegistryEntryRepository mirror;

    @Test
    void loadsClasspathFlowsAndValidatesSubFlowReferences() {
        assertThat(registry.find("fake-parent")).isPresent();
        assertThat(registry.find("fake-child")).isPresent();
        assertThat(registry.require("fake-parent").step(1).subFlow().flowId()).isEqualTo("fake-child");
    }

    @Test
    void mirrorsDescriptorsToDatabaseWithContentHash() {
        FlowRegistryEntry entry = mirror.findById(new FlowRegistryEntry.Key("fake-parent", "1.0.0")).orElseThrow();
        assertThat(entry.getYamlSha256()).hasSize(64);
        assertThat(entry.getRawYaml()).contains("fake-child");
    }
}
