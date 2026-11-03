-- Why Factory opened the attempt's pull request as a draft, copied from dev_delivery.json:
-- a JSON array of reasons in plain words. Null: the pull request is ready for review, or none was opened.
ALTER TABLE pilot_attempt ADD COLUMN draft_reasons jsonb;
