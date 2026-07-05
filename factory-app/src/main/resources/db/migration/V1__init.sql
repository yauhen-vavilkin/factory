-- FOLIO AI SDLC Factory — initial schema

CREATE TABLE flow_registry (
    flow_id       varchar(100)  NOT NULL,
    version       varchar(50)   NOT NULL,
    name          varchar(255)  NOT NULL,
    yaml_sha256   varchar(64)   NOT NULL,
    raw_yaml      text          NOT NULL,
    registered_at timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (flow_id, version)
);

CREATE TABLE pipeline_execution (
    id                  uuid         PRIMARY KEY,
    flow_id             varchar(100) NOT NULL,
    flow_version        varchar(50)  NOT NULL,
    status              varchar(30)  NOT NULL,
    current_step_index  int          NOT NULL DEFAULT 0,
    parent_execution_id uuid,
    parent_step_index   int,
    trigger_payload     jsonb,
    retry_counts        jsonb        NOT NULL DEFAULT '{}',
    error_message       text,
    next_run_at         timestamptz  NOT NULL DEFAULT now(),
    version             bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    completed_at        timestamptz,
    CONSTRAINT fk_parent_execution FOREIGN KEY (parent_execution_id) REFERENCES pipeline_execution (id)
);

CREATE INDEX idx_execution_claimable ON pipeline_execution (status, next_run_at);
CREATE INDEX idx_execution_parent ON pipeline_execution (parent_execution_id);

CREATE TABLE artifact (
    id           uuid          PRIMARY KEY,
    execution_id uuid          NOT NULL REFERENCES pipeline_execution (id),
    name         varchar(255)  NOT NULL,
    version      int           NOT NULL,
    content_type varchar(100)  NOT NULL DEFAULT 'text/markdown',
    content      text          NOT NULL,
    sha256       varchar(64)   NOT NULL,
    created_by   varchar(255)  NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_artifact_version UNIQUE (execution_id, name, version)
);

CREATE TABLE audit_event (
    id           bigserial     PRIMARY KEY,
    execution_id uuid,
    event_type   varchar(50)   NOT NULL,
    step_id      varchar(100),
    actor        varchar(255)  NOT NULL,
    detail       jsonb,
    occurred_at  timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_execution ON audit_event (execution_id, id);

CREATE FUNCTION forbid_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

CREATE TABLE hitl_review (
    id                 uuid         PRIMARY KEY,
    execution_id       uuid         NOT NULL REFERENCES pipeline_execution (id),
    gate_id            varchar(100) NOT NULL,
    step_index         int          NOT NULL,
    status             varchar(20)  NOT NULL,
    review_package     jsonb        NOT NULL,
    decision           varchar(20),
    reviewer           varchar(255),
    comments           text,
    amended_artifacts  jsonb,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    decided_at         timestamptz
);

CREATE INDEX idx_hitl_status ON hitl_review (status, created_at);
