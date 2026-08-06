-- orca-edge · baseline.
--
-- Phase 0 builds NO business logic, so this service has no business tables yet.
-- What it does have is a schema it owns, and this migration is what puts that
-- ownership in the database where an operator can read it — rather than only in
-- a document.
--
-- It also proves the mechanism the whole of §7 items 4, 4b and 9b rest on: this
-- file was applied to schema `edge` by `orca-edge` itself, authenticating as
-- `orca_edge`, on its own startup. If the credentials or the default schema
-- were wrong, this would have landed somewhere else or not at all.

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
