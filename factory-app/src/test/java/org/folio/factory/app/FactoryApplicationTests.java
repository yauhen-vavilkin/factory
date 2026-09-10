package org.folio.factory.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"factory.mode=offline", "spring.ai.model.chat=none", "factory.engine.enabled=false"})
@Import(StubLlmConfiguration.class)
@org.junit.jupiter.api.Tag("integration")
@Testcontainers
class FactoryApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void contextLoadsAndMigrationsApply() {
    }
}
