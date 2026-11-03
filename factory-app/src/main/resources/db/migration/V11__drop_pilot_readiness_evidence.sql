-- The pilot starts with plain docker compose up -d; there is no startup action or
-- runtime check any more, so its evidence table and the installation's reference
-- to it go away.

DROP TABLE pilot_readiness_evidence;

ALTER TABLE pilot_installation
    DROP COLUMN runtime_readiness_ref;
