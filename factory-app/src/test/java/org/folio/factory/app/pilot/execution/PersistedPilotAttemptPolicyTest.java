package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.devfactory.pilot.AttemptDeadline;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The persisted deadline mapping: pre-S05 attempts (null deadline_at) resolve
 * as unbounded, and a persisted budget is exposed unchanged.
 */
class PersistedPilotAttemptPolicyTest {

    private final PilotAttemptRepository attempts = mock(PilotAttemptRepository.class);
    private final PersistedPilotAttemptPolicy policy = new PersistedPilotAttemptPolicy(
            attempts, mock(PilotTaskRepository.class), mock(PilotInstallationRepository.class),
            mock(PlatformTransactionManager.class));

    @Test
    void aLegacyAttemptWithoutPersistedDeadlineResolvesAsUnbounded() {
        UUID executionId = UUID.randomUUID();
        PilotAttempt attempt = new PilotAttempt(UUID.randomUUID(), 1, PilotAttempt.Kind.INITIAL,
                "request-1", PilotAttempt.State.RUNNING);
        attempt.setExecutionId(executionId);
        when(attempts.findByExecutionId(executionId)).thenReturn(Optional.of(attempt));

        assertThat(policy.deadline(executionId)).isEmpty();
    }

    @Test
    void aPersistedDeadlineIsExposedForBoundedAttempts() {
        UUID executionId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-10-23T10:00:00Z");
        PilotAttempt attempt = new PilotAttempt(UUID.randomUUID(), 1, PilotAttempt.Kind.INITIAL,
                "request-1", PilotAttempt.State.RUNNING);
        attempt.setExecutionId(executionId);
        attempt.setDeadlineAt(expiresAt);
        when(attempts.findByExecutionId(executionId)).thenReturn(Optional.of(attempt));

        assertThat(policy.deadline(executionId)).contains(new AttemptDeadline(expiresAt));
    }
}
