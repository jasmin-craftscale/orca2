-- Demo data for a local development stack: one site, one area, one lane, one
-- camera, one barrier — plus, further down, an operator, a team and the routing
-- that lets a truck be sent to a human.
--
-- HOW TO RUN IT
--     deploy/demo/seed.sh
-- Re-running changes nothing that is already correct. It runs as the
-- configuration service's own login rather than as an administrator, so it can
-- only do what that service could do for itself.
--
-- ⚠️ WHY THIS IS A SCRIPT SOMEBODY RUNS, AND NOT A MIGRATION
-- Nothing but a person runs this. There is no profile, no environment variable
-- and no ordering accident that can put demo rows into a customer's database.
-- That is a stronger guarantee than any of the automatic alternatives, and each
-- of those was tried against a real mechanism in this repository and lost:
--
--   * A numbered migration in a location active only in local development runs
--     once and is RECORDED as applied. Start the same database again without that
--     location and Flyway reports an applied migration it can no longer find —
--     which is a real and valuable protection, tripped over by demo data.
--   * A repeatable migration does not help either. Flyway takes its checksum of
--     the raw file BEFORE substituting placeholders, so a placeholder that
--     switches the seed on and off never changes the checksum and never causes a
--     re-run. The rows would land only if local development happened to be active
--     the very first time that database was migrated.
--   * A seeder written in Java cannot exist. A build check forbids service code
--     from touching a data source, a JDBC template or a raw connection — every
--     write goes through the shared scoping code — and fighting that check in
--     order to insert demo data would be exactly the wrong way round.
--
-- WHY THE TABLE NAMES ARE SCHEMA-QUALIFIED HERE
-- Because this is not a migration. Migrations deliberately write unqualified
-- names and rely on each service login's default schema to place them; that
-- mechanism is load-bearing and a script run by hand should not lean on it.

SET NOCOUNT ON;
GO

-- ⚠️ REQUIRED, not tidiness. `site` carries a FILTERED unique index (at most one
-- primary site), and SQL Server refuses any INSERT or UPDATE on such a table
-- unless QUOTED_IDENTIFIER is ON. The JDBC driver sets it on, so migrations and
-- services never meet this; sqlcmd sets it OFF for backward compatibility, so a
-- script run through sqlcmd fails with "INSERT failed because the following SET
-- options have incorrect settings" and no hint about which table.
--
-- deploy/demo/seed.sh also passes the command-line flag that means the same
-- thing. Both are here on purpose: whichever one somebody drops, the other still
-- holds.
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

-- The lane's device-host address points at the stand-in device host that the
-- local stack runs, in place of real hardware.
--
-- The address is passed in as a variable by deploy/demo/seed.sh rather than
-- written here, because the port it contains is already written down once in the
-- deployment's environment file. Two copies of a port number is one copy that
-- eventually disagrees with the other.
--
-- ⚠️ It is a LOCALHOST address, not a container name. The hardware-facing service
-- normally runs on the developer's own machine while the stub runs inside the
-- container network, and a container name resolves only from inside another
-- container.
IF NOT EXISTS (SELECT 1 FROM core.lane WHERE external_id = N'LANE-DEMO-01')
	INSERT INTO core.lane (external_id, area_id, code, name, device_host_url, is_out_of_service, lane_priority)
	SELECT N'LANE-DEMO-01', a.area_id, N'L01', N'Lane 1', N'$(deviceHostUrl)', 0, 0
	FROM core.area a WHERE a.external_id = N'AREA-DEMO-GATE';
GO

-- The insert above only runs when the lane does not exist yet, so on a second run
-- it does nothing — including nothing about a port that has since changed.
-- Somebody who changes the stub's port and re-seeds expects the lane to follow,
-- not to keep pointing at a port nothing is listening on. Hence this update.
UPDATE core.lane SET device_host_url = N'$(deviceHostUrl)'
WHERE external_id = N'LANE-DEMO-01'
  AND (device_host_url IS NULL OR device_host_url <> N'$(deviceHostUrl)');
GO

-- Device types are now chosen from the catalog in `core.device_type` rather than
-- written as free text. When these two devices were first seeded the vocabulary
-- was provisional — 'LPR_CAMERA' and 'BARRIER' as plain strings — and it was
-- later settled by translating the catalog from the system in production today.
-- The camera kept its word; the barrier is that catalog's GATE_ARM. Each device
-- also states its site explicitly, as every scoped row in that schema does.
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

