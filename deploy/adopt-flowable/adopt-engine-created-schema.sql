-- ============================================================================
-- Repairs a development database on which the workflow engine created its own
-- tables before this system took charge of them.
--
-- ⚠️ RUN IT AS THE GATE SERVICE'S OWN LOGIN, `orca_runtime`, NEVER AS AN
-- ADMINISTRATOR. THIS SCRIPT DROPS TABLES.
--
-- Everything it touches is found through the executing login's DEFAULT SCHEMA
-- rather than a schema written into the file. Run as the service, the database
-- itself is what makes it impossible to touch anybody else's schema — the same
-- confinement that keeps each service inside its own. Run as an administrator,
-- that protection is simply gone, and the only thing standing between the script
-- and the wrong schema is whoever typed the command.
--
-- The procedure around this file, including how to run it, is
-- docs/flowable-adoption.md.
--
-- ----------------------------------------------------------------------------
-- THE SITUATION IT EXISTS FOR
--
-- The workflow engine can build its own tables on startup, and early on it was
-- configured to: `flowable.database-schema-update: true`, which is now `false`.
-- On a database where that happened, 45 engine tables exist and the migration
-- tool knows nothing about them. Those same 45 tables are now
-- defined as migrations of ours, so the next startup tries to create the first of
-- them and fails:
--
--     There is already an object named 'ACT_GE_PROPERTY' in the database.
--
-- This happened on a developer's machine during a demonstration and was cleared
-- by hand under time pressure. This file is that clearing — written down,
-- guarded, and covered by a test that runs this script rather than a copy of it.
--
-- ----------------------------------------------------------------------------
-- WHAT IT DOES, AND WHAT IT DELIBERATELY REFUSES TO DO
--
-- It adopts BY REBUILD: it checks that the engine's schema holds no process data,
-- drops the engine-created objects, and lets the ordinary migration run rebuild
-- them on the next startup. There is no editing of migration history, no
-- hand-written checksums, and nothing that depends on an internal detail of the
-- migration tool — all of which would work today and break on an upgrade.
--
-- ⚠️ IT REFUSES, LOUDLY AND WITHOUT CHANGING ANYTHING, IN TWO CASES:
--
--   * The engine's schema holds process data — running or finished. Dropping then
--     would destroy visits currently in progress and the audit trail behind
--     completed ones. An adoption that PRESERVES data is a different and much
--     larger operation; docs/flowable-adoption.md states what it would need and
--     why it is not built here.
--   * The tables were built by a different version of the engine from the one our
--     migrations were extracted from. The existing tables would then not be what
--     those migrations build, and rebuilding would silently change the schema
--     underneath an engine that has been running against the other shape. That is
--     a migration between versions, and this script will not guess at one.
--
-- A refusal raises an error at a severity that stops the command-line tool, so an
-- operator sees exactly which precondition failed instead of a half-dropped
-- schema and a zero exit code.
-- ============================================================================

SET NOCOUNT ON;
SET XACT_ABORT ON;

DECLARE @schema sysname = SCHEMA_NAME();
-- The engine version our own migrations were extracted from. If the tables in
-- front of us were built by a different one, this script stops.
DECLARE @expectedVersion nvarchar(64) = N'8.0.0.0';

PRINT N'Flowable adoption: inspecting schema [' + @schema + N']';

-- --------------------------------------------------------------------------
-- 1 · Is there anything to adopt at all?
-- --------------------------------------------------------------------------

IF OBJECT_ID(QUOTENAME(@schema) + N'.ACT_GE_PROPERTY', 'U') IS NULL
BEGIN
	-- The engine's property table is missing. That means one of two things: this
	-- schema has no engine tables at all — nothing to adopt, which is the normal
	-- case on a fresh installation — or, far worse, engine tables exist WITHOUT
	-- the one that carries the version marker.
	--
	-- The second is a state the engine's own self-migration cannot produce, so
	-- something else made it, and nothing here can establish either the version or
	-- whether the schema is complete. A script that drops tables does not proceed
	-- through a state it cannot recognise.
	DECLARE @strays int = (SELECT COUNT(*) FROM sys.tables t
		WHERE t.schema_id = SCHEMA_ID(@schema)
		  AND (t.name LIKE 'ACT[_]%' OR t.name LIKE 'FLW[_]%'));
	IF @strays > 0
	BEGIN
		RAISERROR (N'REFUSING to adopt: %d ACT_/FLW_ tables exist but ACT_GE_PROPERTY does not, so neither the engine version nor the schema''s completeness can be established. This is not a state Flowable self-migration produces; investigate by hand before running anything destructive.',
			16, 1, @strays);
		RETURN;
	END
	PRINT N'  Nothing to adopt: this schema has no engine-created Flowable tables.';
	PRINT N'  A fresh installation needs no adoption — Flyway will build them.';
	RETURN;
END

-- Are the engine tables already recorded in the migration history? Then either
-- this adoption has already been run, or the tables were built by our own
-- migrations from the start. Either way there is nothing to do, and dropping them
-- would be destroying a correctly built schema.
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
-- 2 · Refuse unless these tables were built by the engine version our own
--     migrations were extracted from.
-- --------------------------------------------------------------------------
--
-- The engine records its own schema version in its property table. If it does not
-- match, these tables are not the tables our migrations build — so rebuilding
-- them would silently change the schema underneath an engine that has been
-- running against the other shape. That is a version migration, and this script
-- will not guess at one.

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
-- One of the engine's tables holds work currently in progress; the other holds
-- the history of work already finished. Step 4 drops both, so both are counted
-- here, and any row in either stops the script.
--
-- A gate with a truck sitting mid-visit is precisely the installation somebody
-- would run this on in a hurry, which is why the check comes before the drop
-- rather than being left to the operator's judgement.

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
-- The statements are generated by reading the database's own catalog rather than
-- written out by name. A hand-written list would be a second copy of those 45
-- table names, and it is always the copy nobody remembers to update.
--
-- Foreign keys are dropped first, and they have to be: the engine's tables
-- reference one another, so dropping them in catalog order without removing the
-- references first fails partway through and leaves the schema half-gone.
--
-- The whole batch runs in one transaction, and `XACT_ABORT` at the top of the
-- file is what guarantees that any failure rolls the entire thing back. Half a
-- dropped schema is the one outcome worse than not running at all.
--
-- The count afterwards is not decoration: it is the check that the generated
-- statements actually removed everything they were supposed to.

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
