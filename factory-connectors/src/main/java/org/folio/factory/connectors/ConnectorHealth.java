package org.folio.factory.connectors;

/**
 * Lets the platform report which external integrations are live.
 */
public interface ConnectorHealth {

    String connectorName();

    boolean isConfigured();
}
