package org.folio.factory.app.tasks.execution;

import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.devfactory.tasks.AttemptDeadline;
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
class PersistedTaskAttemptPolicyTest {

    private final TaskAttemptRepository attempts = mock(TaskAttemptRepository.class);
    private final PersistedTaskAttemptPolicy policy = new PersistedTaskAttemptPolicy(
            attempts, mock(TaskRepository.class), mock(TaskInstallationRepository.class),
            mock(org.folio.factory.app.tasks.persistence.TaskPublicationIntentRepository.class),
            mock(TaskPublicationGuard.class), mock(PlatformTransactionManager.class));

    @Test
    void aLegacyAttemptWithoutPersistedDeadlineResolvesAsUnbounded() {
        UUID executionId = UUID.randomUUID();
        TaskAttempt attempt = new TaskAttempt(UUID.randomUUID(), 1, TaskAttempt.Kind.INITIAL,
                "request-1", TaskAttempt.State.RUNNING);
        attempt.setExecutionId(executionId);
        when(attempts.findByExecutionId(executionId)).thenReturn(Optional.of(attempt));

        assertThat(policy.deadline(executionId)).isEmpty();
    }

    @Test
    void aPersistedDeadlineIsExposedForBoundedAttempts() {
        UUID executionId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-10-23T10:00:00Z");
        TaskAttempt attempt = new TaskAttempt(UUID.randomUUID(), 1, TaskAttempt.Kind.INITIAL,
                "request-1", TaskAttempt.State.RUNNING);
        attempt.setExecutionId(executionId);
        attempt.setDeadlineAt(expiresAt);
        when(attempts.findByExecutionId(executionId)).thenReturn(Optional.of(attempt));

        assertThat(policy.deadline(executionId)).contains(new AttemptDeadline(expiresAt));
    }
}
