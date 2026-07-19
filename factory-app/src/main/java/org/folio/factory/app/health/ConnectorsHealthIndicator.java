package org.folio.factory.app.health;

import org.folio.factory.connectors.ConnectorHealth;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Surfaces each external connector's configured / not-configured state through the
 * actuator health endpoint (component id {@code connectors}).
 *
 * <p>Deliberately reports {@code UP} even when connectors are unconfigured: running
 * without credentials is the platform's expected advisory (degraded) mode, not a
 * failure — the flow still completes end-to-end and every skipped external sync is
 * audited as {@code CONNECTOR_SKIPPED}. The per-connector detail lets an operator see
 * at a glance which integrations are live without turning a normal degraded run into
 * a DOWN pod.</p>
 */
@Component
public class ConnectorsHealthIndicator implements HealthIndicator {

    private final List<ConnectorHealth> connectors;

    public ConnectorsHealthIndicator(List<ConnectorHealth> connectors) {
        this.connectors = connectors;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        for (ConnectorHealth connector : connectors) {
            builder.withDetail(connector.connectorName(),
                    connector.isConfigured() ? "configured" : "not-configured");
        }
        return builder.build();
    }
}
