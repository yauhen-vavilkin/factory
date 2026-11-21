package org.folio.factory.app.tasks;

import org.flywaydb.core.Flyway;
import org.folio.factory.app.support.SharedDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V12 on a database left by the removed sprint mode: work queued by the removed automatic
 * mode must not start without a click, and team sprint tasks must not pose as the
 * developer's own tasks until a scan finds them assigned. V17 marks tasks that ran by key
 * before a key added a task to My tasks. Each test migrates its own schema of the shared
 * database and drops it afterwards.
 */
class MyTasksMigrationTest {

    private static final PostgreSQLContainer<?> postgres = SharedDatabase.container();

    @AfterAll
    static void dropSchemas() {
        jdbc("public").execute("DROP SCHEMA IF EXISTS legacy, fresh, bykey CASCADE");
    }

    @Test
    void legacyAutomaticQueueIsWithdrawnAndTeamTasksLeaveMyTasks() {
        migrate("legacy", "11");
        JdbcTemplate jdbc = jdbc("legacy");
        // The schema is at V11 here, before V16 renamed the pilot_* tables.
        UUID task = UUID.randomUUID();
        UUID stopped = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        jdbc.update("INSERT INTO pilot_installation (id, mode, sync_complete, last_successful_sync_at, sync_problem)"
                + " VALUES (1, 'AUTOMATIC', true, now(), 'old problem')");
        jdbc.update("INSERT INTO pilot_task (id, jira_instance, issue_id, display_key, title, project_key,"
                + " issue_snapshot, source_revision, eligibility, routing_reason) VALUES (?, 'jira.example.org',"
                + " '10001', 'MODBAR-1', 'Team task', 'MODBAR', '{}'::jsonb, 'r1', 'ELIGIBLE', 'repository mod-bar')",
                task);
        jdbc.update("INSERT INTO pilot_attempt (id, task_id, ordinal, kind, request_id, state, admitted_via)"
                + " VALUES (?, ?, 1, 'INITIAL', 'legacy-1', 'STOPPED', 'AUTOMATIC')", stopped, task);
        jdbc.update("INSERT INTO pilot_attempt (id, task_id, ordinal, kind, request_id, state, admitted_via)"
                + " VALUES (?, ?, 2, 'RETRY', 'legacy-2', 'RESERVED', 'AUTOMATIC')", queued, task);

        migrate("legacy", null);

        assertThat(jdbc.queryForMap("SELECT state, admitted_via, result_reason FROM task_attempt WHERE id = ?", queued))
                .containsEntry("state", "WITHDRAWN")
                .containsEntry("admitted_via", "MANUAL")
                .containsEntry("result_reason",
                        "Withdrawn before start: it was queued by the removed automatic mode");
        assertThat(jdbc.queryForMap("SELECT state, admitted_via FROM task_attempt WHERE id = ?", stopped))
                .containsEntry("state", "STOPPED")
                .containsEntry("admitted_via", "MANUAL");
        assertThat(jdbc.queryForObject("SELECT in_scope FROM task WHERE id = ?", Boolean.class, task)).isFalse();
        assertThat(jdbc.queryForMap("SELECT sync_complete, last_successful_sync_at, sync_problem"
                + " FROM task_installation WHERE id = 1"))
                .containsEntry("sync_complete", false)
                .containsEntry("last_successful_sync_at", null)
                .containsEntry("sync_problem", null);
    }

    @Test
    void tasksThatRanByKeyKeepTheirMarkAndOtherTasksGetNone() {
        migrate("bykey", "16");
        JdbcTemplate jdbc = jdbc("bykey");
        UUID byKey = task(jdbc, "10001", "MODBAR-1", false);
        UUID mine = task(jdbc, "10002", "MODBAR-2", true);
        UUID retried = task(jdbc, "10003", "MODBAR-3", true);
        jdbc.update("INSERT INTO task_attempt (id, task_id, ordinal, kind, request_id, state, admitted_via)"
                + " VALUES (?, ?, 1, 'INITIAL', 'key-1', 'STOPPED', 'BY_KEY')", UUID.randomUUID(), byKey);
        jdbc.update("INSERT INTO task_attempt (id, task_id, ordinal, kind, request_id, state, admitted_via)"
                + " VALUES (?, ?, 1, 'INITIAL', 'run-2', 'STOPPED', 'MANUAL')", UUID.randomUUID(), mine);
        // Assigned to the developer later: a scan put it in My tasks, so it is not marked.
        jdbc.update("INSERT INTO task_attempt (id, task_id, ordinal, kind, request_id, state, admitted_via)"
                + " VALUES (?, ?, 1, 'INITIAL', 'key-3', 'STOPPED', 'BY_KEY')", UUID.randomUUID(), retried);

        migrate("bykey", null);

        assertThat(jdbc.queryForObject("SELECT added_by_key FROM task WHERE id = ?", Boolean.class, byKey)).isTrue();
        assertThat(jdbc.queryForObject("SELECT added_by_key FROM task WHERE id = ?", Boolean.class, mine)).isFalse();
        assertThat(jdbc.queryForObject("SELECT added_by_key FROM task WHERE id = ?", Boolean.class, retried))
                .isFalse();
    }

    private static UUID task(JdbcTemplate jdbc, String issueId, String key, boolean inScope) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task (id, jira_instance, issue_id, display_key, title, project_key, issue_snapshot,"
                + " source_revision, eligibility, routing_reason, in_scope) VALUES (?, 'jira.example.org', ?, ?,"
                + " 'Task', 'MODBAR', '{}'::jsonb, 'r1', 'ELIGIBLE', 'repository mod-bar', ?)", id, issueId, key,
                inScope);
        return id;
    }

    @Test
    void freshDatabaseGetsTheSingleInstallationRow() {
        migrate("fresh", null);

        assertThat(jdbc("fresh").queryForObject("SELECT count(*) FROM task_installation WHERE id = 1",
                Integer.class)).isEqualTo(1);
    }

    private static void migrate(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl() + "&currentSchema=" + schema, postgres.getUsername(), postgres.getPassword()));
    }
}
