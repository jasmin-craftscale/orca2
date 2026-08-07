-- ============================================================================
-- Adopting a database that let the Flowable engine migrate itself.
--
-- RUN AS THE SERVICE'S OWN LOGIN (orca_runtime), NEVER AS sa. The script works
-- on SCHEMA_NAME() — the executing login's default schema — so the database
-- itself is what stops it touching a schema that is not runtime's (ADR-004).
-- That is not a nicety: this script DROPS tables.
--
-- ----------------------------------------------------------------------------
-- The situation it exists for
--
-- Phase 0 shipped `flowable.database-schema-update: true`. On such a database
-- the engine created its own ACT_*/FLW_* tables and Flyway knows nothing about
-- them. WP3 put those 45 tables under Flyway as V110–V114, so the next start
-- runs V110 and fails:
--
--     There is already an object named 'ACT_GE_PROPERTY' in the database.
--
-- phase-1-report.md §7.4 records this happening on a developer machine during
-- the demo, where it was cleared by hand. This is that clearing, written down,
-- guarded, and tested.
--
-- ----------------------------------------------------------------------------
-- What it does, and what it deliberately REFUSES to do
--
-- It adopts BY REBUILD: it verifies the engine schema holds no process data,
-- drops the engine-created objects, and lets Flyway build them from V110–V114
-- on the next start. No schema-history surgery, no checksums written by hand,
-- nothing that depends on a Flyway internal.
--
-- It REFUSES, loudly and without changing anything, when:
--
--   * the engine schema holds live or historic process data. Dropping then
--     would destroy running visits and the audit trail behind completed ones,
--     and a data-preserving adoption is a different and larger operation —
--     see `docs/flowable-adoption.md` §4, which states what it would need and
--     why it is NOT built here.
--   * the engine tables were built by a Flowable version other than the one
--     V110–V114 were extracted from. The tables would then not be what those
--     migrations build, and rebuilding would silently change the schema under
--     an engine that has been running on the other one.
--
-- A refusal is a RAISERROR with severity 16, so sqlcmd -b stops and the
-- operator sees which precondition failed rather than a half-done schema.
-- ============================================================================

SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @schema sysname = SCHEMA_NAME();
DECLARE @expectedVersion nvarchar(64) = N'8.0.0.0';  -- V110's extraction source

PRINT N'Flowable adoption: inspecting schema [' + @schema + N']';

-- --------------------------------------------------------------------------
-- 1 · Is there anything to adopt at all?
-- --------------------------------------------------------------------------

IF OBJECT_ID(QUOTENAME(@schema) + N'.ACT_GE_PROPERTY', 'U') IS NULL
BEGIN
	PRINT N'  Nothing to adopt: this schema has no engine-created Flowable tables.';
	PRINT N'  A fresh installation needs no adoption — Flyway will build them.';
	RETURN;
END

-- Already under Flyway? Then this has been done, or the schema was built by
-- V110–V114 in the first place. Either way, do not touch it.
IF OBJECT_ID(QUOTENAME(@schema) + N'.flyway_schema_history', 'U') IS NOT NULL
   AND EXISTS (SELECT 1 FROM sys.objects o
               WHERE o.object_id = OBJECT_ID(QUOTENAME(@schema) + N'.flyway_schema_history'))
BEGIN
	DECLARE @underFlyway int;
	DECLARE @sql nvarchar(max) = N'SELECT @found = COUNT(*) FROM '
		+ QUOTENAME(@schema) + N'.flyway_schema_history WHERE version = N''110''';
	EXEC sp_executesql @sql, N'@found int OUTPUT', @found = @underFlyway OUTPUT;

	IF @underFlyway > 0
	BEGIN
		PRINT N'  Nothing to adopt: V110 is already recorded in flyway_schema_history.';
		RETURN;
	END
END

-- --------------------------------------------------------------------------
-- 2 · Refuse unless the engine that built these tables is the one we extracted
-- --------------------------------------------------------------------------
--
-- ACT_GE_PROPERTY carries the engine's own schema-version markers. If they do
-- not say 8.0.0.0, these tables are not what V110–V114 build, and rebuilding
-- would change the schema under an engine that has been running against the
-- other one. That is a migration, not an adoption.

