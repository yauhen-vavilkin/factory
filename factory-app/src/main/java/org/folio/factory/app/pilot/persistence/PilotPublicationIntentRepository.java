package org.folio.factory.app.pilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PilotPublicationIntentRepository extends JpaRepository<PilotPublicationIntent, UUID> {

    Optional<PilotPublicationIntent> findByExecutionId(UUID executionId);

    Optional<PilotPublicationIntent> findByAttemptId(UUID attemptId);
}
