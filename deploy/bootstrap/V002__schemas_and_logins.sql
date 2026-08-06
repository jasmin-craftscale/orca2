-- ORCA bootstrap · V002 · seven schemas, seven logins, one owner each.
--
-- This is ADR-004 made real. Schema ownership is enforced by DATABASE
-- CREDENTIALS, not by convention: `orca_core` owns the `core` schema and has no
-- permission of any kind on `runtime`, and neither a code review nor a build
-- check is what stops it — the database does.
--
-- Seven, not six. The six JVM services each get a schema and a login. The
-- seventh pair, `media`, exists for the telephony engine, which reads its own
-- configuration tables directly in its own format (§B5, §C7). ORCA does not
-- read, write or model those tables; the login exists so that Asterisk connects
-- as itself rather than as an administrator, and it is confined the same way
-- every other login is.
--
-- Passwords arrive as sqlcmd variables from run.sh, which reads them from .env.
-- Nothing here has a default: a bootstrap that silently invents a password is a
-- bootstrap that creates a login nobody can use and nobody can find.

SET NOCOUNT ON;
GO

USE [orca];
GO

-- ---------------------------------------------------------------------------
-- orca-core — the world as configured (§C1)
-- ---------------------------------------------------------------------------
IF SUSER_ID(N'orca_core') IS NULL
	EXEC (N'CREATE LOGIN [orca_core] WITH PASSWORD = ''$(CORE_PASSWORD)'', CHECK_POLICY = OFF');
GO
IF DATABASE_PRINCIPAL_ID(N'orca_core') IS NULL
	EXEC (N'CREATE USER [orca_core] FOR LOGIN [orca_core]');
GO
IF SCHEMA_ID(N'core') IS NULL
	EXEC (N'CREATE SCHEMA [core] AUTHORIZATION [orca_core]');
GO
EXEC (N'ALTER USER [orca_core] WITH DEFAULT_SCHEMA = [core]');
GO

-- ---------------------------------------------------------------------------
-- orca-runtime — the gate brain (§C2)
-- ---------------------------------------------------------------------------
IF SUSER_ID(N'orca_runtime') IS NULL
	EXEC (N'CREATE LOGIN [orca_runtime] WITH PASSWORD = ''$(RUNTIME_PASSWORD)'', CHECK_POLICY = OFF');
GO
IF DATABASE_PRINCIPAL_ID(N'orca_runtime') IS NULL
	EXEC (N'CREATE USER [orca_runtime] FOR LOGIN [orca_runtime]');
GO
IF SCHEMA_ID(N'runtime') IS NULL
	EXEC (N'CREATE SCHEMA [runtime] AUTHORIZATION [orca_runtime]');
GO
EXEC (N'ALTER USER [orca_runtime] WITH DEFAULT_SCHEMA = [runtime]');
GO

-- ---------------------------------------------------------------------------
-- orca-edge — the hardware boundary (§C3)
-- ---------------------------------------------------------------------------
IF SUSER_ID(N'orca_edge') IS NULL
	EXEC (N'CREATE LOGIN [orca_edge] WITH PASSWORD = ''$(EDGE_PASSWORD)'', CHECK_POLICY = OFF');
GO
IF DATABASE_PRINCIPAL_ID(N'orca_edge') IS NULL
	EXEC (N'CREATE USER [orca_edge] FOR LOGIN [orca_edge]');
GO
IF SCHEMA_ID(N'edge') IS NULL
	EXEC (N'CREATE SCHEMA [edge] AUTHORIZATION [orca_edge]');
GO
EXEC (N'ALTER USER [orca_edge] WITH DEFAULT_SCHEMA = [edge]');
GO

-- ---------------------------------------------------------------------------
-- orca-portal — carriers and drivers (§C4)
-- ---------------------------------------------------------------------------
IF SUSER_ID(N'orca_portal') IS NULL
	EXEC (N'CREATE LOGIN [orca_portal] WITH PASSWORD = ''$(PORTAL_PASSWORD)'', CHECK_POLICY = OFF');
GO
IF DATABASE_PRINCIPAL_ID(N'orca_portal') IS NULL
	EXEC (N'CREATE USER [orca_portal] FOR LOGIN [orca_portal]');
GO
IF SCHEMA_ID(N'portal') IS NULL
	EXEC (N'CREATE SCHEMA [portal] AUTHORIZATION [orca_portal]');
GO
EXEC (N'ALTER USER [orca_portal] WITH DEFAULT_SCHEMA = [portal]');
GO

-- ---------------------------------------------------------------------------
-- orca-sync — replication (§C5)
-- ---------------------------------------------------------------------------
IF SUSER_ID(N'orca_sync') IS NULL
	EXEC (N'CREATE LOGIN [orca_sync] WITH PASSWORD = ''$(SYNC_PASSWORD)'', CHECK_POLICY = OFF');
GO
IF DATABASE_PRINCIPAL_ID(N'orca_sync') IS NULL
	EXEC (N'CREATE USER [orca_sync] FOR LOGIN [orca_sync]');
GO
IF SCHEMA_ID(N'sync') IS NULL
	EXEC (N'CREATE SCHEMA [sync] AUTHORIZATION [orca_sync]');
GO
EXEC (N'ALTER USER [orca_sync] WITH DEFAULT_SCHEMA = [sync]');
GO

-- ---------------------------------------------------------------------------
-- orca-fleet — licences and releases (§C6). Cloud only; never at a site.
-- ---------------------------------------------------------------------------
IF SUSER_ID(N'orca_fleet') IS NULL
	EXEC (N'CREATE LOGIN [orca_fleet] WITH PASSWORD = ''$(FLEET_PASSWORD)'', CHECK_POLICY = OFF');
GO
IF DATABASE_PRINCIPAL_ID(N'orca_fleet') IS NULL
	EXEC (N'CREATE USER [orca_fleet] FOR LOGIN [orca_fleet]');
GO
IF SCHEMA_ID(N'fleet') IS NULL
	EXEC (N'CREATE SCHEMA [fleet] AUTHORIZATION [orca_fleet]');
GO
EXEC (N'ALTER USER [orca_fleet] WITH DEFAULT_SCHEMA = [fleet]');
GO

-- ---------------------------------------------------------------------------
-- media — the telephony engine's own tables (§B5, §C7)
--
-- NOT an ORCA application schema and NOT a Gradle module. No ORCA migration
-- writes here, no ORCA code reads it, and it carries no ORCA retention rule.
-- The login exists so that the engine connects as itself and is confined like
-- every other principal.
-- ---------------------------------------------------------------------------
IF SUSER_ID(N'orca_media') IS NULL
	EXEC (N'CREATE LOGIN [orca_media] WITH PASSWORD = ''$(MEDIA_PASSWORD)'', CHECK_POLICY = OFF');
GO
IF DATABASE_PRINCIPAL_ID(N'orca_media') IS NULL
	EXEC (N'CREATE USER [orca_media] FOR LOGIN [orca_media]');
GO
IF SCHEMA_ID(N'media') IS NULL
	EXEC (N'CREATE SCHEMA [media] AUTHORIZATION [orca_media]');
GO
EXEC (N'ALTER USER [orca_media] WITH DEFAULT_SCHEMA = [media]');
GO
