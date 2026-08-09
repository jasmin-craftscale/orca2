-- orca-core · Phase 3 WP3 — publish the settings registry's values.
--
-- V107 seeded the two work-item SLA defaults with the words "runtime reads this
-- in the work-item phase" on the row. This is that read's contract: the screen
-- identity's per-screen thresholds (V109) fall back to the global settings, and
-- runtime reaches settings the same way it reaches everything of core's —
-- a published view, never core's tables (ADR-009).
--
-- The whole registry is published, not just the two keys: the registry rejects
-- secrets BEFORE the table by design (V107, rule 8), so there is nothing in it
-- a service may not read — and a view filtered to named keys would need a
-- migration every time a service legitimately needs one more setting.
--
-- Installation realm (phase-2 §5.1): settings have no site dimension, and the
-- consumer reads under the config_realm dimension like topology_operator.

IF DATABASE_PRINCIPAL_ID(N'orca_runtime') IS NULL
	THROW 50110, 'orca_runtime does not exist in this database. Run deploy/bootstrap/run.sh before starting orca-core.', 1;
GO

CREATE VIEW topology_setting AS
SELECT
	d.config_realm,
	d.setting_key,
	COALESCE(v.setting_value, d.default_value) AS setting_value
FROM setting_definition d
	LEFT JOIN setting_value v ON v.setting_definition_id = d.setting_definition_id
WHERE d.retired_at IS NULL;
GO

GRANT SELECT ON topology_setting TO [orca_runtime];
GO
