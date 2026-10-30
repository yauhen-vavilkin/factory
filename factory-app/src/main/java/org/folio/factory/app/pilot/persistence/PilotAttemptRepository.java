package org.folio.factory.app.pilot.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PilotAttemptRepository extends JpaRepository<PilotAttempt, UUID> {

    List<PilotAttempt> findByTaskIdOrderByOrdinalAsc(UUID taskId);

    Optional<PilotAttempt> findByRequestId(String requestId);

    Optional<PilotAttempt> findByExecutionId(UUID executionId);

    List<PilotAttempt> findByTaskIdAndStateIn(UUID taskId, Collection<PilotAttempt.State> states);

    /** Tasks that were started at least once, the most recently clicked first. */
    @Query(value = """
            select a.taskId from PilotAttempt a
            where a.state <> :withdrawn
            group by a.taskId
            order by max(a.createdAt) desc
            """, countQuery = """
            select count(distinct a.taskId) from PilotAttempt a where a.state <> :withdrawn
            """)
    Page<UUID> findTriggeredTaskIds(@Param("withdrawn") PilotAttempt.State withdrawn, Pageable page);

    /** The newest change to any attempt, so My tasks reloads when work moves on. */
    @Query("select max(a.updatedAt) from PilotAttempt a")
    Optional<Instant> findLatestUpdate();

    long countByStateIn(Collection<PilotAttempt.State> states);

    default List<PilotAttempt> findLiveByTaskId(UUID taskId) {
        return findByTaskIdAndStateIn(taskId, PilotAttempt.State.LIVE_STATES);
    }

    /**
     * Dispatch candidates in click order: the attempt that was started first runs
     * first. Callers limit via pageable.
     */
    @Query("""
            select a from PilotAttempt a
            where a.state = :state
            order by a.createdAt asc, a.id asc
            """)
    List<PilotAttempt> findDispatchable(@Param("state") PilotAttempt.State state, Pageable limit);
}
