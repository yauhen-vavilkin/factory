package org.folio.factory.devfactory.delivery;

/** A permanent delivery condition that must complete honestly instead of being retried by the engine. */
public class DeliveryBlockedException extends IllegalStateException {
    public DeliveryBlockedException(String message) {
        super(message);
    }
}
