package org.folio.factory.app.web.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DashboardStats(Instant generatedAt, int days,
        List<StatusCount> executionsByStatus, List<FlowStatusCount> executionsByFlowAndStatus,
        List<DailyCount> executionsPerDay, List<StepFailureCount> stepFailures, HitlStats hitl,
        List<ConnectorOutcome> connectorOutcomes) {

    public record StatusCount(String status, long count) {
    }

    public record FlowStatusCount(String flowId, String status, long count) {
    }

    public record DailyCount(LocalDate day, String status, long count) {
    }

    public record StepFailureCount(String flowId, String stepId, long failures, long retriesScheduled) {
    }

    public record HitlStats(long pending, Double oldestPendingAgeSeconds, long decidedInWindow,
                            Double avgDecisionSeconds) {
    }

    public record ConnectorOutcome(String connector, String eventType, long count) {
    }
}
