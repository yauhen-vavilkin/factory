package org.folio.factory.app;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemovedDeliveryAuthorizationCheckTest {

    @Test
    void anOldCatalogThatStillSetsAuthorizedRefusesToStart() {
        StandardEnvironment environment = environment(Map.of(
                "factory.dev-factory.repositories.mod-roles-keycloak.delivery.repository", "folio-org/mod-roles-keycloak",
                "factory.dev-factory.repositories.mod-roles-keycloak.delivery.authorized", "false",
                "factory.dev-factory.delivery.targets.sidecar.authorized", "true"));

        assertThatThrownBy(() -> new RemovedDeliveryAuthorizationCheck(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("factory.dev-factory.delivery.targets.sidecar.authorized, "
                        + "factory.dev-factory.repositories.mod-roles-keycloak.delivery.authorized");
    }

    @Test
    void aCatalogWithoutTheSwitchStarts() {
        StandardEnvironment environment = environment(Map.of(
                "factory.dev-factory.repositories.sidecar.delivery.repository", "folio-org/folio-module-sidecar",
                "factory.dev-factory.delivery.create-pull-request", "true"));

        assertThatCode(() -> new RemovedDeliveryAuthorizationCheck(environment)).doesNotThrowAnyException();
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("catalog", properties));
        return environment;
    }
}
