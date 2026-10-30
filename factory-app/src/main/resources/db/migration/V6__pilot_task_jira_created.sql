-- Queue order follows the Jira creation time of the discovered issue, which is
-- immutable data, not the local row discovery time. Nullable: rows discovered
-- before this column existed (and direct test fixtures) keep working.

ALTER TABLE pilot_task ADD COLUMN jira_created_at timestamptz;
