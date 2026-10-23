package org.folio.factory.app.pilot.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PilotAttemptRepository extends JpaRepository<PilotAttempt, UUID> {

    List<PilotAttempt> findByTaskIdOrderByOrdinalAsc(UUID taskId);

    Optional<PilotAttempt> findByRequestId(String requestId);

    Optional<PilotAttempt> findByExecutionId(UUID executionId);

    List<PilotAttempt> findByTaskIdAndStateIn(UUID taskId, Collection<PilotAttempt.State> states);

    List<PilotAttempt> findByStateAndScopeFingerprint(PilotAttempt.State state, String scopeFingerprint);

    default List<PilotAttempt> findLiveByTaskId(UUID taskId) {
        return findByTaskIdAndStateIn(taskId, PilotAttempt.State.LIVE_STATES);
    }

    /**
     * Dispatch candidates in stable queue order: the task's Jira creation time
     * first, then display key. Callers limit via pageable.
     */
    @Query("""
            select a from PilotAttempt a
            join PilotTask t on t.id = a.taskId
            where a.state = :state
            order by t.jiraCreatedAt asc, t.displayKey asc
            """)
    List<PilotAttempt> findDispatchable(@Param("state") PilotAttempt.State state, Pageable limit);
}
