package org.folio.factory.app;

import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.Toolchain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    private static final List<String> REPOSITORY_OVERRIDES = List.of(
            "FACTORY_SIDECAR_SOURCE_REPO", "FACTORY_SIDECAR_DELIVERY_REPO",
            "FACTORY_MOD_ROLES_KEYCLOAK_SOURCE_REPO", "FACTORY_MOD_ROLES_KEYCLOAK_DELIVERY_REPO",
            "FACTORY_MGR_TENANT_ENTITLEMENTS_SOURCE_REPO", "FACTORY_MGR_TENANT_ENTITLEMENTS_DELIVERY_REPO",
            "FACTORY_MGR_TENANT_ENTITLEMENTS_BASE_BRANCH",
            "FACTORY_MGR_APPLICATIONS_SOURCE_REPO", "FACTORY_MGR_APPLICATIONS_DELIVERY_REPO",
            "FACTORY_UI_USERS_SOURCE_REPO", "FACTORY_UI_USERS_DELIVERY_REPO",
            "FACTORY_UI_CIRCULATION_SOURCE_REPO", "FACTORY_UI_CIRCULATION_DELIVERY_REPO",
            "FACTORY_UI_INVENTORY_SOURCE_REPO", "FACTORY_UI_INVENTORY_DELIVERY_REPO",
            "FACTORY_UI_MARC_AUTHORITIES_SOURCE_REPO", "FACTORY_UI_MARC_AUTHORITIES_DELIVERY_REPO",
            "FACTORY_UI_CHECKIN_SOURCE_REPO", "FACTORY_UI_CHECKIN_DELIVERY_REPO",
            "FACTORY_UI_EHOLDINGS_SOURCE_REPO", "FACTORY_UI_EHOLDINGS_DELIVERY_REPO",
            "FACTORY_UI_MYPROFILE_SOURCE_REPO", "FACTORY_UI_MYPROFILE_DELIVERY_REPO",
            "FACTORY_UI_TAGS_SOURCE_REPO", "FACTORY_UI_TAGS_DELIVERY_REPO");

    @Test
    void configuresDeveloperRepositoriesPlansAndDeliveryTargets() {
        assertThat(devFactoryProperties.repositories())
                .containsOnlyKeys("sidecar", "mod-roles-keycloak", "mgr-tenant-entitlements", "mgr-applications", "ui-users", "ui-circulation", "ui-inventory", "ui-marc-authorities", "ui-checkin", "ui-eholdings", "ui-myprofile", "ui-tags");
        assertThat(devDeliveryProperties.targets())
                .containsOnlyKeys("sidecar", "mod-roles-keycloak", "mgr-tenant-entitlements", "mgr-applications", "ui-users", "ui-circulation", "ui-inventory", "ui-marc-authorities", "ui-checkin", "ui-eholdings", "ui-myprofile", "ui-tags");
        assertThat(devRuntimeProperties.command("java21-unit")).containsExactly("mvn", "-B", "-ntp", "test");
        assertThat(devRuntimeProperties.seedTimeoutSeconds()).isEqualTo(300);
        assertThat(devRuntimeProperties.toolchain("node22-jest")).isEqualTo(Toolchain.YARN);
        assertThat(devRuntimeProperties.toolchain("java21-unit")).isEqualTo(Toolchain.MAVEN);
        assertThat(devRuntimeProperties.command("node22-jest")).containsExactly("yarn", "test", "--maxWorkers=2");
        assertThat(devRuntimeProperties.installCommand("node22-jest")).containsExactly(
                "yarn", "install", "--frozen-lockfile", "--ignore-scripts", "--non-interactive",
                "--mutex", "file:/tmp/factory-home/.yarn-cache/.yarn-mutex");
        // The deployed command and the Java default must lock the same path, or the lock silently does nothing.
        assertThat(devRuntimeProperties.installCommand("node22-jest"))
                .isEqualTo(DevRuntimeProperties.Yarn.DEFAULT_INSTALL_COMMAND);
        assertThat(devRuntimeProperties.installCommand("java21-unit")).isEmpty();
        assertThat(devRuntimeProperties.verificationCommand("node22-jest")).containsExactly("sh", "-c",
                "'yarn' 'install' '--frozen-lockfile' '--ignore-scripts' '--non-interactive'"
                        + " '--mutex' 'file:/tmp/factory-home/.yarn-cache/.yarn-mutex' && 'yarn' 'test' '--maxWorkers=2'");
        assertThat(devRuntimeProperties.command("java21-roles-verify"))
                .containsExactly("mvn", "-B", "-ntp", "verify");
        assertThat(devRuntimeProperties.requiredReports("java21-roles-verify")).hasSize(134);
        assertThat(devDeliveryProperties.createPullRequest()).isTrue();
    }

    /** Operators override these through the environment (the pilot points ui-users at a fork). */
    @Test
    void repositoriesAndDeliveryTargetsDefaultToTheUpstreamRepositories() {
        assumeTrue(REPOSITORY_OVERRIDES.stream().noneMatch(name -> {
            String value = System.getenv(name);
            return value != null && !value.isBlank();
        }), "A repository override is set in the environment");
        assertThat(devFactoryProperties.repositories().get("sidecar"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/folio-module-sidecar", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-unit", List.of("MODSIDECAR"), List.of()));
        assertThat(devFactoryProperties.repositories().get("mod-roles-keycloak"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/mod-roles-keycloak", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-roles-verify", List.of("MODROLESKC"), List.of()));
        assertThat(devFactoryProperties.repositories().get("mgr-tenant-entitlements"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/mgr-tenant-entitlements", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-unit", List.of("MGRENTITLE"), List.of()));
        assertThat(devFactoryProperties.repositories().get("mgr-applications"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/mgr-applications", "master", "maven:3.9-eclipse-temurin-21",
                        "java21-unit", List.of("MGRAPPS"), List.of()));
        assertThat(devFactoryProperties.repositories().get("ui-users"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/ui-users", "master", "node:22.22.0-bookworm",
                        "node22-jest", List.of("UIU"), List.of()));
        assertThat(devFactoryProperties.repositories().get("ui-circulation"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/ui-circulation", "master", "node:22.22.0-bookworm",
                        "node22-jest", List.of("UICIRC"), List.of()));
        assertThat(devFactoryProperties.repositories().get("ui-inventory"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/ui-inventory", "master", "node:22.22.0-bookworm",
                        "node22-jest", List.of("UIIN"), List.of()));
        assertThat(devFactoryProperties.repositories().get("ui-marc-authorities"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/ui-marc-authorities", "master", "node:22.22.0-bookworm",
                        "node22-jest", List.of("UIMARCAUTH"), List.of()));
        assertThat(devFactoryProperties.repositories().get("ui-checkin"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/ui-checkin", "master", "node:22.22.0-bookworm",
                        "node22-jest", List.of("UICHKIN"), List.of()));
        assertThat(devFactoryProperties.repositories().get("ui-eholdings"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/ui-eholdings", "master", "node:22.22.0-bookworm",
                        "node22-jest", List.of("UIEH"), List.of()));
        assertThat(devFactoryProperties.repositories().get("ui-myprofile"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/ui-myprofile", "master", "node:22.22.0-bookworm",
                        "node22-jest", List.of("UIMPROF"), List.of()));
        assertThat(devFactoryProperties.repositories().get("ui-tags"))
                .isEqualTo(new DevFactoryProperties.Repository(
                        "folio-org/ui-tags", "master", "node:22.22.0-bookworm",
                        "node22-jest", List.of("UITAG"), List.of()));
        assertThat(devDeliveryProperties.targets()).containsOnly(
                entry("sidecar", new DeliveryTarget("folio-org/folio-module-sidecar", "master", true)),
                entry("mod-roles-keycloak", new DeliveryTarget("folio-org/mod-roles-keycloak", "master", true)),
                entry("mgr-tenant-entitlements", new DeliveryTarget("folio-org/mgr-tenant-entitlements", "master", true)),
                entry("mgr-applications", new DeliveryTarget("folio-org/mgr-applications", "master", true)),
                entry("ui-users", new DeliveryTarget("folio-org/ui-users", "master", true)),
                entry("ui-circulation", new DeliveryTarget("folio-org/ui-circulation", "master", true)),
                entry("ui-inventory", new DeliveryTarget("folio-org/ui-inventory", "master", true)),
                entry("ui-marc-authorities", new DeliveryTarget("folio-org/ui-marc-authorities", "master", true)),
                entry("ui-checkin", new DeliveryTarget("folio-org/ui-checkin", "master", true)),
                entry("ui-eholdings", new DeliveryTarget("folio-org/ui-eholdings", "master", true)),
                entry("ui-myprofile", new DeliveryTarget("folio-org/ui-myprofile", "master", true)),
                entry("ui-tags", new DeliveryTarget("folio-org/ui-tags", "master", true)));
    }
}
