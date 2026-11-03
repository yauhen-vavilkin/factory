package org.folio.factory.app.tasks.persistence;

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

public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, UUID> {

    List<TaskAttempt> findByTaskIdOrderByOrdinalAsc(UUID taskId);

    Optional<TaskAttempt> findByRequestId(String requestId);

    Optional<TaskAttempt> findByExecutionId(UUID executionId);

    List<TaskAttempt> findByTaskIdAndStateIn(UUID taskId, Collection<TaskAttempt.State> states);

    /** Tasks that were started at least once, the most recently clicked first. */
    @Query(value = """
            select a.taskId from TaskAttempt a
            where a.state <> :withdrawn
            group by a.taskId
            order by max(a.createdAt) desc
            """, countQuery = """
            select count(distinct a.taskId) from TaskAttempt a where a.state <> :withdrawn
            """)
    Page<UUID> findTriggeredTaskIds(@Param("withdrawn") TaskAttempt.State withdrawn, Pageable page);

    /** The newest change to any attempt, so My tasks reloads when work moves on. */
    @Query("select max(a.updatedAt) from TaskAttempt a")
    Optional<Instant> findLatestUpdate();

    long countByStateIn(Collection<TaskAttempt.State> states);

    default List<TaskAttempt> findLiveByTaskId(UUID taskId) {
        return findByTaskIdAndStateIn(taskId, TaskAttempt.State.LIVE_STATES);
    }

    /**
     * Dispatch candidates in click order: the attempt that was started first runs
     * first. Callers limit via pageable.
     */
    @Query("""
            select a from TaskAttempt a
            where a.state = :state
            order by a.createdAt asc, a.id asc
            """)
    List<TaskAttempt> findDispatchable(@Param("state") TaskAttempt.State state, Pageable limit);
}
