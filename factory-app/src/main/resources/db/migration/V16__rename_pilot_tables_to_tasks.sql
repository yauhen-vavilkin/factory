-- My tasks was called "pilot" in the code. Rename its tables, and the constraints and indexes
-- named after them, to the task names the code uses now. Rows are kept as they are.
ALTER TABLE pilot_task RENAME TO task;
ALTER TABLE pilot_attempt RENAME TO task_attempt;
ALTER TABLE pilot_installation RENAME TO task_installation;
ALTER TABLE pilot_publication_intent RENAME TO task_publication_intent;

DO $$
DECLARE
    item record;
BEGIN
    FOR item IN
        SELECT c.conrelid::regclass AS owner, c.conname AS name
        FROM pg_constraint c
        JOIN pg_namespace n ON n.oid = c.connamespace
        WHERE n.nspname = current_schema() AND c.conname LIKE '%pilot%'
    LOOP
        EXECUTE format('ALTER TABLE %s RENAME CONSTRAINT %I TO %I', item.owner, item.name,
                       replace(replace(item.name, 'pilot_task', 'task'), 'pilot_', 'task_'));
    END LOOP;
    -- Indexes behind renamed constraints were renamed with them; this covers the plain ones.
    FOR item IN
        SELECT indexname AS name FROM pg_indexes
        WHERE schemaname = current_schema() AND indexname LIKE '%pilot%'
    LOOP
        EXECUTE format('ALTER INDEX %I RENAME TO %I', item.name,
                       replace(replace(item.name, 'pilot_task', 'task'), 'pilot_', 'task_'));
    END LOOP;
END $$;

-- An execution started for a task attempt carries the attempt id in its dedup key; keep the
-- existing keys matching the new prefix so a re-dispatched attempt still finds its execution.
UPDATE pipeline_execution
SET dedup_key = 'task-attempt=' || substring(dedup_key FROM 15)
WHERE dedup_key LIKE 'pilot-attempt=%';
