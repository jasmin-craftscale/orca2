-- Step one of four: create the database and set the two isolation options
-- everything else depends on.
--
-- HOW THIS FILE IS RUN
-- Once, against a fresh SQL Server instance, before any service starts, using an
-- administrative login. Running it again does nothing. In a local development
-- stack that is `docker compose run --rm bootstrap` from the deploy directory;
-- deploy/README.md is the full procedure.
--
-- WHY THESE FOUR FILES EXIST SEPARATELY FROM THE SERVICES
-- They are the privileged half of the database setup — the part no service can do
-- for itself. Each service connects as its own login and can reach only its own
-- schema, so a service authenticating as `orca_core` cannot possibly create the
-- `orca_core` login or grant it anything. That is the point of the design, not an
-- inconvenience in it: if a service could set up its own permissions, the
-- permissions would not be a boundary.
--
-- The four files, in order: this one creates the database; the second creates the
-- schemas and logins; the third grants the few cross-schema permissions that are
-- deliberate; the fourth verifies that the confinement actually holds.

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

-- ⚠️ THESE TWO SETTINGS ARE NOT OPTIONAL TUNING. Under this database engine's
-- default behaviour, a plain read takes locks and waits behind a write to the
-- same rows. This platform reads while it writes constantly — the component
-- draining the outbox claims rows at the same moment services are committing new
-- facts into it — and a reader that blocks a writer on the path a truck is
-- waiting on is a barrier that does not lift.
--
-- Turning on read-committed snapshot makes an ordinary read see the last
-- committed version of a row instead of queueing for the current one. Without it,
-- the outbox claim (which deliberately skips rows another instance is holding)
-- and an ordinary read of the same table contend for no reason at all.
--
-- `WITH ROLLBACK IMMEDIATE` disconnects anything already connected, because the
-- setting cannot be changed while sessions are open. That is safe here and only
-- here: this runs before any service starts.
ALTER DATABASE [orca] SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE;
GO

-- The second setting allows a transaction to ask explicitly for a consistent
-- snapshot of the whole database for its duration — needed where several reads
-- must agree with each other.
ALTER DATABASE [orca] SET ALLOW_SNAPSHOT_ISOLATION ON;
GO
