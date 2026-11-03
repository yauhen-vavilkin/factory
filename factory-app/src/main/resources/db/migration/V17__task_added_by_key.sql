-- A task added to My tasks by its key stays in the list until it runs or the developer removes it:
-- a scan does not take it out, as it does with tasks that are no longer assigned to the developer.
ALTER TABLE task ADD COLUMN added_by_key boolean NOT NULL DEFAULT false;

-- Tasks run by key before this change keep their "added by key" mark under Triggered.
UPDATE task SET added_by_key = true
WHERE NOT in_scope
  AND EXISTS (SELECT 1 FROM task_attempt a WHERE a.task_id = task.id AND a.admitted_via = 'BY_KEY');
