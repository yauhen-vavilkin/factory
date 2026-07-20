package org.folio.factory.app.web.dashboard;

import org.folio.factory.app.web.dashboard.DashboardStats.ConnectorOutcome;
import org.folio.factory.app.web.dashboard.DashboardStats.DailyCount;
import org.folio.factory.app.web.dashboard.DashboardStats.FlowStatusCount;
import org.folio.factory.app.web.dashboard.DashboardStats.HitlStats;
import org.folio.factory.app.web.dashboard.DashboardStats.StatusCount;
import org.folio.factory.app.web.dashboard.DashboardStats.StepFailureCount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Aggregates execution/audit/HITL metrics for the dashboard charts in one payload.
 * Uses native Postgres SQL through {@link JdbcClient}: {@code date_trunc},
 * {@code FILTER (WHERE ...)} and {@code detail->>'connector'} are inexpressible in
 * JPQL, and the project is Postgres-only.
 */
@Service
public class DashboardStatsService {

    private final JdbcClient jdbc;

    public DashboardStatsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardStats compute(int days) {
        OffsetDateTime since = Instant.now().minus(days, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC);

        PendingRow pending = pending();
        DecidedRow decided = decided(since);
        HitlStats hitl = new HitlStats(pending.count(), pending.oldestSeconds(),
                decided.count(), decided.avgSeconds());

        return new DashboardStats(Instant.now(), days,
                executionsByStatus(), executionsByFlowAndStatus(), executionsPerDay(since),
                stepFailures(since), hitl, connectorOutcomes(since));
    }

    private List<StatusCount> executionsByStatus() {
        return jdbc.sql("SELECT status, count(*) AS c FROM pipeline_execution GROUP BY status")
                .query((rs, n) -> new StatusCount(rs.getString("status"), rs.getLong("c")))
                .list();
    }

    private List<FlowStatusCount> executionsByFlowAndStatus() {
        return jdbc.sql("""
                        SELECT flow_id, status, count(*) AS c
                        FROM pipeline_execution GROUP BY flow_id, status ORDER BY flow_id
                        """)
                .query((rs, n) -> new FlowStatusCount(rs.getString("flow_id"), rs.getString("status"), rs.getLong("c")))
                .list();
    }

    private List<DailyCount> executionsPerDay(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT (date_trunc('day', created_at AT TIME ZONE 'UTC'))::date AS day, status, count(*) AS c
                        FROM pipeline_execution WHERE created_at >= :since GROUP BY 1, 2 ORDER BY 1
                        """)
                .param("since", since)
                .query((rs, n) -> new DailyCount(rs.getObject("day", LocalDate.class),
                        rs.getString("status"), rs.getLong("c")))
                .list();
    }

    private List<StepFailureCount> stepFailures(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT coalesce(e.flow_id, '(purged)') AS flow_id, a.step_id,
                               count(*) FILTER (WHERE a.event_type = 'STEP_FAILED') AS failures,
                               count(*) FILTER (WHERE a.event_type = 'RETRY_SCHEDULED') AS retries
                        FROM audit_event a LEFT JOIN pipeline_execution e ON e.id = a.execution_id
                        WHERE a.event_type IN ('STEP_FAILED', 'RETRY_SCHEDULED') AND a.occurred_at >= :since
                          AND a.step_id IS NOT NULL
                        GROUP BY 1, 2 ORDER BY failures DESC
                        """)
                .param("since", since)
                .query((rs, n) -> new StepFailureCount(rs.getString("flow_id"), rs.getString("step_id"),
                        rs.getLong("failures"), rs.getLong("retries")))
                .list();
    }

    private List<ConnectorOutcome> connectorOutcomes(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT detail->>'connector' AS connector, event_type, count(*) AS c
                        FROM audit_event
                        WHERE event_type IN ('CONNECTOR_ACTION', 'CONNECTOR_SKIPPED') AND occurred_at >= :since
                        GROUP BY 1, 2
                        """)
                .param("since", since)
                .query((rs, n) -> new ConnectorOutcome(rs.getString("connector"),
                        rs.getString("event_type"), rs.getLong("c")))
                .list();
    }

    private PendingRow pending() {
        return jdbc.sql("""
                        SELECT count(*) AS c,
                               extract(epoch FROM (now() - min(created_at)))::double precision AS oldest
                        FROM hitl_review WHERE status = 'PENDING'
                        """)
                .query((rs, n) -> new PendingRow(rs.getLong("c"), (Double) rs.getObject("oldest")))
                .single();
    }

    private DecidedRow decided(OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT count(*) AS c,
                               avg(extract(epoch FROM decided_at - created_at))::double precision AS avg_s
                        FROM hitl_review WHERE decided_at IS NOT NULL AND decided_at >= :since
                        """)
                .param("since", since)
                .query((rs, n) -> new DecidedRow(rs.getLong("c"), (Double) rs.getObject("avg_s")))
                .single();
    }

    private record PendingRow(long count, Double oldestSeconds) {
    }

    private record DecidedRow(long count, Double avgSeconds) {
    }
}