DECLARE @foundVersion nvarchar(300);
DECLARE @versionSql nvarchar(max) = N'SELECT @v = VALUE_ FROM '
	+ QUOTENAME(@schema) + N'.ACT_GE_PROPERTY WHERE NAME_ = N''common.schema.version''';
EXEC sp_executesql @versionSql, N'@v nvarchar(300) OUTPUT', @v = @foundVersion OUTPUT;

IF @foundVersion IS NULL OR @foundVersion <> @expectedVersion
BEGIN
	RAISERROR (N'REFUSING to adopt: ACT_GE_PROPERTY reports common.schema.version = "%s", and V110-V114 were extracted from Flowable %s. These tables are not what those migrations build. Adopting across engine versions is a migration and this script will not guess it - see docs/flowable-adoption.md.',
		16, 1, @foundVersion, @expectedVersion);
	RETURN;
END

PRINT N'  Engine schema version ' + @foundVersion + N' matches the extracted migrations.';

-- --------------------------------------------------------------------------
-- 3 · Refuse if there is process data. This is the safety property.
-- --------------------------------------------------------------------------
--
-- ACT_RU_EXECUTION is running work; ACT_HI_PROCINST is the history of finished
-- work. Both are dropped by step 4, so both are checked here. A gate with a
-- truck mid-visit is exactly the installation somebody would run this on in a
-- hurry.

DECLARE @running bigint, @historic bigint;
DECLARE @dataSql nvarchar(max) =
	N'SELECT @r = (SELECT COUNT(*) FROM ' + QUOTENAME(@schema) + N'.ACT_RU_EXECUTION),'
	+ N'       @h = (SELECT COUNT(*) FROM ' + QUOTENAME(@schema) + N'.ACT_HI_PROCINST)';
EXEC sp_executesql @dataSql, N'@r bigint OUTPUT, @h bigint OUTPUT',
	@r = @running OUTPUT, @h = @historic OUTPUT;

IF @running > 0 OR @historic > 0
BEGIN
	RAISERROR (N'REFUSING to adopt: this engine schema holds process data (%I64d running executions, %I64d historic process instances). Adopting by rebuild would destroy running visits and the audit trail behind completed ones. A data-preserving adoption is NOT built - docs/flowable-adoption.md section 4 states what it would need and why.',
		16, 1, @running, @historic);
	RETURN;
END

PRINT N'  No process data: 0 running executions, 0 historic process instances.';

-- --------------------------------------------------------------------------
-- 4 · Drop the engine-created objects, foreign keys first
-- --------------------------------------------------------------------------
--
-- Generated from sys.tables rather than listed by name. A hand-written list is
-- a second copy of the 45 tables, and it is the copy nobody updates.

DECLARE @drop nvarchar(max) = N'';

SELECT @drop = @drop + N'ALTER TABLE ' + QUOTENAME(@schema) + N'.' + QUOTENAME(t.name)
		+ N' DROP CONSTRAINT ' + QUOTENAME(fk.name) + N';' + CHAR(10)
FROM sys.foreign_keys fk
JOIN sys.tables t ON t.object_id = fk.parent_object_id
WHERE t.schema_id = SCHEMA_ID(@schema)
  AND (t.name LIKE 'ACT[_]%' OR t.name LIKE 'FLW[_]%');

SELECT @drop = @drop + N'DROP TABLE ' + QUOTENAME(@schema) + N'.' + QUOTENAME(t.name) + N';' + CHAR(10)
FROM sys.tables t
WHERE t.schema_id = SCHEMA_ID(@schema)
  AND (t.name LIKE 'ACT[_]%' OR t.name LIKE 'FLW[_]%');

BEGIN TRANSACTION;
EXEC sp_executesql @drop;
COMMIT TRANSACTION;

DECLARE @remaining int = (SELECT COUNT(*) FROM sys.tables t
	WHERE t.schema_id = SCHEMA_ID(@schema)
	  AND (t.name LIKE 'ACT[_]%' OR t.name LIKE 'FLW[_]%'));

IF @remaining > 0
	RAISERROR (N'Adoption incomplete: %d engine tables remain. Do NOT start the service; investigate.', 16, 1, @remaining);
ELSE
	PRINT N'  Adopted. The engine tables are gone; V110-V114 will build them on the next start.';
