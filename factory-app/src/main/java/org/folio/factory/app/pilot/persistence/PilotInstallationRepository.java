package org.folio.factory.app.pilot.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PilotInstallationRepository extends JpaRepository<PilotInstallation, Integer> {

    /**
     * Locks the singleton installation row so only one dispatch at a time can
     * reserve the active attempt slot. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PilotInstallation i where i.id = :id")
    Optional<PilotInstallation> lockById(@Param("id") int id);
}
