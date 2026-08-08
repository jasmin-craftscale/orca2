-- ORCA demo data — one site, one area, one lane, one camera, one barrier.
--
-- WHY THIS IS NOT A MIGRATION, since the phase plan named two other vehicles and
-- both are defeated by mechanisms this repository already has:
--
--   * A VERSIONED migration in a `local`-only Flyway location runs once and is
--     recorded. Start the same database without `local` afterwards and
--     `validate-on-migrate: true` reports an applied migration that no longer
--     resolves — a real guarantee, tripped by demo data.
--   * A REPEATABLE migration does not help. Flyway checksums the raw file before
--     placeholder substitution, so flipping a `${demoSeed}` placeholder does not
--     make it re-run: the seed would land only if `local` happened to be active
--     the very first time the database was migrated.
--   * A STARTUP SEEDER in Java cannot be written yet. `ScopeSeamRule` forbids a
--     service class from touching `DataSource`, `JdbcTemplate` or a `Connection`,
--     and the seam has no write surface until WP2. Fighting the check to seed
--     demo data would be the wrong way round.
--
-- So the seed is an explicit act instead: nothing runs it but a person, which is
-- a stronger guarantee than `local`-profile-only ever was — there is no profile,
-- no environment variable and no ordering accident that puts demo rows on a
-- customer site.
--
-- Idempotent: re-running changes nothing. Run it with deploy/demo/seed.sh.
--
-- It runs as `orca_core`, not as `sa`, so it uses only rights the service itself
-- has. Names are schema-qualified because this is not a migration and should not
-- lean on the DEFAULT_SCHEMA mechanism ADR-004 rests on.

SET NOCOUNT ON;
GO

-- ⚠️ REQUIRED, not tidiness. `site` carries a FILTERED unique index (at most one
-- primary site), and SQL Server refuses any INSERT or UPDATE on such a table
-- unless QUOTED_IDENTIFIER is ON. The JDBC driver sets it on, so migrations and
-- services never meet this; sqlcmd sets it OFF for backward compatibility, so a
-- script run through sqlcmd fails with "INSERT failed because the following SET
-- options have incorrect settings" and no hint about which table.
--
-- seed.sh also passes -I, which is the documented way to say the same thing.
-- Both, because whichever one someone drops, the other still holds.
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

USE [orca];
GO

-- The installation's own site. `is_primary = 1` is guarded by a filtered unique
-- index, so if this database already has a primary site the insert is refused
-- rather than silently creating a second one.
IF NOT EXISTS (SELECT 1 FROM core.site WHERE external_id = N'SITE-DEMO')
	INSERT INTO core.site (external_id, code, name, is_primary)
	VALUES (N'SITE-DEMO', N'DEMO', N'Demo Terminal', 1);
GO

IF NOT EXISTS (SELECT 1 FROM core.area WHERE external_id = N'AREA-DEMO-GATE')
	INSERT INTO core.area (external_id, site_id, code, name)
	SELECT N'AREA-DEMO-GATE', s.site_id, N'GATE', N'Main Gate'
	FROM core.site s WHERE s.external_id = N'SITE-DEMO';
GO

-- `device_host_url` now points at WP7's device-host stub, and the address comes in
-- as a sqlcmd variable from seed.sh rather than being written here — it is
-- .env's ORCA_DEVICE_HOST_STUB_PORT, and two copies of a port number is one copy
-- that eventually disagrees.
--
-- It is a LOCALHOST url because edge runs on the developer's machine and the stub
-- runs in the compose network; the container name would resolve only from inside
-- another container.
IF NOT EXISTS (SELECT 1 FROM core.lane WHERE external_id = N'LANE-DEMO-01')
	INSERT INTO core.lane (external_id, area_id, code, name, device_host_url, is_out_of_service, lane_priority)
	SELECT N'LANE-DEMO-01', a.area_id, N'L01', N'Lane 1', N'$(deviceHostUrl)', 0, 0
	FROM core.area a WHERE a.external_id = N'AREA-DEMO-GATE';
GO

-- Re-running the seed after changing the stub's port should move the lane, not
-- leave it pointing at a port nothing listens on. The insert above is guarded by
-- existence; this is the part that has to be idempotent by UPDATE.
UPDATE core.lane SET device_host_url = N'$(deviceHostUrl)'
WHERE external_id = N'LANE-DEMO-01'
  AND (device_host_url IS NULL OR device_host_url <> N'$(deviceHostUrl)');
GO

-- The device-type vocabulary was provisional in Phase 1 ('LPR_CAMERA' and
-- 'BARRIER' as free strings); Phase 2's WP3 settled it with the seeded 1.x
-- catalog (core.device_type). The camera keeps its word; the barrier is the
-- catalog's GATE_ARM. Devices now carry their site scope explicitly.
IF NOT EXISTS (SELECT 1 FROM core.device WHERE external_id = N'DEV-DEMO-CAMERA')
	INSERT INTO core.device (external_id, lane_id, site_external_id, device_type_id, name, address)
	SELECT N'DEV-DEMO-CAMERA', l.lane_id, N'SITE-DEMO',
		(SELECT device_type_id FROM core.device_type WHERE code = 'LPR_CAMERA'),
		N'Lane 1 plate camera', NULL
	FROM core.lane l WHERE l.external_id = N'LANE-DEMO-01';
GO

IF NOT EXISTS (SELECT 1 FROM core.device WHERE external_id = N'DEV-DEMO-BARRIER')
	INSERT INTO core.device (external_id, lane_id, site_external_id, device_type_id, name, address)
	SELECT N'DEV-DEMO-BARRIER', l.lane_id, N'SITE-DEMO',
		(SELECT device_type_id FROM core.device_type WHERE code = 'GATE_ARM'),
		N'Lane 1 barrier', NULL
	FROM core.lane l WHERE l.external_id = N'LANE-DEMO-01';
GO

PRINT 'Demo data present:';
GO
SELECT s.external_id AS site, a.external_id AS area, l.external_id AS lane,
       (SELECT COUNT(*) FROM core.device d WHERE d.lane_id = l.lane_id) AS devices
FROM core.lane l
	JOIN core.area a ON a.area_id = l.area_id
	JOIN core.site s ON s.site_id = a.site_id
WHERE l.external_id = N'LANE-DEMO-01';
GO
