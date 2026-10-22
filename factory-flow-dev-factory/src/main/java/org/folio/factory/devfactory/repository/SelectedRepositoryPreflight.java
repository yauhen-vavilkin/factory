package org.folio.factory.devfactory.repository;

/** Rechecks the confirmed repository before checkout/build, using the task's frozen base. */
@FunctionalInterface
public interface SelectedRepositoryPreflight {
    void requireRepositoryReady(String repositoryKey, String frozenBaseSha);
}
