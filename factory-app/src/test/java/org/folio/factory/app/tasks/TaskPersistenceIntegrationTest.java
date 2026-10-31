package org.folio.factory.app.tasks;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.tasks.source.TaskDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
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
 * Task persistence against real PostgreSQL: task identity survives renamed keys,
 * and the database boundary alone prevents duplicate live admissions, duplicate
 * request ids, duplicate execution bindings and half-applied attempt bindings.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class TaskPersistenceIntegrationTest {

    private static final String INSTANCE = "jira.example.org";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskAttemptRepository attempts;

    @Autowired
    TaskInstallationRepository installations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ObjectProvider<TaskDiscoveryService> discoveryService;

    @Test
    void renamedKeyWithSameIssueIdUpdatesOneTask() {
        String instance = "rename.example.org";
        Instant before = Instant.now();
        Task saved = tasks.save(task("10001", "MODBAR-1", instance));

        Task stored = tasks.findByJiraInstanceAndIssueId(instance, "10001").orElseThrow();
        stored.setDisplayKey("MODBAR-100");
        stored.setTitle("Renamed summary");
        tasks.save(stored);

        assertThat(tasks.findByJiraInstanceAndIssueId(instance, "10001")).isPresent();
        assertThat(tasks.countByJiraInstance(instance)).isEqualTo(1);
        Task renamed = tasks.findByJiraInstanceAndIssueId(instance, "10001").orElseThrow();
        assertThat(renamed.getId()).isEqualTo(saved.getId());
        assertThat(renamed.getDisplayKey()).isEqualTo("MODBAR-100");
        assertThat(renamed.getTitle()).isEqualTo("Renamed summary");
        assertThat(renamed.getIssueId()).isEqualTo("10001");
        assertThat(renamed.getCreatedAt()).isAfter(before);
        assertThat(renamed.getVersion()).isEqualTo(saved.getVersion() + 1);
    }

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
    void withdrawnUnstartedAdmissionIsRetainedAndCanBeReadmitted() {
        UUID taskId = tasks.save(task("10003", "MODBAR-3", INSTANCE)).getId();
        TaskAttempt first = attempts.save(attempt(taskId, 1, TaskAttempt.State.RESERVED, "req-withdraw-1"));

        first.setState(TaskAttempt.State.WITHDRAWN);
        attempts.save(first);

        attempts.save(attempt(taskId, 2, TaskAttempt.State.RESERVED, "req-withdraw-2"));

        List<TaskAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(taskId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRequestId()).isEqualTo("req-withdraw-1");
        assertThat(history.get(0).getState()).isEqualTo(TaskAttempt.State.WITHDRAWN);
        assertThat(attempts.findLiveByTaskId(taskId))
                .singleElement()
                .satisfies(live -> assertThat(live.getRequestId()).isEqualTo("req-withdraw-2"));
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
    void rollbackLeavesNoAttemptBindingOrActiveReservation() {
        UUID taskId = tasks.save(task("10009", "MODBAR-9", INSTANCE)).getId();
        UUID executionId = UUID.randomUUID();
        boolean hadInstallation = installations.findById(1).isPresent();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            tasks.lockById(taskId).orElseThrow();
            TaskAttempt attempt = attempt(taskId, 1, TaskAttempt.State.QUEUED, "req-rollback");
            attempt.setExecutionId(executionId);
            attempts.save(attempt);
            TaskInstallation installation = installations.findById(1).orElseThrow();
            installation.setActiveAttemptId(attempt.getId());
            installations.save(installation);
            throw new IllegalStateException("simulated crash after binding");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId)).isEmpty();
        assertThat(attempts.findByExecutionId(executionId)).isEmpty();
        TaskInstallation installation = installations.findById(1).orElse(null);
        if (hadInstallation) {
            assertThat(installation.getActiveAttemptId()).isNull();
        } else {
            assertThat(installation).isNull();
        }
    }

    @Test
    void migrationSeedsTheSingleInstallationAndScanIsAlwaysAvailable() {
        TaskInstallation initial = TaskInstallations.seeded();

        assertThat(installations.findById(1)).isPresent();
        assertThat(initial.getId()).isEqualTo(1);
        assertThat(initial.isSyncComplete()).isFalse();
        assertThat(initial.getLastSuccessfulSyncAt()).isNull();
        assertThat(initial.getActiveAttemptId()).isNull();
        assertThat(discoveryService.getIfAvailable()).isNotNull();
    }

    private UUID reserve(UUID taskId, int ordinal, String requestId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            tasks.lockById(taskId).orElseThrow();
            return attempts.save(attempt(taskId, ordinal, TaskAttempt.State.RESERVED, requestId)).getId();
        });
    }

    private Task task(String issueId, String displayKey, String instance) {
        return new Task(instance, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
    }

    private TaskAttempt attempt(UUID taskId, int ordinal, TaskAttempt.State state, String requestId) {
        return new TaskAttempt(taskId, ordinal, TaskAttempt.Kind.INITIAL, requestId, state);
    }
}
