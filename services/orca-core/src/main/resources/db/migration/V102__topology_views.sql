-- Read-only views that let other services see this installation's sites, areas,
-- lanes and devices.
--
-- WHAT THIS IS FOR
-- The orca-core service owns all configuration data — the customer, its sites,
-- the areas within a site, the lanes within an area, and the devices attached to
-- each lane. Other services constantly need a small part of that: orca-runtime
-- must know which lane a truck arrived at; orca-edge must know which devices are
-- on that lane. They cannot read core's tables directly, because each service
-- logs in to the database as itself and is granted access only to its own schema.
--
-- These two views are the controlled exception. Core publishes them, grants
-- SELECT on them to the services that need them, and those services read them
-- inside their own transaction — no HTTP call to core, no network delay on the
-- path a truck is waiting on, and no way for a reader to write anything.
--
-- Publishing a view rather than opening up the tables is what keeps core free to
-- change: it can restructure `lane` and `device` however it likes, as long as the
-- views keep answering the same columns.
--
-- WHY THE NAMES LOOK LIKE THIS
-- `topology_lane`, not `topology.lane`. The views live inside the `core` schema
-- with a `topology_` prefix rather than in a schema of their own, so there is no
-- extra schema, no extra database login, and no extra entry in the setup scripts
-- under deploy/bootstrap. The product owner ruled this on 7 August 2026.
--
-- WHAT MIGHT SURPRISE YOU
--
-- 1. Both identifiers are exposed — the internal numeric key (`lane_id`) and the
--    external string id (`lane_external_id`) — and that is deliberate. The
--    runtime correlates, locks and indexes on the numeric key, while anything
--    crossing a service boundary uses the external id. These views are the only
--    source of lane identity a consumer has, so they publish both and let the
--    consumer choose. If the platform ever settles on one, the other column is
--    dropped from the view; nothing has to be rebuilt to get there.
--
-- 2. Retired rows never appear. Nothing here is ever deleted: a row is retired by
--    setting `retired_at`, and both views filter those out. That is what makes
--    the convention safe to have — no consumer has to remember it, and no
--    consumer can forget it.
--
-- 3. The `GO` separators are required, not decoration. SQL Server demands that
--    CREATE VIEW be the first statement in its batch. Without an explicit
--    separator the two views and the grants below arrive as a single batch, and
--    the migration fails with the memorable but unhelpful error
--    "Incorrect syntax near the keyword 'CREATE'".

CREATE VIEW topology_lane AS
SELECT
	l.lane_id,
	l.external_id          AS lane_external_id,
	l.code                 AS lane_code,
	l.name                 AS lane_name,
	l.device_host_url,
	l.is_out_of_service,
	l.lane_priority,
	a.area_id,
	a.external_id          AS area_external_id,
	a.code                 AS area_code,
	s.site_id,
	s.external_id          AS site_external_id,
	s.code                 AS site_code,
	s.is_primary           AS site_is_primary
FROM lane l
	JOIN area a ON a.area_id = l.area_id
	JOIN site s ON s.site_id = a.site_id
WHERE l.retired_at IS NULL
  AND a.retired_at IS NULL
  AND s.retired_at IS NULL;
GO

CREATE VIEW topology_device AS
SELECT
	d.device_id,
	d.external_id          AS device_external_id,
	d.device_type,
	d.name                 AS device_name,
	d.address,
	l.lane_id,
	l.external_id          AS lane_external_id,
	s.site_id,
	s.external_id          AS site_external_id
FROM device d
	JOIN lane l ON l.lane_id = d.lane_id
	JOIN area a ON a.area_id = l.area_id
	JOIN site s ON s.site_id = a.site_id
WHERE d.retired_at IS NULL
  AND l.retired_at IS NULL
  AND a.retired_at IS NULL
  AND s.retired_at IS NULL;
GO

-- --------------------------------------------------------------------------
-- Granting the two consumers permission to read the views
-- --------------------------------------------------------------------------
--
-- The `orca_core` login owns the `core` schema — the setup scripts under
-- deploy/bootstrap make it the schema's authorised owner — so it owns these views
-- and may grant SELECT on them. It cannot grant anything on another service's
-- schema, and nothing here tries to.
--
-- Note that the bootstrap deliberately writes no blanket DENY anywhere, and this
-- is one of the reasons why: in SQL Server a DENY overrides a GRANT, so a DENY
-- added for tidiness would silently defeat exactly these two statements, and the
-- resulting failure would look like a bug in the view rather than a permissions
-- problem.
--
-- If the consuming logins do not exist, this migration fails on purpose rather
-- than skipping the grants. A database without them has not been through the
-- bootstrap, and a published view that no consumer can read is not published.
-- Skipping quietly would let orca-core start up green while orca-runtime refused
-- to start, complaining about a missing view that is in fact present — the worst
-- of both failures to diagnose.
IF DATABASE_PRINCIPAL_ID(N'orca_runtime') IS NULL OR DATABASE_PRINCIPAL_ID(N'orca_edge') IS NULL
	THROW 50101, 'orca_runtime and/or orca_edge do not exist in this database. Run deploy/bootstrap/run.sh before starting orca-core: a published view that no consumer can read is not published.', 1;
GO

GRANT SELECT ON topology_lane TO [orca_runtime], [orca_edge];
GO

GRANT SELECT ON topology_device TO [orca_runtime], [orca_edge];
GO

-- The orca-portal service will also need to read lanes once it is built, but it
-- is not granted access here. Portal is currently an empty skeleton — it is only
-- built out when work on the hosted cloud tier begins — and a permission granted
-- to a service that reads nothing is a permission nobody can justify when they
-- find it later. It arrives with portal's first real reader.
