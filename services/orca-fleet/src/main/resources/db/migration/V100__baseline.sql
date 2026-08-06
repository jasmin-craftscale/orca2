-- orca-fleet · baseline.
--
-- Phase 0 builds NO business logic, so this service has no business tables yet.
-- What it does have is a schema it owns, and this migration is what puts that
-- ownership in the database where an operator can read it — rather than only in
-- a document.
--
-- It also proves the mechanism the whole of §7 items 4, 4b and 9b rest on: this
-- file was applied to schema `fleet` by `orca-fleet` itself, authenticating as
-- `orca_fleet`, on its own startup. If the credentials or the default schema
-- were wrong, this would have landed somewhere else or not at all.

IF NOT EXISTS (
	SELECT 1 FROM sys.extended_properties
	WHERE class = 3 AND major_id = SCHEMA_ID(N'fleet') AND name = N'orca_owner')
	EXEC sys.sp_addextendedproperty
		@name = N'orca_owner', @value = N'orca-fleet',
		@level0type = N'SCHEMA', @level0name = N'fleet';

IF NOT EXISTS (
	SELECT 1 FROM sys.extended_properties
	WHERE class = 3 AND major_id = SCHEMA_ID(N'fleet') AND name = N'orca_chapter')
	EXEC sys.sp_addextendedproperty
		@name = N'orca_chapter', @value = N'§C6 — licences and releases',
		@level0type = N'SCHEMA', @level0name = N'fleet';
