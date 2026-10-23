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

    List<PilotTask> findByJiraInstanceAndInScopeTrueAndEligibility(String jiraInstance,
                                                                   PilotTask.Eligibility eligibility);

    long countByJiraInstance(String jiraInstance);

    /**
     * Locks the logical task row so admission can reserve the initial attempt
     * under mutual exclusion. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PilotTask t where t.id = :id")
    Optional<PilotTask> lockById(@Param("id") UUID id);
}
