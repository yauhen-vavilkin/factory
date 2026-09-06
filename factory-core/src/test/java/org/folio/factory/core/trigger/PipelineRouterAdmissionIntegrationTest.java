package org.folio.factory.core.trigger;

import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T22 idempotent admission (R1–R3) against real PostgreSQL: one admitted
 * event revision yields exactly one execution per matching flow, replay
 * returns the existing execution with zero new rows, a database unique
 * constraint backs the promise under racing admissions, and manual/child
 * executions stay admission-free.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@Testcontainers
class PipelineRouterAdmissionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PipelineRouter router;

    @Autowired
    StateManager stateManager;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void replayedAdmissionReturnsExistingExecutionWithZeroNewRows() {
        String admissionKey = key();
        var payload = readyForQaPayload("ERM-77");

        List<UUID> first = router.routeAdmitted(
                TriggerEvent.of("jira.issue.transitioned", "test", payload), admissionKey);
        long rowsAfterFirst = executions.count();

        List<UUID> replay = router.routeAdmitted(
                TriggerEvent.of("jira.issue.transitioned", "test-again", payload), admissionKey);

        assertThat(first).hasSize(1);
        assertThat(replay).isEqualTo(first);
        assertThat(executions.count()).as("replay must not add execution rows").isEqualTo(rowsAfterFirst);
        PipelineExecution admitted = stateManager.findAdmitted("fake-webhook", admissionKey).orElseThrow();
        assertThat(admitted.getId()).isEqualTo(first.getFirst());
        assertThat(admitted.getAdmissionKey()).isEqualTo(admissionKey);
    }

    @Test
    void differentAdmissionKeyIsANewExecution() {
        var payload = readyForQaPayload("ERM-78");

        UUID first = router.routeAdmitted(
                TriggerEvent.of("jira.issue.transitioned", "test", payload), key()).getFirst();
        UUID second = router.routeAdmitted(
                TriggerEvent.of("jira.issue.transitioned", "test", payload), key()).getFirst();

        assertThat(second).isNotEqualTo(first);
        assertThat(stateManager.get(first).getAdmissionKey())
                .isNotEqualTo(stateManager.get(second).getAdmissionKey());
    }

    @Test
    void racingAdmissionsOfTheSameRevisionYieldExactlyOneExecution() throws Exception {
        String admissionKey = key();
        var payload = readyForQaPayload("ERM-79");
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<UUID>>> results = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(admitAfterBarrier(barrier, i, payload, admissionKey)))
                    .toList();

            Set<UUID> admitted = new HashSet<>();
            for (Future<List<UUID>> result : results) {
                List<UUID> ids = result.get(60, TimeUnit.SECONDS);
                assertThat(ids).as("every racer must see the one admitted execution").hasSize(1);
                admitted.addAll(ids);
            }
            assertThat(admitted).as("one admitted revision = one execution id").hasSize(1);

            List<PipelineExecution> rows = executions.findAll().stream()
                    .filter(execution -> admissionKey.equals(execution.getAdmissionKey()))
                    .toList();
            assertThat(rows).as("exactly one row carries the admission key").hasSize(1);
            assertThat(rows.getFirst().getId()).isEqualTo(admitted.iterator().next());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void admissionUniqueConstraintRejectsDuplicateRow() {
        String admissionKey = key();

        PipelineExecution admitted = stateManager.createAdmittedExecution(
                "fake-webhook", "1.0.0", "{}", admissionKey);
        assertThat(admitted.getAdmissionKey()).isEqualTo(admissionKey);

        assertThatThrownBy(() -> stateManager.createAdmittedExecution(
                "fake-webhook", "1.0.0", "{}", admissionKey))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Deterministic race shape: the loser's read sees nothing (the winner's
     * transaction is still open), its insert blocks on the unique index, the
     * winner commits, and the loser must return the winner's execution rather
     * than failing or creating a second row.
     */
    @Test
    void admissionLoserBlockedByOpenWinnerTransactionReturnsWinnersExecution() throws Exception {
        String admissionKey = key();
        var payload = readyForQaPayload("ERM-81");
        ExecutorService holder = Executors.newSingleThreadExecutor();
        ExecutorService loser = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch inserted = new CountDownLatch(1);
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Future<PipelineExecution> winner = holder.submit(() -> tx.execute(status -> {
                PipelineExecution row = new PipelineExecution("fake-webhook", "1.0.0", "{}");
                row.setAdmissionKey(admissionKey);
                executions.saveAndFlush(row);
                inserted.countDown();
                // Hold the transaction open long enough for the loser's read
                // (sees nothing, READ_COMMITTED) and insert (blocks on the
                // unique index) to happen before this commit.
                sleepQuietly(2500);
                return row;
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<List<UUID>> admitted = loser.submit(() -> router.routeAdmitted(
                    TriggerEvent.of("jira.issue.transitioned", "test", payload), admissionKey));

            List<UUID> ids = admitted.get(30, TimeUnit.SECONDS);
            assertThat(ids).as("loser must return the winner's execution").containsExactly(winner.get().getId());
            List<PipelineExecution> rows = executions.findAll().stream()
                    .filter(execution -> admissionKey.equals(execution.getAdmissionKey()))
                    .toList();
            assertThat(rows).hasSize(1);
        } finally {
            holder.shutdownNow();
            loser.shutdownNow();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void manualRouteStaysAdmissionFree() {
        UUID first = router.routeManual("fake-webhook", json.readTree("{\"issueKey\": \"ERM-1\"}"));
        UUID second = router.routeManual("fake-webhook", json.readTree("{\"issueKey\": \"ERM-2\"}"));

        assertThat(first).isNotEqualTo(second);
        assertThat(stateManager.get(first).getAdmissionKey()).isNull();
        assertThat(stateManager.get(second).getAdmissionKey()).isNull();
    }

    @Test
    void blankAdmissionKeyIsRejected() {
        assertThatThrownBy(() -> router.routeAdmitted(
                TriggerEvent.of("jira.issue.transitioned", "test", readyForQaPayload("ERM-80")), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admissionKey");
    }

    private Callable<List<UUID>> admitAfterBarrier(CyclicBarrier barrier, int racer,
            tools.jackson.databind.JsonNode payload, String admissionKey) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return router.routeAdmitted(
                    TriggerEvent.of("jira.issue.transitioned", "poller-" + racer, payload), admissionKey);
        };
    }

    private tools.jackson.databind.JsonNode readyForQaPayload(String issueKey) {
        return json.readTree("""
                {"issueKey": "%s",
                 "issue": {"fields": {"status": {"name": "Ready for QA"}}}}
                """.formatted(issueKey));
    }

    private static String key() {
        return "file.inbox:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
