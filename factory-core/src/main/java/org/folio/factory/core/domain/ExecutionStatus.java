package org.folio.factory.core.domain;

import java.util.Arrays;
import java.util.List;

public enum ExecutionStatus {
    PENDING,
    RUNNING,
    AWAITING_HITL,
    AWAITING_SUBFLOW,
    COMPLETED,
    FAILED_ESCALATED,
    REJECTED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED_ESCALATED || this == REJECTED || this == CANCELLED;
    }

    /**
     * A resolved end state, excluding {@link #FAILED_ESCALATED} — which is terminal
     * for the step cursor but resumable via its escalation review, so an execution
     * can legitimately leave it again. Used for once-per-execution outcome metrics so
     * a resumed escalation is not double-counted.
     */
    public boolean isFinal() {
        return this == COMPLETED || this == REJECTED || this == CANCELLED;
    }

    /** Single source of truth for the terminal statuses; reused by the dedup query. */
    public static List<ExecutionStatus> terminalStatuses() {
        return Arrays.stream(values()).filter(ExecutionStatus::isTerminal).toList();
    }
}
