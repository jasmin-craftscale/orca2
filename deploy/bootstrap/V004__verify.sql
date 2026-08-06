-- ORCA bootstrap · V004 · assert the shape the previous three files claim.
--
-- A grant that was never tested is a grant nobody knows the shape of. This file
-- fails loudly rather than printing a summary, because a bootstrap that half
-- worked and said nothing is worse than one that did not run.

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

-- 3 · Each schema is OWNED by its own service's user, and by no other.
--     Ownership is what lets a service create tables in its schema; if it landed
--     on dbo instead, every service would still work and the isolation would be
--     gone.
INSERT INTO @problems (problem)
SELECT N'Schema ' + x.schema_name + N' is owned by ' + ISNULL(p.name, N'(nobody)')
     + N', expected ' + x.owner_name
FROM (VALUES (N'core', N'orca_core'), (N'runtime', N'orca_runtime'), (N'edge', N'orca_edge'),
             (N'portal', N'orca_portal'), (N'sync', N'orca_sync'), (N'fleet', N'orca_fleet'),
             (N'media', N'orca_media')) AS x(schema_name, owner_name)
JOIN sys.schemas s ON s.name = x.schema_name
LEFT JOIN sys.database_principals p ON p.principal_id = s.principal_id
WHERE p.name IS NULL OR p.name <> x.owner_name;

-- 4 · Each user's DEFAULT_SCHEMA is its own. An unqualified CREATE TABLE from a
--     service lands here, so a wrong default silently puts one service's tables
--     in dbo — where the next service can read them.
INSERT INTO @problems (problem)
SELECT N'User ' + x.user_name + N' has default schema ' + ISNULL(u.default_schema_name, N'(none)')
     + N', expected ' + x.schema_name
FROM (VALUES (N'orca_core', N'core'), (N'orca_runtime', N'runtime'), (N'orca_edge', N'edge'),
             (N'orca_portal', N'portal'), (N'orca_sync', N'sync'), (N'orca_fleet', N'fleet'),
             (N'orca_media', N'media')) AS x(user_name, schema_name)
JOIN sys.database_principals u ON u.name = x.user_name
WHERE ISNULL(u.default_schema_name, N'') <> x.schema_name;

-- 5 · No service user holds ANY permission on a schema that is not its own.
--     This is the claim ADR-004 rests on, checked rather than asserted.
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

-- 6 · guest cannot connect.
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
