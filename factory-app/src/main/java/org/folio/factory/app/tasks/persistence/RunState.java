package org.folio.factory.app.tasks.persistence;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.folio.factory.devfactory.DevFactoryConfiguration;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;

/**
 * What a run came to, in the words of the Executions list and the Dashboard. A run of a task attempt takes it from
 * the attempt, as the task page does: a run that ran to the end without a pull request is the failed attempt it
 * stands for, not a completed run. A Developer Flow run that no attempt started (one from before My tasks, or
 * started through the API) reads the same from its artifacts: a delivered pull request, a question, or else a
 * failure. Any other run takes it from the engine status.
 */
@Getter
@RequiredArgsConstructor
public enum RunState {

    IN_PROGRESS("In progress"),
    WAITING_FOR_REVIEW("Waiting for review"),
    PULL_REQUEST("Pull request opened"),
    COMPLETED("Completed"),
    ASKED("Asked a question"),
    NEEDS_INSPECTION("Needs inspection"),
    FAILED("Failed"),
    CANCELLED_OR_REJECTED("Cancelled or rejected");

    /** The runs and their attempts, at most one attempt per run (task_attempt.execution_id is unique). */
    public static final String FROM = "pipeline_execution e LEFT JOIN task_attempt a ON a.execution_id = e.id";

    /**
     * The state of the run {@code e} with its attempt {@code a}, as SQL over {@link #FROM}. An attempt asked a
     * question when the cause after its first ": " starts with the words of {@link TaskStops#asksForAnswer}.
     */
    public static final String SQL = """
            CASE
              WHEN a.state IN ('QUEUED', 'RUNNING') THEN 'IN_PROGRESS'
              WHEN a.state = 'PR_READY' THEN 'PULL_REQUEST'
              WHEN a.state = 'STOPPED' AND strpos(a.result_reason, ': ') > 1
                   AND substr(a.result_reason, strpos(a.result_reason, ': ') + 2) LIKE '"""
            + TaskStops.DECISION_REASON + """
            %' THEN 'ASKED'
              WHEN a.state = 'STOPPED' THEN 'FAILED'
              WHEN a.state = 'NEEDS_INSPECTION' THEN 'NEEDS_INSPECTION'
              WHEN a.id IS NULL AND e.flow_id = '"""
            + DevFactoryConfiguration.FLOW_ID + """
            ' AND e.status IN ('COMPLETED', 'AWAITING_HITL', 'CANCELLED')
                   AND EXISTS (SELECT 1 FROM artifact r WHERE r.execution_id = e.id
                               AND r.name IN ('"""
            + DeliveryWorker.DELIVERY + "', '" + VerifyWorker.RESULT + """
            ') AND r.content LIKE '%"state":"DELIVERED"%') THEN 'PULL_REQUEST'
              WHEN a.id IS NULL AND e.flow_id = '"""
            + DevFactoryConfiguration.FLOW_ID + """
            ' AND e.status = 'COMPLETED'
                   AND EXISTS (SELECT 1 FROM artifact r WHERE r.execution_id = e.id AND r.name = '"""
            + DevelopWorker.OUTCOME + """
            ' AND r.content LIKE '%"status":"NEEDS_DECISION"%') THEN 'ASKED'
              WHEN a.id IS NULL AND e.flow_id = '"""
            + DevFactoryConfiguration.FLOW_ID + """
            ' AND e.status = 'COMPLETED' THEN 'FAILED'
              WHEN e.status IN ('PENDING', 'RUNNING', 'AWAITING_SUBFLOW') THEN 'IN_PROGRESS'
              WHEN e.status = 'AWAITING_HITL' THEN 'WAITING_FOR_REVIEW'
              WHEN e.status = 'COMPLETED' THEN 'COMPLETED'
              WHEN e.status = 'FAILED_ESCALATED' THEN 'FAILED'
              ELSE 'CANCELLED_OR_REJECTED'
            END""";

    private final String label;
}
