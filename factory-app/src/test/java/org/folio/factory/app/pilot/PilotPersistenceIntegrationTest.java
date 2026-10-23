package org.folio.factory.app.pilot;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.source.PilotDiscoveryPoller;
import org.folio.factory.app.pilot.source.PilotDiscoveryService;
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
 * Pilot persistence against real PostgreSQL: task identity survives renamed keys,
 * and the database boundary alone prevents duplicate live admissions, duplicate
 * request ids, duplicate execution bindings and half-applied attempt bindings.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotPersistenceIntegrationTest {

    private static final String INSTANCE = "jira.example.org";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PilotTaskRepository tasks;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ObjectProvider<PilotDiscoveryService> discoveryService;

    @Autowired
    ObjectProvider<PilotDiscoveryPoller> discoveryPoller;

    @Test
    void renamedKeyWithSameIssueIdUpdatesOneTask() {
        String instance = "rename.example.org";
        Instant before = Instant.now();
        PilotTask saved = tasks.save(task("10001", "MODBAR-1", instance));

        PilotTask stored = tasks.findByJiraInstanceAndIssueId(instance, "10001").orElseThrow();
        stored.setDisplayKey("MODBAR-100");
        stored.setTitle("Renamed summary");
        tasks.save(stored);

        assertThat(tasks.findByJiraInstanceAndIssueId(instance, "10001")).isPresent();
        assertThat(tasks.countByJiraInstance(instance)).isEqualTo(1);
        PilotTask renamed = tasks.findByJiraInstanceAndIssueId(instance, "10001").orElseThrow();
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

        List<PilotAttempt> all = attempts.findByTaskIdOrderByOrdinalAsc(taskId);
        assertThat(all).hasSize(1);
        assertThat(attempts.findLiveByTaskId(taskId)).hasSize(1);
    }

    @Test
    void withdrawnUnstartedAdmissionIsRetainedAndCanBeReadmitted() {
        UUID taskId = tasks.save(task("10003", "MODBAR-3", INSTANCE)).getId();
        PilotAttempt first = attempts.save(attempt(taskId, 1, PilotAttempt.State.RESERVED, "req-withdraw-1"));

        first.setState(PilotAttempt.State.WITHDRAWN);
        attempts.save(first);

        attempts.save(attempt(taskId, 2, PilotAttempt.State.RESERVED, "req-withdraw-2"));

        List<PilotAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(taskId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRequestId()).isEqualTo("req-withdraw-1");
        assertThat(history.get(0).getState()).isEqualTo(PilotAttempt.State.WITHDRAWN);
        assertThat(attempts.findLiveByTaskId(taskId))
                .singleElement()
                .satisfies(live -> assertThat(live.getRequestId()).isEqualTo("req-withdraw-2"));
    }

    @Test
    void duplicateRequestIdIsRejectedAtTheDatabaseBoundary() {
        UUID taskA = tasks.save(task("10004", "MODBAR-4", INSTANCE)).getId();
        UUID taskB = tasks.save(task("10005", "MODBAR-5", INSTANCE)).getId();
        attempts.save(attempt(taskA, 1, PilotAttempt.State.WITHDRAWN, "req-duplicate"));

        assertThatThrownBy(() -> attempts.saveAndFlush(
                attempt(taskB, 1, PilotAttempt.State.WITHDRAWN, "req-duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateExecutionBindingIsRejectedAtTheDatabaseBoundary() {
        UUID taskA = tasks.save(task("10006", "MODBAR-6", INSTANCE)).getId();
        UUID taskB = tasks.save(task("10007", "MODBAR-7", INSTANCE)).getId();
        UUID executionId = UUID.randomUUID();
        PilotAttempt bound = attempt(taskA, 1, PilotAttempt.State.STOPPED, "req-bind-1");
        bound.setExecutionId(executionId);
        attempts.save(bound);

        PilotAttempt rival = attempt(taskB, 1, PilotAttempt.State.STOPPED, "req-bind-2");
        rival.setExecutionId(executionId);
        assertThatThrownBy(() -> attempts.saveAndFlush(rival))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateTaskOrdinalIsRejectedAtTheDatabaseBoundary() {
        UUID taskId = tasks.save(task("10008", "MODBAR-8", INSTANCE)).getId();
        attempts.save(attempt(taskId, 1, PilotAttempt.State.WITHDRAWN, "req-ordinal-1"));

        assertThatThrownBy(() -> attempts.saveAndFlush(
                attempt(taskId, 1, PilotAttempt.State.WITHDRAWN, "req-ordinal-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rollbackLeavesNoAttemptBindingOrActiveReservation() {
        UUID taskId = tasks.save(task("10009", "MODBAR-9", INSTANCE)).getId();
        UUID executionId = UUID.randomUUID();
        boolean hadInstallation = installations.findById(1).isPresent();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            tasks.lockById(taskId).orElseThrow();
            PilotAttempt attempt = attempt(taskId, 1, PilotAttempt.State.QUEUED, "req-rollback");
            attempt.setExecutionId(executionId);
            attempts.save(attempt);
            PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
            installation.setActiveAttemptId(attempt.getId());
            installations.save(installation);
            throw new IllegalStateException("simulated crash after binding");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId)).isEmpty();
        assertThat(attempts.findByExecutionId(executionId)).isEmpty();
        PilotInstallation installation = installations.findById(1).orElse(null);
        if (hadInstallation) {
            assertThat(installation.getActiveAttemptId()).isNull();
        } else {
            assertThat(installation).isNull();
        }
    }

    @Test
    void installationStartsInReviewBeforeRunAndBackgroundDiscoveryIsDisabledByDefault() {
        PilotInstallation saved = installations.save(PilotInstallation.initial());

        assertThat(saved.getId()).isEqualTo(1);
        assertThat(saved.getMode()).isEqualTo(PilotInstallation.Mode.REVIEW_BEFORE_RUN);
        assertThat(saved.isSyncComplete()).isFalse();
        assertThat(saved.getLastSuccessfulSyncAt()).isNull();
        assertThat(saved.getActiveAttemptId()).isNull();
        assertThat(discoveryPoller.getIfAvailable()).isNull();
        assertThat(discoveryService.getIfAvailable()).isNull();
    }

    private UUID reserve(UUID taskId, int ordinal, String requestId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            tasks.lockById(taskId).orElseThrow();
            return attempts.save(attempt(taskId, ordinal, PilotAttempt.State.RESERVED, requestId)).getId();
        });
    }

    private PilotTask task(String issueId, String displayKey, String instance) {
        return new PilotTask(instance, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
    }

    private PilotAttempt attempt(UUID taskId, int ordinal, PilotAttempt.State state, String requestId) {
        return new PilotAttempt(taskId, ordinal, PilotAttempt.Kind.INITIAL, requestId, state);
    }
}
