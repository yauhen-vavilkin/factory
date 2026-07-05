package org.folio.factory.core.repository;

import org.folio.factory.core.domain.FlowRegistryEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowRegistryEntryRepository extends JpaRepository<FlowRegistryEntry, FlowRegistryEntry.Key> {
}
