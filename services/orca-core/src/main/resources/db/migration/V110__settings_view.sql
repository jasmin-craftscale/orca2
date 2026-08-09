-- A read-only view that lets the gate software read this installation's settings.
--
-- WHY IT EXISTS
-- A screen's timings may be left unset, in which case the installation-wide
-- default applies. Those defaults are rows in the settings registry, which lives
-- in orca-core's schema — and no other service may read orca-core's tables, since
-- each service logs in as itself and is granted access only to its own schema. So
-- the settings are published as a view and granted by name, exactly like the
-- lanes, devices and routing rules before them.
--
-- The view resolves the fallback itself: a setting that has been given a value
-- returns that value, and one that has not returns its registered default. A
-- caller never has to know there are two tables behind it.
--
-- WHY THE WHOLE REGISTRY, RATHER THAN THE TWO KEYS THAT ARE NEEDED TODAY
-- Because there is nothing in the registry a service may not read. Secrets never
-- reach the settings table at all — the service refuses a secret-shaped key with
-- a typed error before it even consults the registry — so publishing everything
-- exposes nothing. The alternative, a view filtered to a list of named keys,
-- would mean a database migration every time a service legitimately needed one
-- more setting, which is a cost with nothing bought.
--
-- WHAT MIGHT SURPRISE YOU
-- The view carries `config_realm`, which is a constant. Settings belong to the
-- installation as a whole and have no site to be scoped by, and the shared
-- data-access code every read goes through has no unscoped read at all — so a
-- view with no site dimension has to offer another one to be read under. That
-- keeps reading installation-wide data a declared act rather than a hole.

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
