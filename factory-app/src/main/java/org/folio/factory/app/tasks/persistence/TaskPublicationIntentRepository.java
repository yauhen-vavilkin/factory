package org.folio.factory.app.tasks.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TaskPublicationIntentRepository extends JpaRepository<TaskPublicationIntent, UUID> {

    Optional<TaskPublicationIntent> findByExecutionId(UUID executionId);

    Optional<TaskPublicationIntent> findByAttemptId(UUID attemptId);
}
