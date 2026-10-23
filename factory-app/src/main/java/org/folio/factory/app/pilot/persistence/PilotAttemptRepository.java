package org.folio.factory.app.pilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PilotAttemptRepository extends JpaRepository<PilotAttempt, UUID> {

    List<PilotAttempt> findByTaskIdOrderByOrdinalAsc(UUID taskId);

    Optional<PilotAttempt> findByRequestId(String requestId);

    Optional<PilotAttempt> findByExecutionId(UUID executionId);

    List<PilotAttempt> findByTaskIdAndStateIn(UUID taskId, Collection<PilotAttempt.State> states);

    default List<PilotAttempt> findLiveByTaskId(UUID taskId) {
        return findByTaskIdAndStateIn(taskId, PilotAttempt.State.LIVE_STATES);
    }
}
