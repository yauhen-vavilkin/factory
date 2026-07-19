package org.folio.factory.app.health;

import org.folio.factory.connectors.ConnectorHealth;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorsHealthIndicatorTest {

    private record FakeConnector(String connectorName, boolean isConfigured) implements ConnectorHealth {
    }

    @Test
    void health_upWithPerConnectorConfigurationDetails() {
        ConnectorsHealthIndicator indicator = new ConnectorsHealthIndicator(
                List.of(new FakeConnector("jira", true), new FakeConnector("testrail", false)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .isEqualTo(Map.of("jira", "configured", "testrail", "not-configured"));
    }
}
