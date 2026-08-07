-- orca-core · the first published views (ADR-009, §C1).
--
-- Mechanism 2 of §B4: a service that needs a lane's devices reads a view in its
-- own transaction — no network hop, no latency on the gate path, and no ability
-- to write. Core can restructure `lane` and `device` freely; a consumer can reach
-- only what core deliberately published.
--
-- NAMING — RULED, not chosen here. §C1 writes these as `topology.lane`, which
-- reads as an eighth schema, and §B5's schema list has no `topology`. The product
-- owner ruled (7 Aug 2026) that it is a PREFIX INSIDE `core`: `core.topology_lane`,
-- `core.topology_device`. No eighth schema, no eighth login, no eighth entry in
-- the bootstrap.
--
-- ⚠️ ONE THING THIS FILE DECIDES THAT THE ARCHITECTURE DOES NOT.
-- These views expose BOTH core's internal key (`lane_id`, `device_id`) and the
-- external identifier. §B8 says interfaces use the external id and internal joins
-- use the key, and a view is called a contract (ADR-009) — so exposing the
-- surrogate is arguable. But §C2 gives `runtime.execution` a `lane_id` that
-- admission "correlates, locks and indexes on", explicitly in contrast to the
-- denormalised `lane_code` the old model had, and core's view is the only source
-- of lane identity a consumer has. Publishing both is the choice that settles
-- nothing: a consumer may key on the surrogate or on the external id, and the day
-- the corpus says which, one column is dropped rather than a model rebuilt.
-- Reported in the phase report as a decision the specification did not dictate.
--
-- Retired rows are absent from both views. That is the whole reason the retirement
-- convention is safe to have: no consumer has to remember it.
--
-- The `GO` separators are not decoration. SQL Server requires CREATE VIEW to be
-- the first statement in its batch, and without an explicit terminator the two
-- views and the grants below arrive as one — which fails with the memorable and
-- unhelpful "Incorrect syntax near the keyword 'CREATE'".

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
-- The grants. This is ADR-009 working as designed.
-- --------------------------------------------------------------------------
--
-- `orca_core` owns the `core` schema (deploy/bootstrap sets AUTHORIZATION), so it
-- owns these views and may grant SELECT on them. It cannot grant anything on
-- another service's schema, and nothing here tries to.
--
-- deploy/bootstrap/V003 deliberately writes NO blanket DENY, and says why: DENY
-- overrides GRANT, so a DENY written for tidiness would silently defeat exactly
-- these two statements and the failure would look like a bug in the view.
--
-- Refusing rather than skipping when the principals are absent: a database where
-- they do not exist has not been bootstrapped, and a published view no consumer
-- can read is not published. Skipping quietly would let orca-core come up green
-- and leave runtime refusing to start with a message about a missing view that is
-- in fact present — the worst of both failures.
IF DATABASE_PRINCIPAL_ID(N'orca_runtime') IS NULL OR DATABASE_PRINCIPAL_ID(N'orca_edge') IS NULL
	THROW 50101, 'orca_runtime and/or orca_edge do not exist in this database. Run deploy/bootstrap/run.sh before starting orca-core: a published view that no consumer can read is not published.', 1;
GO

GRANT SELECT ON topology_lane TO [orca_runtime], [orca_edge];
GO

GRANT SELECT ON topology_device TO [orca_runtime], [orca_edge];
GO

-- orca-portal also reads `topology.lane` per §C1. It is not granted here: portal
-- stays the Phase 0 skeleton until cloud scope opens (register NEW-1b), and a
-- grant to a service that reads nothing is a permission nobody can justify when
-- they find it. It arrives with portal's first reader.
