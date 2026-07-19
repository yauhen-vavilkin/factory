-- Cost controls: dedup key + supporting indexes for concurrency cap and daily budget.

ALTER TABLE pipeline_execution ADD COLUMN dedup_key varchar(255);

-- At most one ACTIVE (non-terminal) execution per (flow_id, dedup_key). Terminal
-- rows are excluded so a re-fire after an execution finishes is governed by the
-- time-window check in the router rather than blocked forever. NULL dedup_key rows
-- (dedup disabled, manual opt-out, sub-flow children) are exempt from the constraint.
-- Keep the status literals in sync with ExecutionStatus.isTerminal().
CREATE UNIQUE INDEX uq_execution_active_dedup
    ON pipeline_execution (flow_id, dedup_key)
    WHERE dedup_key IS NOT NULL
      AND status NOT IN ('COMPLETED', 'FAILED_ESCALATED', 'REJECTED', 'CANCELLED');

-- Window lookup for the router's dedup check (findDuplicates).
CREATE INDEX idx_execution_dedup_lookup
    ON pipeline_execution (flow_id, dedup_key, created_at);

-- Daily budget counts rows created since start-of-day.
CREATE INDEX idx_execution_created_at
    ON pipeline_execution (created_at);

-- (Concurrency cap counts status = 'RUNNING' and reuses the existing
--  idx_execution_claimable (status, next_run_at) index; no new index needed.)
