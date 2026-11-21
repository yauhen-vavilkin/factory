-- The run settings chosen when an attempt was started: profile id and label, model and endpoint origin
-- (for display, never credentials), effort, max turns and the operator's instructions. Older attempts
-- have none: they ran with the default profile and its defaults.
ALTER TABLE pilot_attempt ADD COLUMN run_settings jsonb;