-- --------------------------------------------------------------------------
-- The clerk's half of the demo: one operator, one team, the screen that fronts
-- the gate process's manual-handling wait state, and the routing rule that sends
-- that work to that team.
--
-- Without these four rows a truck that needs a human decision has nowhere to go.
-- With them, the whole loop can be watched live — a truck arrives, hits an
-- exception, becomes a work item in a queue, an operator completes it, and the
-- parked process carries on and lifts the barrier.
--
-- ⚠️ THE OPERATOR IS SEEDED WITH NO IDENTITY-PROVIDER SUBJECT, AND THAT IS
-- DELIBERATE. The local identity provider has no human accounts in it, only
-- convenience clients for development, so there is no subject to write here yet.
-- The walkthrough links whatever token it obtains to this row with a single,
-- visible UPDATE — which is also the shortest demonstration of the operator
-- directory doing exactly the job it exists for. The exact step is written out in
-- docs/phase-3-report.md, in its walkthrough of the live clerk loop.
-- --------------------------------------------------------------------------
IF NOT EXISTS (SELECT 1 FROM core.role WHERE external_id = N'rol-demo-clerk')
	INSERT INTO core.role (external_id, name, description)
	VALUES (N'rol-demo-clerk', N'Demo Clerk', N'Demo-only role for the clerk workflow walkthrough');
GO

IF NOT EXISTS (SELECT 1 FROM core.user_account WHERE external_id = N'usr-demo-clerk')
	INSERT INTO core.user_account (external_id, display_name, email, role_id)
	SELECT N'usr-demo-clerk', N'Demo Clerk', N'demo-clerk@example.invalid', r.role_id
	FROM core.role r WHERE r.external_id = N'rol-demo-clerk';
GO

IF NOT EXISTS (SELECT 1 FROM core.team WHERE external_id = N'team-demo-clerks')
	INSERT INTO core.team (external_id, site_external_id, name, handling_method)
	VALUES (N'team-demo-clerks', N'SITE-DEMO', N'Demo Clerks', N'PROMPT');
GO

IF NOT EXISTS (SELECT 1 FROM core.team_member tm
		JOIN core.team t ON t.team_id = tm.team_id
		WHERE t.external_id = N'team-demo-clerks' AND tm.retired_at IS NULL)
	INSERT INTO core.team_member (team_id, user_id, site_external_id)
	SELECT t.team_id, u.user_id, N'SITE-DEMO'
	FROM core.team t, core.user_account u
	WHERE t.external_id = N'team-demo-clerks' AND u.external_id = N'usr-demo-clerk';
GO

-- The screen that fronts the gate process's manual-handling wait state. The two
-- names below — the process design's key and the identifier of the task inside it
-- — are the workflow engine's own vocabulary, and must match what the process
-- definition actually declares or no work item will ever route to this screen.
--
-- The maximum of 60 seconds arms the overdue timer at one minute: short enough
-- that a breach can be watched live during a walkthrough, long enough that
-- somebody demonstrating the happy path can finish the item first.
IF NOT EXISTS (SELECT 1 FROM core.screen WHERE external_id = N'scr-demo-manual')
	INSERT INTO core.screen (external_id, site_external_id, name,
		process_definition_key, node_reference, expected_sec, max_sec)
	VALUES (N'scr-demo-manual', N'SITE-DEMO', N'Manual handling',
		N'gate-visit', N'manualInput', 30, 60);
GO

IF NOT EXISTS (SELECT 1 FROM core.team_routing tr
		JOIN core.team t ON t.team_id = tr.team_id
		WHERE t.external_id = N'team-demo-clerks' AND tr.retired_at IS NULL)
	INSERT INTO core.team_routing (external_id, site_external_id, team_id, screen_id, lane_id, priority)
	SELECT N'rt-demo-clerks-manual', N'SITE-DEMO', t.team_id, sc.screen_id, l.lane_id, 1
	FROM core.team t, core.screen sc, core.lane l
	WHERE t.external_id = N'team-demo-clerks'
	  AND sc.external_id = N'scr-demo-manual'
	  AND l.external_id = N'LANE-DEMO-01';
GO

PRINT 'Demo data present:';
GO
SELECT s.external_id AS site, a.external_id AS area, l.external_id AS lane,
       (SELECT COUNT(*) FROM core.device d WHERE d.lane_id = l.lane_id) AS devices,
       (SELECT COUNT(*) FROM core.screen) AS screens,
       (SELECT COUNT(*) FROM core.team_routing tr WHERE tr.retired_at IS NULL) AS routing_rules
FROM core.lane l
	JOIN core.area a ON a.area_id = l.area_id
	JOIN core.site s ON s.site_id = a.site_id
WHERE l.external_id = N'LANE-DEMO-01';
GO
