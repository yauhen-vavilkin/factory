-- T25 durable attempt ordinals — purely additive: per-(execution, step)
-- monotonically ascending attempt counter map. Deliberately separate from
-- retry_counts (a pure retry budget that resetRetry clears): an allocated
-- ordinal is durable and never reissued, even after a budget reset or an
-- escalation-approved requeue. No existing row is rewritten.

ALTER TABLE pipeline_execution ADD COLUMN attempt_counts jsonb NOT NULL DEFAULT '{}';
