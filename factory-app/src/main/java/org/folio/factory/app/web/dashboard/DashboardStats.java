package org.folio.factory.app.web.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DashboardStats(Instant generatedAt, int days,
        List<StatusCount> executionsByStatus, List<FlowStatusCount> executionsByFlowAndStatus,
        List<DailyCount> executionsPerDay, List<StepFailureCount> stepFailures, HitlStats hitl,
        List<ConnectorOutcome> connectorOutcomes, TokenUsage tokens, List<StepTokenCount> stepTokens) {

    /** Runs in one {@code RunState}: its name, the words the Executions list shows for it, and the count. */
    public record StatusCount(String state, String label, long count) {
    }

    /** Runs of one flow in one state; {@code flowName} is the name the Executions list shows for the flow. */
    public record FlowStatusCount(String flowId, String flowName, String state, long count) {
    }

    public record DailyCount(LocalDate day, String state, String label, long count) {
    }

    /** Failures and retries of one step; {@code label} names the flow and the step in words for the chart. */
    public record StepFailureCount(String flowId, String stepId, String label, long failures, long retriesScheduled) {
    }

    public record HitlStats(long pending, Double oldestPendingAgeSeconds, long decidedInWindow,
                            Double avgDecisionSeconds) {
    }

    public record ConnectorOutcome(String connector, String eventType, long count) {
    }

    /** LLM tokens consumed by agent steps that completed inside the window. */
    public record TokenUsage(long promptTokens, long completionTokens) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    public record StepTokenCount(String flowId, String stepId, long promptTokens, long completionTokens) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
