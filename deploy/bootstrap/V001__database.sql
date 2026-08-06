-- ORCA bootstrap · V001 · the database itself.
--
-- Run ONCE against a fresh SQL Server instance, before any service starts, with
-- an administrative login. Re-running is a no-op.
--
-- This file and its siblings are the privileged half of the database setup: the
-- part no service can do for itself. A service authenticating as `orca_core`
-- cannot create the `orca_core` login, and that is the point of ADR-004 rather
-- than an inconvenience of it.

SET NOCOUNT ON;
GO

IF DB_ID(N'orca') IS NULL
BEGIN
	PRINT 'Creating database [orca]';
	EXEC (N'CREATE DATABASE [orca]');
END
ELSE
	PRINT 'Database [orca] already exists — nothing to do';
GO

-- READ_COMMITTED_SNAPSHOT is on because the platform reads while it writes: the
-- outbox relay claims rows while services commit facts, and readers must not
-- block writers on the gate path. Without it, a skip-locked claim and an
-- ordinary read of the same table contend for no reason.
ALTER DATABASE [orca] SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;
GO

ALTER DATABASE [orca] SET ALLOW_SNAPSHOT_ISOLATION ON;
GO
