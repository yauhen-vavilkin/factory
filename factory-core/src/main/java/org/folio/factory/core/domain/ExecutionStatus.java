package org.folio.factory.core.domain;

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
}
