-- Demo data, part two: the customer system the demo process calls out to, and
-- what its answers are taken to mean.
--
-- ⚠️ WHY THIS IS A SEPARATE FILE FROM demo-site.sql, RUN AS A DIFFERENT LOGIN
-- These rows live in the gate service's schema, and the configuration service's
-- login has no permission to write there. That is the platform's data-ownership
-- boundary working exactly as designed, not an inconvenience to be worked around:
-- if a single script could write both schemas, the database would not be
-- enforcing the confinement that deploy/bootstrap/verify-isolation.sh goes on to
-- assert.
--
-- Re-running changes nothing except the endpoint address, which follows whatever
-- the deployment's environment file currently says.

SET NOCOUNT ON;
GO

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

USE [orca];
GO

-- The customer's terminal operating system — the software that knows which
-- containers may be collected — as far as this demo is concerned: a canned-answer
-- stub under deploy/stubs/tos.
--
-- Its address is passed in by deploy/demo/seed.sh so that the deployment's
-- environment file stays the one place the port is written down.
--
-- ⚠️ THE DEADLINE IS A LOCAL CHOICE, NOT A PLATFORM CONSTANT. Every external call
-- in this platform must HAVE a deadline; nothing anywhere says how long it should
-- be, and nothing should. A terminal operating system that goes slow at shift
-- change and one that never does want different numbers, and picking belongs to
-- whoever runs the site.
IF NOT EXISTS (SELECT 1 FROM runtime.connector_config
               WHERE site_external_id = N'SITE-DEMO' AND connector_name = N'tos')
	INSERT INTO runtime.connector_config
		(site_external_id, connector_name, base_url, request_path, deadline_ms, is_enabled)
	VALUES (N'SITE-DEMO', N'tos', N'$(tosUrl)', N'/tos/v1/visits', 4000, 1);
GO

UPDATE runtime.connector_config SET base_url = N'$(tosUrl)'
WHERE site_external_id = N'SITE-DEMO' AND connector_name = N'tos' AND base_url <> N'$(tosUrl)';
GO

-- What each answer from that system means, as a word the process can branch on.
--
-- ⚠️ ONE ROW, AND ONLY ONE, ON PURPOSE. The demo process sends APPROVED to the
-- barrier and everything else to a human. A site that wants 409 to mean something
-- other than "a person looks at it" ADDS A ROW HERE; it does not wait for a code
-- change. That is the whole reason this mapping is data.
--
-- A status with no row becomes the literal word `HTTP_<status>` — HTTP_503, say —
-- which no branch matches, so the process takes its default path to a human. An
-- answer nobody wrote a branch for must never become an implicit approval and
-- lift a barrier.
IF NOT EXISTS (SELECT 1 FROM runtime.connector_route
               WHERE site_external_id = N'SITE-DEMO' AND connector_name = N'tos' AND http_status = 200)
	INSERT INTO runtime.connector_route (site_external_id, connector_name, http_status, outcome)
	VALUES (N'SITE-DEMO', N'tos', 200, N'APPROVED');
GO

PRINT 'Connector configuration present:';
GO
SELECT connector_name, base_url, request_path, deadline_ms, is_enabled
FROM runtime.connector_config WHERE site_external_id = N'SITE-DEMO';
GO
SELECT connector_name, http_status, outcome
FROM runtime.connector_route WHERE site_external_id = N'SITE-DEMO';
GO
