package org.folio.factory.app.pilot.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PilotTaskRepository extends JpaRepository<PilotTask, UUID> {

    Optional<PilotTask> findByJiraInstanceAndIssueId(String jiraInstance, String issueId);

    List<PilotTask> findByJiraInstanceAndInScopeTrue(String jiraInstance);

    long countByJiraInstance(String jiraInstance);

    /** My tasks on the configured Jira never started (or only withdrawn before start), newest Jira task first. */
    @Query("""
            select t from PilotTask t
            where t.jiraInstance = :jiraInstance and t.inScope = true
              and not exists (select a from PilotAttempt a where a.taskId = t.id and a.state <> :withdrawn)
            order by t.jiraCreatedAt desc, t.displayKey asc
            """)
    List<PilotTask> findAvailable(@Param("jiraInstance") String jiraInstance,
                                  @Param("withdrawn") PilotAttempt.State withdrawn);

    /**
     * Locks the logical task row so admission can reserve the initial attempt
     * under mutual exclusion. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PilotTask t where t.id = :id")
    Optional<PilotTask> lockById(@Param("id") UUID id);
}
