package org.folio.factory.app.pilot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V12 on a database left by the sprint pilot: work queued by the removed automatic
 * mode must not start without a click, and team sprint tasks must not pose as the
 * developer's own tasks until a scan finds them assigned. Each test migrates its
 * own schema.
 */
@Testcontainers
class PilotMyTasksMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void legacyAutomaticQueueIsWithdrawnAndTeamTasksLeaveMyTasks() {
        migrate("legacy", "11");
        JdbcTemplate jdbc = jdbc("legacy");
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

        assertThat(jdbc.queryForMap("SELECT state, admitted_via, result_reason FROM pilot_attempt WHERE id = ?", queued))
                .containsEntry("state", "WITHDRAWN")
                .containsEntry("admitted_via", "MANUAL")
                .containsEntry("result_reason",
                        "Withdrawn before start: it was queued by the removed automatic mode");
        assertThat(jdbc.queryForMap("SELECT state, admitted_via FROM pilot_attempt WHERE id = ?", stopped))
                .containsEntry("state", "STOPPED")
                .containsEntry("admitted_via", "MANUAL");
        assertThat(jdbc.queryForObject("SELECT in_scope FROM pilot_task WHERE id = ?", Boolean.class, task)).isFalse();
        assertThat(jdbc.queryForMap("SELECT sync_complete, last_successful_sync_at, sync_problem"
                + " FROM pilot_installation WHERE id = 1"))
                .containsEntry("sync_complete", false)
                .containsEntry("last_successful_sync_at", null)
                .containsEntry("sync_problem", null);
    }

    @Test
    void freshDatabaseGetsTheSingleInstallationRow() {
        migrate("fresh", null);

        assertThat(jdbc("fresh").queryForObject("SELECT count(*) FROM pilot_installation WHERE id = 1",
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
