-- M2 task-screen scope display: the resolved plain-language team-source identity
-- from the last complete sync, so the task screens can show it without re-reading
-- Jira. Nullable because rows that predate the first complete sync have nothing
-- recorded yet; the live statuses/restrictions keep coming from configuration.

ALTER TABLE pilot_installation
    ADD COLUMN source_name varchar(500),
    ADD COLUMN source_url varchar(1000),
    ADD COLUMN sprint_name varchar(500);
