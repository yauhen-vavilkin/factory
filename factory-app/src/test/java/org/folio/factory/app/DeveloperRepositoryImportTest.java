package org.folio.factory.app;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises Boot's real ConfigData imports, without loading the application or connectors. */
class DeveloperRepositoryImportTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void externalCatalogOverridesDefaultsAndAddsRepositoriesWhileExplicitEnvironmentWins(boolean override)
            throws Exception {
        Path catalog = directory.resolve("repositories.yaml");
        Files.writeString(catalog, """
                factory:
                  dev-factory:
                    repositories:
                      sidecar:
                        source-repo: ${FACTORY_SIDECAR_SOURCE_REPO:catalog-owner/sidecar}
                        base-branch: ${FACTORY_SIDECAR_BASE_BRANCH:catalog-base}
                        delivery:
                          repository: ${FACTORY_SIDECAR_DELIVERY_REPO:catalog-delivery/sidecar}
                          authorized: ${FACTORY_SIDECAR_DELIVERY_AUTHORIZED:true}
                      another-module:
                        source-repo: fifth-owner/module
                        base-branch: main
                        build-image: maven:3.9-eclipse-temurin-21
                        verification-plan: java21-unit
                        jira-projects: [ANOTHER]
                        delivery:
                          repository: fifth-delivery/module
                          authorized: true
                """);

        var runner = new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withPropertyValues(
                        // Do not discover application.yaml or a checkout-local catalog implicitly.
                        "spring.config.name=repository-import-fixture",
                        "spring.config.import=classpath:developer-repositories.yaml," + catalog.toUri())
                .withUserConfiguration(PropertiesConfiguration.class);
        if (override) runner = runner.withPropertyValues(
                "FACTORY_SIDECAR_SOURCE_REPO=environment-owner/sidecar",
                "FACTORY_SIDECAR_BASE_BRANCH=environment-base",
                "FACTORY_SIDECAR_DELIVERY_REPO=environment-delivery/sidecar",
                "FACTORY_SIDECAR_DELIVERY_AUTHORIZED=false");

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var repositories = context.getBean(DevFactoryProperties.class);
            var delivery = context.getBean(DevDeliveryProperties.class);
            var runtime = context.getBean(DevRuntimeProperties.class);
            assertThat(repositories.repositories()).containsOnlyKeys(
                    "sidecar", "mod-roles-keycloak", "mgr-tenant-entitlements", "mgr-applications", "another-module");
            var sidecar = repositories.repositories().get("sidecar");
            String base = override ? "environment-base" : "catalog-base";
            assertThat(sidecar.sourceRepo()).isEqualTo(override ? "environment-owner/sidecar" : "catalog-owner/sidecar");
            assertThat(sidecar.baseBranch()).isEqualTo(base);
            assertThat(delivery.target("sidecar", repositories)).isEqualTo(new DeliveryTarget(
                    override ? "environment-delivery/sidecar" : "catalog-delivery/sidecar", base, !override));
            assertThat(sidecar.jiraProjects()).containsExactly("MODSIDECAR");
            assertThat(sidecar.verificationPlan()).isEqualTo("java21-unit");
            assertThat(repositories.repositories().get("mgr-applications").sourceRepo())
                    .isEqualTo("folio-org/mgr-applications");
            assertThat(delivery.targets()).isEmpty();
            assertThat(delivery.requireTarget("another-module", repositories))
                    .isEqualTo(new DeliveryTarget("fifth-delivery/module", "main", true));
            assertThat(runtime.command("java21-unit")).containsExactly("mvn", "-B", "-ntp", "test");
            assertThat(runtime.requiredReports("java21-roles-verify")).hasSize(134);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({DevFactoryProperties.class, DevDeliveryProperties.class, DevRuntimeProperties.class})
    static class PropertiesConfiguration { }
}
