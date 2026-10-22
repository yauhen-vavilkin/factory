package org.folio.factory.app;

import org.folio.factory.app.developer.DeveloperPreflightProbe;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.service.StateManager;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Developer Flow is an ordinary plugin: registered next to Test Factory, visible
 * through the existing flow API/UI. Execution is covered by
 * {@link DeveloperIntakeDecisionTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.connectors.jira.base-url=",
        "factory.dev-factory.runtime.coding.runtime=claude-code",
        "factory.dev-factory.runtime.coding.claude-code.authentication="
})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
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

    @Autowired
    StateManager state;

    @MockitoBean
    DeveloperPreflightProbe preflightProbe;

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
        assertThat(detail.path("version").asString()).isEqualTo("0.9.0");
        assertThat(flowRegistry.require("dev-factory").agentChain().get(7).inputs())
                .contains("dev_coding_request.json", "dev_coding_outcome.json");
        assertThat(detail.path("steps").findValuesAsString("stepId"))
                .containsExactly("read-task", "select-repository", "prepare-task", "implement",
                        "clarify-implementation", "continue-implementation", "verify", "publish",
                        "initialize-pr-feedback", "pr-follow-up");
        assertThat(detail.path("steps").get(0).path("workerId").asString()).isEqualTo("dev-intake");

        assertThat(rest.get().uri("/flows").retrieve().body(String.class))
                .contains("Developer Flow").contains("Test Factory");
        assertThat(rest.get().uri("/flows/dev-factory").retrieve().body(String.class))
                .contains("Developer Flow").contains("dev-intake");
    }

    @Test
    void configuresDeveloperRepositoriesForVerificationAndDelivery() {
        assertThat(devFactoryProperties.repositories())
                .containsOnlyKeys("sidecar", "mod-roles-keycloak", "mgr-tenant-entitlements", "mgr-applications");
        assertThat(devFactoryProperties.repositories().get("sidecar"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/folio-module-sidecar", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-unit", List.of("MODSIDECAR"), List.of(),
                        new DeliveryTarget("folio-org/folio-module-sidecar", "master", false)));
        assertThat(devFactoryProperties.repositories().get("mod-roles-keycloak"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/mod-roles-keycloak", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-roles-verify", List.of("MODROLESKC"), List.of(),
                        new DeliveryTarget("folio-org/mod-roles-keycloak", "master", false)));
        assertThat(devFactoryProperties.repositories().get("mgr-tenant-entitlements"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/mgr-tenant-entitlements", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-unit", List.of("MGRENTITLE"), List.of(),
                        new DeliveryTarget("folio-org/mgr-tenant-entitlements", "master", false)));
        assertThat(devRuntimeProperties.command("java21-unit")).containsExactly("mvn", "-B", "-ntp", "test");
        assertThat(devFactoryProperties.repositories().get("mgr-applications"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/mgr-applications", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-unit", List.of("MGRAPPS"), List.of(),
                        new DeliveryTarget("folio-org/mgr-applications", "master", false)));
        assertThat(devRuntimeProperties.command("java21-roles-verify"))
                .containsExactly("mvn", "-B", "-ntp", "verify");
        assertThat(devRuntimeProperties.requiredReports("java21-roles-verify")).containsExactly("surefire", "failsafe");
        assertThat(devDeliveryProperties.createPullRequest()).isTrue();
        assertThat(devDeliveryProperties.targets()).isEmpty();
        assertThat(devDeliveryProperties.effectiveTargets(devFactoryProperties)).containsOnly(
                entry("sidecar", new DeliveryTarget("folio-org/folio-module-sidecar", "master", false)),
                entry("mod-roles-keycloak", new DeliveryTarget(
                        "folio-org/mod-roles-keycloak", "master", false)),
                entry("mgr-tenant-entitlements", new DeliveryTarget(
                        "folio-org/mgr-tenant-entitlements", "master", false)),
                entry("mgr-applications", new DeliveryTarget(
                        "folio-org/mgr-applications", "master", false)));
    }

    @Test
    void realPreflightBlocksApiAndUiAdmissionBeforeCreatingExecutions() throws Exception {
        long before = state.countCreatedSince(Instant.EPOCH);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var apiRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/triggers/manual"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"flowId":"dev-factory","payload":{"issueKey":"MODSIDECAR-196"}}
                        """))
                .build();

        var apiResponse = client.send(apiRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(apiResponse.statusCode()).isEqualTo(422);
        assertThat(apiResponse.body()).contains("Developer Flow preflight failed", "Configure the selected coding profile");
        assertThat(state.countCreatedSince(Instant.EPOCH)).isEqualTo(before);

        String form = "payload=" + URLEncoder.encode("{\"issueKey\":\"MODSIDECAR-196\"}", StandardCharsets.UTF_8)
                + "&dedupKey=" + UUID.randomUUID();
        var uiRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/flows/dev-factory/trigger"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        var uiResponse = client.send(uiRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(uiResponse.statusCode()).isEqualTo(302);
        var location = URI.create(uiResponse.headers().firstValue("Location").orElseThrow());
        assertThat(location.getPath()).isEqualTo("/flows/dev-factory");
        assertThat(URLDecoder.decode(location.getRawQuery(), StandardCharsets.UTF_8))
                .contains("Developer Flow preflight failed", "Configure the selected coding profile");
        assertThat(state.countCreatedSince(Instant.EPOCH)).isEqualTo(before);
        verify(preflightProbe, times(2)).dockerAvailable();
    }
}
