package org.folio.factory.app.tasks;

import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task persistence against real PostgreSQL: the database boundary alone prevents duplicate live
 * admissions, duplicate request ids, duplicate execution bindings and duplicate attempt ordinals, and
 * the persisted attempt policy grants task context and one paid start only to the bound execution.
 */
@FactoryIntegrationTest
class TaskPersistenceIntegrationTest {

    private static final String INSTANCE = TaskFixture.INSTANCE;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskAttemptRepository attempts;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TaskAttemptPolicy taskPolicy;

    @Test
    void concurrentReservationsCreateExactlyOneLiveAttempt() throws Exception {
        UUID taskId = tasks.save(task("10002", "MODBAR-2", INSTANCE)).getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<UUID>> reservations = List.of(
                    () -> reserve(taskId, 1, "req-concurrent-a"),
                    () -> reserve(taskId, 2, "req-concurrent-b"));
            List<Future<UUID>> outcomes = pool.invokeAll(reservations);
            int admitted = 0;
            for (Future<UUID> outcome : outcomes) {
                try {
                    outcome.get();
                    admitted++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                }
            }
            assertThat(admitted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        List<TaskAttempt> all = attempts.findByTaskIdOrderByOrdinalAsc(taskId);
        assertThat(all).hasSize(1);
        assertThat(attempts.findLiveByTaskId(taskId)).hasSize(1);
    }

    @Test
    void duplicateRequestIdIsRejectedAtTheDatabaseBoundary() {
        UUID taskA = tasks.save(task("10004", "MODBAR-4", INSTANCE)).getId();
        UUID taskB = tasks.save(task("10005", "MODBAR-5", INSTANCE)).getId();
        attempts.save(attempt(taskA, 1, TaskAttempt.State.WITHDRAWN, "req-duplicate"));

        assertThatThrownBy(() -> attempts.saveAndFlush(
                attempt(taskB, 1, TaskAttempt.State.WITHDRAWN, "req-duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateExecutionBindingIsRejectedAtTheDatabaseBoundary() {
        UUID taskA = tasks.save(task("10006", "MODBAR-6", INSTANCE)).getId();
        UUID taskB = tasks.save(task("10007", "MODBAR-7", INSTANCE)).getId();
        UUID executionId = UUID.randomUUID();
        TaskAttempt bound = attempt(taskA, 1, TaskAttempt.State.STOPPED, "req-bind-1");
        bound.setExecutionId(executionId);
        attempts.save(bound);

        TaskAttempt rival = attempt(taskB, 1, TaskAttempt.State.STOPPED, "req-bind-2");
        rival.setExecutionId(executionId);
        assertThatThrownBy(() -> attempts.saveAndFlush(rival))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateTaskOrdinalIsRejectedAtTheDatabaseBoundary() {
        UUID taskId = tasks.save(task("10008", "MODBAR-8", INSTANCE)).getId();
        attempts.save(attempt(taskId, 1, TaskAttempt.State.WITHDRAWN, "req-ordinal-1"));

        assertThatThrownBy(() -> attempts.saveAndFlush(
                attempt(taskId, 1, TaskAttempt.State.WITHDRAWN, "req-ordinal-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistedPolicyGrantsOnlyTheBoundExecutionAndClaimsOnePaidStart() {
        UUID legacyExecution = UUID.randomUUID();
        assertThat(taskPolicy.find(legacyExecution)).isEmpty();
        assertThat(taskPolicy.claimPaidStart(legacyExecution)).isFalse();
        taskPolicy.recordStop(legacyExecution, "implement", "ignored for legacy executions");

        UUID executionId = UUID.randomUUID();
        Task task = tasks.save(task("10011", "MODBAR-11", INSTANCE));
        TaskAttempt attempt = attempt(task.getId(), 1, TaskAttempt.State.QUEUED, "request-port");
        attempt.setInputFingerprint(task.getSourceRevision());
        attempt.setDestinationFingerprint("destination-hash");
        attempt.setApprovedSnapshot(task.getIssueSnapshot());
        attempt.setExecutionId(executionId);
        attempts.save(attempt);

        var context = taskPolicy.find(executionId).orElseThrow();
        assertThat(context.attemptId()).isEqualTo(attempt.getId());
        assertThat(context.repositoryKey()).isEqualTo("mod-bar");
        assertThat(context.approvedSnapshot()).containsEntry("key", "MODBAR-11");

        assertThat(taskPolicy.claimPaidStart(executionId)).isTrue();
        assertThat(taskPolicy.claimPaidStart(executionId)).isFalse();
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getPaidStartAt()).isNotNull();

        taskPolicy.recordStop(executionId, "read-task", "ambiguous repository mapping");
        taskPolicy.recordStop(executionId, "implement", "a later stop must not win");

        assertThat(attempts.findById(attempt.getId()).orElseThrow().getResultReason())
                .startsWith("read-task: ").contains("ambiguous");
        assertThat(taskPolicy.find(legacyExecution)).isEmpty();
    }

    private UUID reserve(UUID taskId, int ordinal, String requestId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            tasks.lockById(taskId).orElseThrow();
            return attempts.save(attempt(taskId, ordinal, TaskAttempt.State.RESERVED, requestId)).getId();
        });
    }

    private Task task(String issueId, String displayKey, String instance) {
        return TaskFixture.taskOn(instance, issueId, displayKey);
    }

    private TaskAttempt attempt(UUID taskId, int ordinal, TaskAttempt.State state, String requestId) {
        return TaskFixture.attempt(taskId, ordinal, state, requestId);
    }
}
