-- The readable conversation of each coding run: the prompt, the agent's messages, tool calls with
-- their results and the final result. One execution has several runs (initial, continuation, each
-- PR feedback round); seq keeps the order inside a run. Rows are written in small batches while the
-- run is going, so a Factory restart loses at most the last batch. Retention removes them with the
-- execution.
CREATE TABLE coding_transcript_entry (
    execution_id uuid         NOT NULL REFERENCES pipeline_execution (id) ON DELETE CASCADE,
    run_id       uuid         NOT NULL,
    seq          int          NOT NULL,
    step_id      varchar(100) NOT NULL,
    kind         varchar(30)  NOT NULL,
    occurred_at  timestamptz  NOT NULL,
    detail       jsonb        NOT NULL,
    PRIMARY KEY (execution_id, run_id, seq)
);
