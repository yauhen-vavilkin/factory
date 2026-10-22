package org.folio.factory.app;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DeveloperRepositoryConfigurationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({
            "sidecar, SIDECAR",
            "mod-roles-keycloak, MOD_ROLES_KEYCLOAK",
            "mgr-tenant-entitlements, MGR_TENANT_ENTITLEMENTS",
            "mgr-applications, MGR_APPLICATIONS"
    })
    void repositoryEnvironmentKeepsSourceAndDeliveryAlignedWithoutChangingOtherTargets(
            String repositoryKey, String environmentPrefix) throws Exception {
        var defaults = bind(Map.of());
        var prefix = "FACTORY_" + environmentPrefix;

        for (boolean authorized : new boolean[]{true, false}) {
            var configured = bind(Map.of(
                    prefix + "_SOURCE_REPO", "Source-Owner/Module.Case",
                    prefix + "_DELIVERY_REPO", "delivery-owner/Module.Case",
                    prefix + "_BASE_BRANCH", "codex/clean-startup-check",
                    prefix + "_DELIVERY_AUTHORIZED", Boolean.toString(authorized)));
            var source = configured.source().repositories().get(repositoryKey);
            var target = configured.delivery().target(repositoryKey, configured.source());

            assertThat(source.sourceRepo()).isEqualTo("Source-Owner/Module.Case");
            assertThat(target.repository()).isEqualTo("delivery-owner/Module.Case");
            assertThat(source.baseBranch()).isEqualTo("codex/clean-startup-check");
            assertThat(target.baseBranch()).isEqualTo(source.baseBranch());
            assertThat(target.authorized()).isEqualTo(authorized);
            if (authorized) {
                assertThat(configured.delivery().requireTarget(repositoryKey, configured.source())).isEqualTo(target);
            } else {
                assertThatThrownBy(() -> configured.delivery().requireTarget(repositoryKey, configured.source()))
                        .isInstanceOf(DeliveryBlockedException.class)
                        .hasMessageContaining("explicit operator authorization");
            }

            var defaultSource = defaults.source().repositories().get(repositoryKey);
            assertThat(source.buildImage()).isEqualTo(defaultSource.buildImage());
            assertThat(source.verificationPlan()).isEqualTo(defaultSource.verificationPlan());
            assertThat(source.jiraProjects()).isEqualTo(defaultSource.jiraProjects());
            assertThat(configured.delivery().createPullRequest()).isEqualTo(defaults.delivery().createPullRequest());
            for (var otherKey : defaults.source().repositories().keySet()) {
                if (!otherKey.equals(repositoryKey)) {
                    assertThat(configured.source().repositories().get(otherKey))
                            .isEqualTo(defaults.source().repositories().get(otherKey));
                    assertThat(configured.delivery().target(otherKey, configured.source()))
                            .isEqualTo(defaults.delivery().target(otherKey, defaults.source()));
                }
            }
        }
    }

    @Test
    void externalCatalogAddsFifthRepositoryWithOneSourceAndDeliveryEntry() throws Exception {
        var file = directory.resolve("repositories.yaml");
        Files.writeString(file, """
                factory:
                  dev-factory:
                    repositories:
                      another-module:
                        source-repo: source-owner/another-module
                        base-branch: feature/base
                        build-image: maven:3.9-eclipse-temurin-21
                        verification-plan: java21-unit
                        jira-projects: [ANOTHER]
                        jira-components: [Backend]
                        delivery:
                          repository: delivery-owner/another-module
                          authorized: true
                """);
        var configured = bind(Map.of(), file);
        var repository = configured.source().repositories().get("another-module");
        assertThat(configured.source().repositories()).hasSize(5);
        assertThat(repository.sourceRepo()).isEqualTo("source-owner/another-module");
        assertThat(repository.jiraProjects()).containsExactly("ANOTHER");
        assertThat(repository.jiraComponents()).containsExactly("Backend");
        assertThat(configured.delivery().requireTarget("another-module", configured.source()))
                .isEqualTo(new DeliveryTarget("delivery-owner/another-module", "feature/base", true));
        assertThat(configured.runtime().command(repository.verificationPlan()))
                .containsExactly("mvn", "-B", "-ntp", "test");
        assertThat(configured.runtime().requiredReports("java21-roles-verify")).hasSize(134);
    }

    @Test
    void catalogDeliveryDefaultsToDisabledAndLegacyExplicitTargetOverridesDuringMigration() throws Exception {
        var defaults = bind(Map.of());
        assertThat(defaults.delivery().targets()).isEmpty();
        assertThat(defaults.delivery().effectiveTargets(defaults.source()).values())
                .allMatch(target -> !target.authorized());

        var configured = bind(Map.of(
                "factory.dev-factory.delivery.targets.sidecar.repository", "legacy-owner/module",
                "factory.dev-factory.delivery.targets.sidecar.base-branch", "legacy-base",
                "factory.dev-factory.delivery.targets.sidecar.authorized", true));
        assertThat(configured.delivery().requireTarget("sidecar", configured.source()))
                .isEqualTo(new DeliveryTarget("legacy-owner/module", "legacy-base", true));
        assertThat(configured.source().repositories().get("sidecar").delivery().authorized()).isFalse();
    }

    @Test
    void rejectsConflictingSourceAndNestedDeliveryBaseBranches() {
        assertThatThrownBy(() -> bind(Map.of(
                "factory.dev-factory.repositories.sidecar.delivery.base-branch", "different-base")))
                .hasRootCauseMessage("Repository delivery base-branch must match source base-branch");
    }

    @Test
    void partialLegacyDestinationInheritsTheCatalogBase() throws Exception {
        var configured = bind(Map.of(
                "factory.dev-factory.delivery.targets.sidecar.repository", "legacy-owner/module",
                "factory.dev-factory.delivery.targets.sidecar.authorized", true));
        assertThat(configured.delivery().requireTarget("sidecar", configured.source()))
                .isEqualTo(new DeliveryTarget("legacy-owner/module", "master", true));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialLegacyAuthorizationKeepsCatalogDestinationAndHonorsExplicitFalse(boolean authorized) throws Exception {
        var configured = bind(Map.of(
                "FACTORY_SIDECAR_DELIVERY_REPO", "catalog-owner/module",
                "FACTORY_SIDECAR_BASE_BRANCH", "catalog-base",
                "FACTORY_SIDECAR_DELIVERY_AUTHORIZED", true,
                "factory.dev-factory.delivery.targets.sidecar.authorized", authorized));
        assertThat(configured.delivery().target("sidecar", configured.source()))
                .isEqualTo(new DeliveryTarget("catalog-owner/module", "catalog-base", authorized));
        if (authorized) {
            assertThatCode(() -> configured.delivery().requireTarget("sidecar", configured.source())).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> configured.delivery().requireTarget("sidecar", configured.source()))
                    .isInstanceOf(DeliveryBlockedException.class);
        }
    }

    @Test
    void rejectsInvalidExternalRepositoryAndMissingDeliveryRemainsBlocked() throws Exception {
        assertThatThrownBy(() -> bind(Map.of(
                "factory.dev-factory.repositories.sidecar.source-repo", "invalid")))
                .hasRootCauseMessage("Invalid Developer Flow source-repo 'invalid'");
        var repository = new DevFactoryProperties.Repository(
                "owner/repo", "main", "maven:3.9-eclipse-temurin-21", "unit", List.of("TASK"), List.of());
        var repositories = new DevFactoryProperties(null, new java.util.TreeMap<>(Map.of("new", repository)));
        assertThatThrownBy(() -> new DevDeliveryProperties(Map.of(), true).requireTarget("new", repositories))
                .isInstanceOf(DeliveryBlockedException.class)
                .hasMessageContaining("Missing delivery configuration");
    }

    private static Configuration bind(Map<String, Object> variables) throws Exception {
        return bind(variables, null);
    }

    private static Configuration bind(Map<String, Object> variables, Path externalCatalog) throws Exception {
        var environment = new StandardEnvironment();
        // Keep the developer's actual repository settings and credentials out of the fixture.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", variables));
        if (externalCatalog != null)
            for (var source : new YamlPropertySourceLoader().load("external", new FileSystemResource(externalCatalog)))
                environment.getPropertySources().addLast(source);
        for (var source : new YamlPropertySourceLoader().load("repositories", new ClassPathResource("developer-repositories.yaml")))
            environment.getPropertySources().addLast(source);
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")))
            environment.getPropertySources().addLast(source);
        var binder = Binder.get(environment);
        return new Configuration(
                binder.bind("factory.dev-factory", DevFactoryProperties.class).get(),
                binder.bind("factory.dev-factory.delivery", DevDeliveryProperties.class).get(),
                binder.bind("factory.dev-factory.runtime", org.folio.factory.devfactory.runtime.DevRuntimeProperties.class).get());
    }

    private record Configuration(DevFactoryProperties source, DevDeliveryProperties delivery,
                                 org.folio.factory.devfactory.runtime.DevRuntimeProperties runtime) {}
}
