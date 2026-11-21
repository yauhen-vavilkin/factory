-- S07 authority model: discovery records the OBSERVED source authority on every
-- complete sync, while the ACCEPTED fingerprint changes only through an explicit
-- automatic-mode enable or an approved admission, so a saved-filter, routing or
-- destination change stays detectable instead of being silently re-accepted.
-- Attempts record how they were admitted, because only automatic initial
-- attempts may consume refreshed input at dispatch; manual initial attempts and
-- retries keep the frozen approved input.

ALTER TABLE pilot_installation ADD COLUMN observed_authority_fingerprint varchar(64);

ALTER TABLE pilot_attempt ADD COLUMN admitted_via varchar(16) NOT NULL DEFAULT 'MANUAL';
