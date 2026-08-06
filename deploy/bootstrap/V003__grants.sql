-- ORCA bootstrap · V003 · grants, and the grants deliberately absent.
--
-- Each service login gets exactly what it needs to own its own schema and
-- nothing more.
--
-- WHAT IS NOT HERE MATTERS MORE THAN WHAT IS.
--
-- There is no DENY on the other six schemas, and that is deliberate rather than
-- an oversight. A SQL Server principal has no permission on an object until one
-- is granted, so the confinement is already total: `orca_core` selecting from
-- `runtime` is refused today, with no DENY written anywhere. A blanket DENY
-- would look stronger and would actively break the architecture, because DENY
-- overrides GRANT — and ADR-009 requires that core's published read-only views
-- be SELECT-grantable to runtime, edge and portal later. A DENY written now
-- would silently defeat that grant when it arrives, and the failure would look
-- like a bug in the view.
--
-- The confinement is verified, not assumed: see V004__verify.sql and
-- verify-isolation.sh.

SET NOCOUNT ON;
GO

USE [orca];
GO

-- Database-level DDL rights. Owning a schema is not by itself permission to
-- create a table in it; SQL Server wants both. Each service runs Flyway against
-- its own schema on startup, so each needs CREATE TABLE.
GRANT CREATE TABLE TO [orca_core];
GRANT CREATE TABLE TO [orca_runtime];
GRANT CREATE TABLE TO [orca_edge];
GRANT CREATE TABLE TO [orca_portal];
GRANT CREATE TABLE TO [orca_sync];
GRANT CREATE TABLE TO [orca_fleet];
GO

-- orca-core alone publishes read-only views onto its world model (ADR-009,
-- §C1). It is the only login that needs CREATE VIEW, and giving it to the other
-- five would be granting a right nobody has a use for.
GRANT CREATE VIEW TO [orca_core];
GO

-- The telephony engine's schema is populated by the engine's own tooling, not
-- by an ORCA migration. It gets CREATE TABLE for that, and nothing else.
GRANT CREATE TABLE TO [orca_media];
GO

-- `guest` is disabled in a new database by default. Stated here because a
-- guest-enabled database would silently give every login a path into every
-- schema, and that is precisely the failure this file exists to prevent.
IF EXISTS (SELECT 1 FROM sys.database_permissions p
           JOIN sys.database_principals u ON p.grantee_principal_id = u.principal_id
           WHERE u.name = N'guest' AND p.permission_name = N'CONNECT' AND p.state = N'G')
BEGIN
	PRINT 'WARNING: guest has CONNECT on [orca] — revoking';
	REVOKE CONNECT FROM [guest];
END
GO
