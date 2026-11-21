-- My tasks: tasks come from the developer's own Jira assignments, every run starts from an
-- explicit click and there is no automatic admission mode, team source or sprint any more.
-- The installation row is created here, so the single active-attempt slot always has a row
-- to lock, even before the first scan.

-- Work the removed automatic mode queued without a click must not start now.
UPDATE pilot_attempt
SET state = 'WITHDRAWN',
    result_reason = 'Withdrawn before start: it was queued by the removed automatic mode'
WHERE admitted_via = 'AUTOMATIC' AND state = 'RESERVED';

UPDATE pilot_attempt SET admitted_via = 'MANUAL' WHERE admitted_via = 'AUTOMATIC';

-- Team sprint tasks are not the developer's tasks: My tasks starts empty and a scan
-- brings back only the tasks assigned to the developer.
UPDATE pilot_task SET in_scope = false;
UPDATE pilot_installation SET sync_complete = false, last_successful_sync_at = NULL, sync_problem = NULL;

ALTER TABLE pilot_attempt DROP COLUMN scope_fingerprint;

ALTER TABLE pilot_installation
    DROP COLUMN mode,
    DROP COLUMN accepted_authority_fingerprint,
    DROP COLUMN observed_authority_fingerprint,
    DROP COLUMN source_name,
    DROP COLUMN source_url,
    DROP COLUMN sprint_name;

INSERT INTO pilot_installation (id, sync_complete) VALUES (1, false) ON CONFLICT (id) DO NOTHING;
