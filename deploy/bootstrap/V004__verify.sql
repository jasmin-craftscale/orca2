-- Step four of four: check that the database really is in the shape the previous
-- three files claim to have put it in.
--
-- WHY THIS FILE EXISTS
-- The entire data-ownership boundary of this platform is a handful of database
-- settings — who owns which schema, whose default schema is what, who has been
-- granted anything on whose. A permission that was never tested is a permission
-- nobody knows the true shape of, and every one of these mistakes fails silently:
-- a service with the wrong default schema starts up green, applies its
-- migrations, and puts its tables where the next service can read them.
--
-- ⚠️ IT FAILS LOUDLY RATHER THAN PRINTING A SUMMARY. It collects every problem it
-- finds and then raises a single error listing all of them, so nobody has to read
-- output looking for a warning. A bootstrap that half worked and said nothing is
-- worse than one that plainly did not run.
--
-- Six checks follow, each explained at its own number.

SET NOCOUNT ON;
GO

USE [orca];
GO

DECLARE @problems TABLE (problem NVARCHAR(400));

-- 1 · Seven schemas exist.
INSERT INTO @problems (problem)
SELECT N'Missing schema: ' + s.name
FROM (VALUES (N'core'), (N'runtime'), (N'edge'), (N'portal'), (N'sync'), (N'fleet'), (N'media')) AS s(name)
WHERE SCHEMA_ID(s.name) IS NULL;

-- 2 · Seven logins exist.
INSERT INTO @problems (problem)
SELECT N'Missing login: ' + l.name
FROM (VALUES (N'orca_core'), (N'orca_runtime'), (N'orca_edge'), (N'orca_portal'),
             (N'orca_sync'), (N'orca_fleet'), (N'orca_media')) AS l(name)
WHERE SUSER_ID(l.name) IS NULL;

-- 3 · Each schema is OWNED by its own service's user, and by nobody else.
--     Ownership is half of what lets a service create tables in its own schema.
--     If a schema ended up owned by the database's default owner instead, every
--     service would still appear to work perfectly — and the isolation between
--     them would be gone.
INSERT INTO @problems (problem)
SELECT N'Schema ' + x.schema_name + N' is owned by ' + ISNULL(p.name, N'(nobody)')
     + N', expected ' + x.owner_name
FROM (VALUES (N'core', N'orca_core'), (N'runtime', N'orca_runtime'), (N'edge', N'orca_edge'),
             (N'portal', N'orca_portal'), (N'sync', N'orca_sync'), (N'fleet', N'orca_fleet'),
             (N'media', N'orca_media')) AS x(schema_name, owner_name)
JOIN sys.schemas s ON s.name = x.schema_name
LEFT JOIN sys.database_principals p ON p.principal_id = s.principal_id
WHERE p.name IS NULL OR p.name <> x.owner_name;

-- 4 · Each user's default schema is its own.
--
--     ⚠️ THIS IS THE CHECK THAT CATCHES THE WORST SILENT FAILURE. Every migration
--     in this repository names its tables without a schema prefix, deliberately,
--     so that the same file lands in a different schema for each service that
--     applies it. The default schema is what decides where. A wrong one puts a
--     service's tables in the database's shared default schema — visible to every
--     other login — with no error at any point.
INSERT INTO @problems (problem)
SELECT N'User ' + x.user_name + N' has default schema ' + ISNULL(u.default_schema_name, N'(none)')
     + N', expected ' + x.schema_name
FROM (VALUES (N'orca_core', N'core'), (N'orca_runtime', N'runtime'), (N'orca_edge', N'edge'),
             (N'orca_portal', N'portal'), (N'orca_sync', N'sync'), (N'orca_fleet', N'fleet'),
             (N'orca_media', N'media')) AS x(user_name, schema_name)
JOIN sys.database_principals u ON u.name = x.user_name
WHERE ISNULL(u.default_schema_name, N'') <> x.schema_name;

-- 5 · No service holds ANY permission on a schema that is not its own.
--
--     This is the claim the whole design rests on — one service, one schema,
--     enforced by the database rather than by convention — and here it is checked
--     rather than believed. The match works by stripping the "orca_" prefix from
--     the login name to get the schema it is allowed to touch.
INSERT INTO @problems (problem)
SELECT N'Cross-schema permission: ' + u.name + N' has ' + p.permission_name
     + N' (' + p.state_desc + N') on schema ' + s.name
FROM sys.database_permissions p
JOIN sys.database_principals u ON u.principal_id = p.grantee_principal_id
JOIN sys.schemas s ON s.schema_id = p.major_id
WHERE p.class_desc = N'SCHEMA'
  AND u.name IN (N'orca_core', N'orca_runtime', N'orca_edge', N'orca_portal',
                 N'orca_sync', N'orca_fleet', N'orca_media')
  AND s.name <> REPLACE(u.name, N'orca_', N'');

-- 6 · The built-in `guest` account cannot connect. If it can, every login in the
--     instance reaches this database through it, and all five checks above become
--     decoration.
INSERT INTO @problems (problem)
SELECT N'guest has CONNECT on [orca] — every login can reach every schema through it'
WHERE EXISTS (SELECT 1 FROM sys.database_permissions p
              JOIN sys.database_principals u ON p.grantee_principal_id = u.principal_id
              WHERE u.name = N'guest' AND p.permission_name = N'CONNECT' AND p.state = N'G');

IF EXISTS (SELECT 1 FROM @problems)
BEGIN
	DECLARE @report NVARCHAR(MAX) = N'';
	SELECT @report = @report + CHAR(10) + N'  - ' + problem FROM @problems;
	RAISERROR (N'ORCA bootstrap verification FAILED:%s', 16, 1, @report);
END
ELSE
	PRINT 'ORCA bootstrap verification passed: 7 schemas, 7 logins, each confined to its own.';
GO
