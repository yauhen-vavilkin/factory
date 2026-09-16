package org.folio.factory.devfactory.delivery;

/** Trusted operator configuration, never taken from a task or coding output. */
public record DeliveryTarget(String repository, String baseBranch, boolean authorized) {
    public void requireAuthorized() {
        if (!authorized) throw new IllegalStateException("Delivery requires explicit operator authorization");
        if (repository == null || !repository.matches("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+")
                || repository.split("/")[0].equalsIgnoreCase("folio-org")) {
            throw new IllegalStateException("Delivery requires a configured user-owned GitHub fork");
        }
        if (baseBranch == null || baseBranch.isBlank()) throw new IllegalStateException("Delivery base branch is missing");
    }
}
