-- T22 idempotent inbox admission — purely additive: nullable admission key
-- plus a unique (flow_id, admission_key) index backing keyed find-or-create
-- admission. NULLs are distinct in PostgreSQL, so manual/legacy/child rows
-- (no admission key) are unaffected. No existing row is rewritten.

ALTER TABLE pipeline_execution ADD COLUMN admission_key varchar(100);

CREATE UNIQUE INDEX uq_execution_flow_admission
    ON pipeline_execution (flow_id, admission_key);
