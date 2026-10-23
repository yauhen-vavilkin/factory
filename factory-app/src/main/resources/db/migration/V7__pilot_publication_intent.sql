-- Durable publication intent: the exact identity persisted before the first
-- remote mutation of a pilot attempt, so a lost push or pull-request response
-- is reconciled against what was intended instead of guessed. One intent per
-- attempt and per execution; the first recorded intent stays authoritative and
-- is never rewritten.

CREATE TABLE pilot_publication_intent (
    id            uuid         PRIMARY KEY,
    attempt_id    uuid         NOT NULL REFERENCES pilot_attempt (id),
    execution_id  uuid         NOT NULL,
    repository    varchar(300) NOT NULL,
    base_branch   varchar(200) NOT NULL,
    head_branch   varchar(200) NOT NULL,
    base_sha      varchar(40)  NOT NULL,
    tree_sha      varchar(40)  NOT NULL,
    patch_sha256  varchar(64)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_pilot_publication_intent_attempt UNIQUE (attempt_id),
    CONSTRAINT uq_pilot_publication_intent_execution UNIQUE (execution_id)
);

CREATE INDEX idx_pilot_publication_intent_head ON pilot_publication_intent (repository, head_branch);
