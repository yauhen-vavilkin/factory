package org.folio.factory.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.sandbox.core.SandboxProperties;
import org.folio.factory.sandbox.harness.HarnessProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Asserts the frozen S7.5 configuration contract against the values actually
 * loaded from {@code application.yaml} (tasks/T14.md, frozen configuration
 * contract + 2026-09-04 layer-2 amendment). Green context alone is not
 * evidence: most yaml values equal the code defaults, so this pins the yaml
 * itself — a misspelled or ignored key must fail here.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false"})
@Import(StubLlmConfiguration.class)
@Testcontainers
class FactoryYamlContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private EngineProperties engineProperties;

    @Autowired
    private HarnessProperties harnessProperties;

    @Autowired
    private SandboxProperties sandboxProperties;

    @Test
    void engineLeaseTimeoutIsPinnedAboveHarnessJobTimeout() {
        assertEquals(Long.valueOf(2700L), engineProperties.leaseTimeoutSeconds());
    }

    @Test
    void harnessPropertiesLoadFrozenDefaultsFromYaml() {
        assertEquals(Integer.valueOf(40), harnessProperties.maxSteps());
        assertEquals(Integer.valueOf(3), harnessProperties.maxFormatErrors());
        assertEquals(Long.valueOf(30L), harnessProperties.jobTimeoutMin());
        assertEquals("glm-5.3-flash", harnessProperties.modelId());
    }

    @Test
    void sandboxPropertiesLoadFrozenDefaultsFromYaml() {
        assertEquals("docker", sandboxProperties.mode());
        assertEquals("maven:3.9-eclipse-temurin-21", sandboxProperties.image());
    }
}
