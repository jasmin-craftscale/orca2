-- ORCA demo data, part two — the connector the demo process calls, and how its
-- answers are routed.
--
-- Separate from demo-site.sql, and run as a DIFFERENT LOGIN, because these rows
-- live in the `runtime` schema and `orca_core` cannot write it. That is ADR-004
-- working rather than an inconvenience: if one seed script could write both, the
-- database would not be enforcing the confinement `verify-isolation.sh` asserts.
--
-- Idempotent: re-running changes nothing but the endpoint, which follows .env.

SET NOCOUNT ON;
GO

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

USE [orca];
GO

-- The Terminal Operating System, as far as the demo is concerned: the WireMock
-- stub in deploy/stubs/tos. The URL comes in from seed.sh so that .env stays the
-- one place the port is written down.
--
-- ⚠️ `deadline_ms` is a LOCAL value. §B8 requires a deadline and says nothing
-- about its size, and it should not: a terminal operating system that is
-- routinely slow at shift change and one that never is want different numbers,
-- and choosing belongs to whoever runs the site.
IF NOT EXISTS (SELECT 1 FROM runtime.connector_config
               WHERE site_external_id = N'SITE-DEMO' AND connector_name = N'tos')
	INSERT INTO runtime.connector_config
		(site_external_id, connector_name, base_url, request_path, deadline_ms, is_enabled)
	VALUES (N'SITE-DEMO', N'tos', N'$(tosUrl)', N'/tos/v1/visits', 4000, 1);
GO

UPDATE runtime.connector_config SET base_url = N'$(tosUrl)'
WHERE site_external_id = N'SITE-DEMO' AND connector_name = N'tos' AND base_url <> N'$(tosUrl)';
GO

-- HTTP status -> the branch discriminator the process routes on.
--
-- 200 is the ONLY row here, deliberately. `gate-visit`'s gateway sends APPROVED
-- to the barrier and everything else to a human, and a site that wants 409 to mean
-- something other than "a human looks at it" adds a row — it does not get code
-- changed. A status with no row becomes `HTTP_<status>`, which no branch matches,
-- so the default flow takes it to a human: an answer nobody wrote a branch for
-- must never become an implicit approval.
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
