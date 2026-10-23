package org.folio.factory.devfactory.delivery;

/** Trusted operator configuration, never taken from a task or coding output. */
public record DeliveryTarget(String repository, String baseBranch, boolean authorized) {
    public void requireAuthorized() {
        if (!authorized) throw new DeliveryBlockedException("Delivery requires explicit operator authorization");
        if (repository == null || !repository.matches("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+")) {
            throw new DeliveryBlockedException(
                    "Delivery requires an explicitly authorized GitHub owner/repository destination");
        }
        if (baseBranch == null || baseBranch.isBlank()) throw new DeliveryBlockedException("Delivery base branch is missing");
    }
}
