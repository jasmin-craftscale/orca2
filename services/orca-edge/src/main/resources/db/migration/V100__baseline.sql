-- The first migration orca-edge applies. It creates no tables — it labels the
-- schema, and by running at all it proves the service reached its own schema.
--
-- WHAT IT DOES
-- It attaches two labels to the `edge` schema itself, using SQL Server's
-- extended-property mechanism: which service owns the schema, and a one-line
-- description of what that service is for. An operator connected to the database
-- can then read who owns a schema from the database, instead of having to find
-- the right document first.
--
-- WHY A MIGRATION THAT CREATES NOTHING IS WORTH HAVING
-- Every service in this installation connects to the same database as itself:
-- its own login, granted access to one schema only, with that schema as the
-- login's default. That confinement is the entire data-ownership boundary, and
-- it is wired up outside Java — in the database, by the setup scripts under
-- deploy/bootstrap, which create the logins, the schemas and the grants once
-- against a fresh database. A login pointed at the wrong default schema does not
-- announce itself; it quietly writes to the wrong place until somebody notices
-- months later.
--
-- The proof is that this file was applied at all. orca-edge applied it on its own
-- startup, authenticating as the `orca_edge` login, and Flyway recorded it in a
-- history table in that login's default schema. Wrong credentials or a wrong
-- default schema, and this would have landed somewhere else or not at all.
--
-- The `IF NOT EXISTS` guards make the two writes safe to repeat, so the file can
-- be replayed against a database that already carries the labels without error.

IF NOT EXISTS (
	SELECT 1 FROM sys.extended_properties
	WHERE class = 3 AND major_id = SCHEMA_ID(N'edge') AND name = N'orca_owner')
	EXEC sys.sp_addextendedproperty
		@name = N'orca_owner', @value = N'orca-edge',
		@level0type = N'SCHEMA', @level0name = N'edge';

IF NOT EXISTS (
	SELECT 1 FROM sys.extended_properties
	WHERE class = 3 AND major_id = SCHEMA_ID(N'edge') AND name = N'orca_chapter')
	EXEC sys.sp_addextendedproperty
		@name = N'orca_chapter', @value = N'§C3 — the hardware boundary',
		@level0type = N'SCHEMA', @level0name = N'edge';
