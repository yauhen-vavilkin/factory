-- M1 sprint pilot: discovered team tasks, admission/attempt identity and the
-- single installation record. Pilot rows are separate from engine history and
-- are never referenced by destructive retention: attempt execution bindings are
-- loose uuid references, so purging pipeline_execution cannot cascade here.

CREATE TABLE pilot_task (
    id              uuid         PRIMARY KEY,
    jira_instance   varchar(255) NOT NULL,
    issue_id        varchar(64)  NOT NULL,
    display_key     varchar(64)  NOT NULL,
    title           varchar(500) NOT NULL,
    project_key     varchar(64)  NOT NULL,
    issue_snapshot  jsonb        NOT NULL,
    source_revision varchar(64)  NOT NULL,
    eligibility     varchar(20)  NOT NULL,
    routing_reason  varchar(500) NOT NULL,
    in_scope        boolean      NOT NULL DEFAULT true,
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_pilot_task_issue UNIQUE (jira_instance, issue_id)
);

CREATE INDEX idx_pilot_task_scope ON pilot_task (in_scope, eligibility);

CREATE TABLE pilot_attempt (
    id                      uuid         PRIMARY KEY,
    task_id                 uuid         NOT NULL REFERENCES pilot_task (id),
    ordinal                 int          NOT NULL,
    kind                    varchar(16)  NOT NULL,
    request_id              varchar(100) NOT NULL,
    state                   varchar(30)  NOT NULL,
    input_fingerprint       varchar(64),
    scope_fingerprint       varchar(64),
    destination_fingerprint varchar(64),
    approved_snapshot       jsonb,
    execution_id            uuid,
    started_at              timestamptz,
    deadline_at             timestamptz,
    paid_start_at           timestamptz,
    result_reason           varchar(500),
    publication_identity    varchar(500),
    version                 bigint       NOT NULL DEFAULT 0,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_pilot_attempt_request UNIQUE (request_id),
    CONSTRAINT uq_pilot_attempt_execution UNIQUE (execution_id),
    CONSTRAINT uq_pilot_attempt_ordinal UNIQUE (task_id, ordinal)
);

-- At most one live admission per task. Keep the state literals in sync with the
-- live set in PilotAttempt.State: RESERVED, QUEUED, RUNNING, NEEDS_INSPECTION.
-- WITHDRAWN rows are excluded so a withdrawn unstarted admission can be
-- readmitted; terminal outcomes release the task's slot.
CREATE UNIQUE INDEX uq_pilot_attempt_live_per_task
    ON pilot_attempt (task_id)
    WHERE state IN ('RESERVED', 'QUEUED', 'RUNNING', 'NEEDS_INSPECTION');

-- Single operational row; the application keeps exactly one row with id = 1.
CREATE TABLE pilot_installation (
    id                             int         PRIMARY KEY,
    mode                           varchar(20) NOT NULL,
    accepted_authority_fingerprint varchar(64),
    sync_complete                  boolean     NOT NULL DEFAULT false,
    last_successful_sync_at        timestamptz,
    sync_problem                   text,
    active_attempt_id              uuid,
    runtime_readiness_ref          varchar(255),
    version                        bigint      NOT NULL DEFAULT 0,
    created_at                     timestamptz NOT NULL DEFAULT now(),
    updated_at                     timestamptz NOT NULL DEFAULT now()
);
