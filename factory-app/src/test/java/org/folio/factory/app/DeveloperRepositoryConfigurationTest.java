package org.folio.factory.app;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

        var configured = bind(Map.of(
                prefix + "_SOURCE_REPO", "Source-Owner/Module.Case",
                prefix + "_DELIVERY_REPO", "delivery-owner/Module.Case",
                prefix + "_BASE_BRANCH", "codex/clean-startup-check"));
        var source = configured.source().repositories().get(repositoryKey);
        var target = configured.delivery().target(repositoryKey, configured.source());

        assertThat(source.sourceRepo()).isEqualTo("Source-Owner/Module.Case");
        assertThat(target.repository()).isEqualTo("delivery-owner/Module.Case");
        assertThat(source.baseBranch()).isEqualTo("codex/clean-startup-check");
        assertThat(target.baseBranch()).isEqualTo(source.baseBranch());
        assertThat(configured.delivery().requireTarget(repositoryKey, configured.source())).isEqualTo(target);

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
                """);
        var configured = bind(Map.of(), file);
        var repository = configured.source().repositories().get("another-module");
        assertThat(configured.source().repositories()).hasSize(5);
        assertThat(repository.sourceRepo()).isEqualTo("source-owner/another-module");
        assertThat(repository.jiraProjects()).containsExactly("ANOTHER");
        assertThat(repository.jiraComponents()).containsExactly("Backend");
        assertThat(configured.delivery().requireTarget("another-module", configured.source()))
                .isEqualTo(new DeliveryTarget("delivery-owner/another-module", "feature/base"));
        assertThat(configured.runtime().command(repository.verificationPlan()))
                .containsExactly("mvn", "-B", "-ntp", "test");
        assertThat(configured.runtime().requiredReports("java21-roles-verify"))
                .containsExactly("surefire", "failsafe");
    }

    @Test
    void catalogDeliveryDefaultsToTheOfficialRepositoryAndLegacyExplicitTargetOverridesDuringMigration()
            throws Exception {
        var defaults = bind(Map.of());
        assertThat(defaults.delivery().targets()).isEmpty();
        assertThat(defaults.delivery().effectiveTargets(defaults.source()).values())
                .allMatch(target -> target.repository().startsWith("folio-org/"));

        var configured = bind(Map.of(
                "factory.dev-factory.delivery.targets.sidecar.repository", "legacy-owner/module",
                "factory.dev-factory.delivery.targets.sidecar.base-branch", "legacy-base"));
        assertThat(configured.delivery().requireTarget("sidecar", configured.source()))
                .isEqualTo(new DeliveryTarget("legacy-owner/module", "legacy-base"));
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
                "factory.dev-factory.delivery.targets.sidecar.repository", "legacy-owner/module"));
        assertThat(configured.delivery().requireTarget("sidecar", configured.source()))
                .isEqualTo(new DeliveryTarget("legacy-owner/module", "master"));
    }

    @Test
    void rejectsInvalidExternalRepository() {
        assertThatThrownBy(() -> bind(Map.of(
                "factory.dev-factory.repositories.sidecar.source-repo", "invalid")))
                .hasRootCauseMessage("Invalid Developer Flow source-repo 'invalid'");
    }

    @Test
    void missingDeliveryConfigurationRemainsBlocked() {
        var repository = new DevFactoryProperties.Repository(
                "owner/repo", "main", "maven:3.9-eclipse-temurin-21", "unit", List.of("TASK"), List.of());
        var repositories = new DevFactoryProperties(null, new TreeMap<>(Map.of("new", repository)));
        var delivery = new DevDeliveryProperties(Map.of(), true);

        assertThatThrownBy(() -> delivery.requireTarget("new", repositories))
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
        if (externalCatalog != null) {
            loadYaml(environment, "external", new FileSystemResource(externalCatalog));
        }
        loadYaml(environment, "repositories", new ClassPathResource("developer-repositories.yaml"));
        loadYaml(environment, "application", new ClassPathResource("application.yaml"));
        var binder = Binder.get(environment);
        return new Configuration(
                binder.bind("factory.dev-factory", DevFactoryProperties.class).get(),
                binder.bind("factory.dev-factory.delivery", DevDeliveryProperties.class).get(),
                binder.bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get());
    }

    private static void loadYaml(StandardEnvironment environment, String name, Resource resource)
            throws IOException {
        var propertySources = new YamlPropertySourceLoader().load(name, resource);
        for (var source : propertySources) {
            environment.getPropertySources().addLast(source);
        }
    }

    private record Configuration(
            DevFactoryProperties source,
            DevDeliveryProperties delivery,
            DevRuntimeProperties runtime) {}
}
