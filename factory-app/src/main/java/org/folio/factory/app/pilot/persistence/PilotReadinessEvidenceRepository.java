package org.folio.factory.app.pilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PilotReadinessEvidenceRepository extends JpaRepository<PilotReadinessEvidence, UUID> {

    Optional<PilotReadinessEvidence> findTopByOrderByCheckedAtDesc();

    Optional<PilotReadinessEvidence> findTopByPassedTrueOrderByCheckedAtDesc();
}
