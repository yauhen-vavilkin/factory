package org.folio.factory.app;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DeveloperRepositoryConfigurationTest {
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
            var target = configured.delivery().targets().get(repositoryKey);

            assertThat(source.sourceRepo()).isEqualTo("Source-Owner/Module.Case");
            assertThat(target.repository()).isEqualTo("delivery-owner/Module.Case");
            assertThat(source.baseBranch()).isEqualTo("codex/clean-startup-check");
            assertThat(target.baseBranch()).isEqualTo(source.baseBranch());
            assertThat(target.authorized()).isEqualTo(authorized);
            if (authorized) {
                assertThat(configured.delivery().requireTarget(repositoryKey)).isEqualTo(target);
            } else {
                assertThatThrownBy(() -> configured.delivery().requireTarget(repositoryKey))
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
                    assertThat(configured.delivery().targets().get(otherKey))
                            .isEqualTo(defaults.delivery().targets().get(otherKey));
                }
            }
        }
    }

    private static Configuration bind(Map<String, Object> variables) throws Exception {
        var environment = new StandardEnvironment();
        // Keep the developer's actual repository settings and credentials out of the fixture.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", variables));
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")))
            environment.getPropertySources().addLast(source);
        var binder = Binder.get(environment);
        return new Configuration(
                binder.bind("factory.dev-factory", DevFactoryProperties.class).get(),
                binder.bind("factory.dev-factory.delivery", DevDeliveryProperties.class).get());
    }

    private record Configuration(DevFactoryProperties source, DevDeliveryProperties delivery) {}
}
