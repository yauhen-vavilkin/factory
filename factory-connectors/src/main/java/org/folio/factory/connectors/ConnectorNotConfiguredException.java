package org.folio.factory.connectors;

/**
 * Thrown by fallback connector implementations when credentials are absent.
 * Data-critical callers let it fail the step; side-effect callers catch it, audit
 * a CONNECTOR_SKIPPED event and continue.
 */
public class ConnectorNotConfiguredException extends RuntimeException {

    public ConnectorNotConfiguredException(String message) {
        super(message);
    }
}
