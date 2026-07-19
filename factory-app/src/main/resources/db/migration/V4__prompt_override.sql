CREATE TABLE prompt_override (
    id bigserial PRIMARY KEY,
    worker_id varchar(100) NOT NULL,
    prompt_name varchar(100) NOT NULL,
    version int NOT NULL,
    use_default boolean NOT NULL DEFAULT false,
    content text,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_prompt_override UNIQUE (worker_id, prompt_name, version),
    CONSTRAINT chk_prompt_override_content CHECK (use_default OR content IS NOT NULL)
);

CREATE INDEX idx_audit_event_type ON audit_event (event_type, id);
