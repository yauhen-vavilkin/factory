package org.folio.factory.core.registry;

public class FlowValidationException extends RuntimeException {

    public FlowValidationException(String message) {
        super(message);
    }

    public FlowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
