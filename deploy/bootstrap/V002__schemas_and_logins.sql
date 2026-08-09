-- Step two of four: seven schemas, seven logins, and each schema owned by exactly
-- one of them.
--
-- ⚠️ THIS FILE IS THE ENTIRE DATA-OWNERSHIP BOUNDARY OF THE PLATFORM. Each
-- service connects to the shared database as itself, is granted nothing on any
-- other service's schema, and has its own schema as its default. So the service
-- that runs the gate physically cannot read the configuration service's tables —
-- not because a review would catch it, and not because a build check forbids it,
-- but because the database refuses.
--
-- Nothing in Java enforces this. If you are looking for where cross-schema access
-- is prevented, it is here and in the file after it, and nowhere else.
--
-- WHY SEVEN AND NOT SIX
-- Six of the pairs belong to the Java services. The seventh, `media`, belongs to
-- the telephony engine — a separate, non-Java component that reads its own
-- configuration tables directly, in its own format. This platform does not read,
-- write or model those tables. The login exists purely so that the engine
-- connects as itself instead of as an administrator, and is confined exactly like
-- everything else.
--
-- WHERE THE PASSWORDS COME FROM
-- They are supplied as variables by the script that runs this file, which reads
-- them from the deployment's own environment file.
--
-- ⚠️ NOTHING HERE HAS A DEFAULT PASSWORD, DELIBERATELY. A bootstrap that quietly
-- invents one creates a login nobody can use and nobody can find — and, far
-- worse, one whose password is whatever this file happened to say.
--
-- THE SHAPE REPEATED SEVEN TIMES BELOW
-- Create the login if it does not exist; create the database user for it; create
-- the schema owned BY that user; then set that schema as the user's default. The
-- last step is what lets every migration write unqualified table names and still
-- land in the right place, and the fourth bootstrap file checks it rather than
-- assuming it.
--
-- Each statement is wrapped in `EXEC` so that it is compiled only when it
-- actually runs. Without that, the database would try to compile a CREATE USER
-- naming a login that does not exist yet, and fail before the guard above it
-- could prevent it. The `GO` lines are batch separators for the same reason: each
-- step must be compiled after the previous one has taken effect.

SET NOCOUNT ON;
GO

USE [orca];
GO

-- ---------------------------------------------------------------------------
-- orca-core — owns all configuration: sites, lanes, devices, users, permissions
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
-- orca-runtime — runs the gate: visits, processes, work items, connector calls
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
-- orca-edge — talks to the hardware: the capture buffer, device state, commands
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
-- orca-portal — hauliers, drivers, bookings. An empty skeleton until the hosted
-- cloud tier is built out; the schema and login exist so it can boot
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
-- orca-sync — replication between a site and the hosted tier. An empty skeleton
-- until the hosted cloud tier is built out
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
-- orca-fleet — licence issuance and software releases. Runs in the cloud only,
-- never at a customer site. An empty skeleton until that tier is built out
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
-- media — the telephony engine's own tables. Video and intercom at the gate.
--
-- ⚠️ NOT one of this platform's schemas, and not a module of this repository. No
-- migration in this repository writes here, no code here reads it, and it carries
-- none of this platform's retention rules. The engine manages its own tables in
-- its own format.
--
-- The login exists for one reason: so that the engine connects as itself and is
-- confined exactly like every other principal, rather than connecting as an
-- administrator because nobody made it an account.
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
