package org.folio.factory.app;

import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Developer Flow is an ordinary plugin: registered next to Test Factory, visible
 * through the existing flow API/UI. Execution is covered by
 * {@link DeveloperIntakeDecisionTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false"
})
@Import(StubLlmConfiguration.class)
@Testcontainers
class DeveloperFlowRegistrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    int port;

    @Autowired
    FlowRegistry flowRegistry;

    @Autowired
    DevFactoryProperties devFactoryProperties;

    @Autowired
    DevRuntimeProperties devRuntimeProperties;

    @Autowired
    DevDeliveryProperties devDeliveryProperties;

    private RestClient rest;

    @BeforeEach
    void setUpClient() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void registersAlongsideTestFactoryAndIsVisibleInExistingFlowViews() {
        assertThat(flowRegistry.require("dev-factory").name()).isEqualTo("Developer Flow");
        assertThat(flowRegistry.require("test-factory").name()).isEqualTo("Test Factory");

        JsonNode flows = rest.get().uri("/api/flows").retrieve().body(JsonNode.class);
        assertThat(flows.findValuesAsString("id")).contains("dev-factory", "test-factory");

        JsonNode detail = rest.get().uri("/api/flows/dev-factory").retrieve().body(JsonNode.class);
        assertThat(detail.path("name").asString()).isEqualTo("Developer Flow");
        assertThat(detail.path("version").asString()).isEqualTo("0.6.0");
        assertThat(detail.path("steps").findValuesAsString("stepId"))
                .containsExactly("read-task", "select-repository", "prepare-task", "implement", "verify", "publish");
        assertThat(detail.path("steps").get(0).path("workerId").asString()).isEqualTo("dev-intake");

        assertThat(rest.get().uri("/flows").retrieve().body(String.class))
                .contains("Developer Flow").contains("Test Factory");
        assertThat(rest.get().uri("/flows/dev-factory").retrieve().body(String.class))
                .contains("Developer Flow").contains("dev-intake");
    }

    @Test
    void configuresBothDeveloperRepositoriesForVerificationAndDelivery() {
        assertThat(devFactoryProperties.repositories()).containsOnlyKeys("sidecar", "mod-roles-keycloak");
        assertThat(devFactoryProperties.repositories().get("sidecar"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "yauhen-vavilkin/folio-module-sidecar", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-unit", List.of("MODSIDECAR"), List.of()));
        assertThat(devFactoryProperties.repositories().get("mod-roles-keycloak"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "yauhen-vavilkin/mod-roles-keycloak", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-unit", List.of("MODROLESKC"), List.of()));
        assertThat(devRuntimeProperties.command("java21-unit")).containsExactly("mvn", "-B", "-ntp", "test");
        assertThat(devDeliveryProperties.createPullRequest()).isTrue();
        assertThat(devDeliveryProperties.targets()).containsOnly(
                entry("sidecar", new DeliveryTarget("yauhen-vavilkin/folio-module-sidecar", "master", true)),
                entry("mod-roles-keycloak", new DeliveryTarget(
                        "yauhen-vavilkin/mod-roles-keycloak", "master", true)));
    }
}
