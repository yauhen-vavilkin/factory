-- S11 startup readiness: append-only evidence for the explicit pilot startup
-- action. Rows record what a run tested and observed; the runtime fingerprint
-- carries identity hashes only (image, model, endpoint origin, authentication
-- kind, credential hash, smoke bounds, instructions), never credential values.
-- Nothing here is referenced by destructive retention, and no row is updated
-- after insert, so a failed check stays visible until a later run supersedes it.

CREATE TABLE pilot_readiness_evidence (
    id                  uuid         PRIMARY KEY,
    runtime_fingerprint varchar(64)  NOT NULL,
    fingerprint         jsonb        NOT NULL,
    passed              boolean      NOT NULL,
    tool_use_success    boolean      NOT NULL,
    usage_complete      boolean      NOT NULL,
    rate_known          boolean      NOT NULL,
    failure_reason      varchar(500),
    execution_ready     boolean      NOT NULL,
    blockers            jsonb        NOT NULL,
    checked_at          timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_pilot_readiness_recent ON pilot_readiness_evidence (checked_at DESC);
