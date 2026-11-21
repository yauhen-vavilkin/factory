package org.folio.factory.app.tasks.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TaskInstallationRepository extends JpaRepository<TaskInstallation, Integer> {

    /**
     * Locks the singleton installation row so only one dispatch at a time can
     * reserve the active attempt slot. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from TaskInstallation i where i.id = :id")
    Optional<TaskInstallation> lockById(@Param("id") int id);
}
