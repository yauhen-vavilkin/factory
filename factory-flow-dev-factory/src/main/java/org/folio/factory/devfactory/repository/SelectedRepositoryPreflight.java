package org.folio.factory.devfactory.repository;

/** Rechecks the confirmed repository before checkout/build, using the task's frozen base. */
@FunctionalInterface
public interface SelectedRepositoryPreflight {
    void requireRepositoryReady(String repositoryKey, String frozenBaseSha);

    /** Standalone workers may run without the application's access probes. */
    static SelectedRepositoryPreflight none() {
        return (repositoryKey, frozenBaseSha) -> {
            // No application preflight is installed in this standalone composition.
        };
    }
}
