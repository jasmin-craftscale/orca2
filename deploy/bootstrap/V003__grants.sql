-- Step three of four: the few permissions each service actually needs, and the
-- permission that is deliberately NOT written here.
--
-- Each login gets exactly enough to own and populate its own schema, and nothing
-- else.
--
-- ⚠️ WHAT IS ABSENT FROM THIS FILE MATTERS MORE THAN WHAT IS IN IT.
--
-- THERE IS NO `DENY` ON ANY OTHER SERVICE'S SCHEMA. That is a decision, not an
-- oversight, and it looks wrong at first glance. Two things make it right:
--
--   1. It would add nothing. In SQL Server a principal has NO permission on an
--      object until one is granted. The confinement is already complete —
--      `orca_core` selecting from the `runtime` schema is refused today, with no
--      DENY anywhere in this file.
--   2. It would actively break the platform. A DENY OVERRIDES A GRANT. The
--      configuration service publishes read-only views of its world model and
--      grants other services permission to read them — the only sanctioned way
--      one service sees another's data. A blanket DENY written here "for
--      tidiness" would silently defeat those grants when they arrive, and the
--      resulting failure would look like a bug in the view rather than a
--      permissions problem.
--
-- So the rule is: confinement comes from granting nothing, never from denying.
-- And it is verified rather than assumed — the next bootstrap file checks it, and
-- deploy/bootstrap/verify-isolation.sh checks it again from outside, by actually
-- attempting the forbidden reads.

SET NOCOUNT ON;
GO

USE [orca];
GO

-- Permission to create tables, granted at the level of the database as a whole.
--
-- ⚠️ Owning a schema is NOT by itself permission to create a table in it. SQL
-- Server wants both, and it is a genuinely confusing pair of rules — a login that
-- owns its schema and lacks this right fails on its very first migration with a
-- permissions error naming the database rather than the schema.
--
-- Every service applies its own migrations against its own schema when it starts,
-- so every service needs it.
GRANT CREATE TABLE TO [orca_core];
GRANT CREATE TABLE TO [orca_runtime];
GRANT CREATE TABLE TO [orca_edge];
GRANT CREATE TABLE TO [orca_portal];
GRANT CREATE TABLE TO [orca_sync];
GRANT CREATE TABLE TO [orca_fleet];
GO

-- Only the configuration service creates views, because it is the only service
-- that publishes any: read-only views of sites, lanes, devices, screens, routing
-- and settings, which are how every other service sees that data without being
-- able to reach the tables underneath.
--
-- Granting this to the other five would hand out a right none of them has a use
-- for — and a permission nobody can justify is one nobody will dare remove later.
GRANT CREATE VIEW TO [orca_core];
GO

-- The telephony engine builds its own tables with its own tooling; no migration
-- in this repository writes to its schema. It gets the same permission to create
-- tables and nothing else at all.
GRANT CREATE TABLE TO [orca_media];
GO

-- ⚠️ THE ONE SETTING THAT WOULD DISSOLVE EVERYTHING ABOVE.
--
-- `guest` is a built-in account that every login falls back to when it has no
-- account of its own in a database. It cannot connect in a newly created database
-- — but if somebody has enabled it, every login in the instance gains whatever
-- guest has, which is a path into schemas none of them should reach.
--
-- That is exactly the failure this file exists to prevent, so it is checked and
-- undone here rather than assumed. The warning is printed rather than swallowed:
-- somebody enabled it on purpose, and they should find out it was reverted.
IF EXISTS (SELECT 1 FROM sys.database_permissions p
           JOIN sys.database_principals u ON p.grantee_principal_id = u.principal_id
           WHERE u.name = N'guest' AND p.permission_name = N'CONNECT' AND p.state = N'G')
BEGIN
	PRINT 'WARNING: guest has CONNECT on [orca] — revoking';
	REVOKE CONNECT FROM [guest];
END
GO
