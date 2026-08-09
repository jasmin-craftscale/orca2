-- How a piece of work that needs a human reaches the right human: the screens an
-- operator works in, and the rules that say which teams work which screens on
-- which lanes.
--
-- WHAT THIS IS FOR
-- Most trucks clear the gate without anybody touching them. Some do not — the
-- plate is unreadable, the booking does not match, the customer's system says no.
-- When that happens the process running the gate pauses at a wait state and a
-- work item is created for an operator to deal with.
--
-- To create that work item, the gate software has to answer two questions:
-- WHICH SCREEN does the operator see, and WHICH TEAMS may work it? `screen`
-- answers the first, `team_routing` the second, and the views at the bottom are
-- how the gate software reads them — it cannot read this schema's tables.
--
-- `screen` IS ONLY THE SCREEN'S IDENTITY
-- It carries the screen's name, the timings it is judged against, and the point
-- in a process design it belongs to. It does NOT carry the screen's layout, its
-- fields or its components. Those belong to the screen builder, which is not
-- built yet. What is here is what the gate needs in order to route work; the rest
-- arrives with the builder, and is deliberately not guessed at now.
--
-- HOW A SCREEN BINDS TO A PROCESS
-- Two columns together: the key of the process design, and the identifier of the
-- particular wait state inside it. That pair is the vocabulary the workflow
-- engine itself uses, and it is what the engine reports when a process pauses —
-- which is why routing keys on it rather than on anything of ORCA's own
-- invention. It stays stable across republication of a process because the
-- compiler that produces those designs is required to keep task identifiers
-- stable.
--
-- THE THREE TIMINGS
-- `below_expected_sec`, `expected_sec` and `max_sec` say how long this work
-- should take before it is shown as running late, and when it has breached
-- entirely. All three are nullable, and null means "use the installation-wide
-- default" — which is why V107__settings_workspace_audit.sql seeded the settings
-- registry with `EXPECTED_PROCESSING_TIME_SEC` and `MAX_PROCESSING_TIME_SEC`, and
-- why V110__settings_view.sql publishes that registry as a view. There is
-- deliberately no installation-wide default matching `below_expected_sec`: what
-- counts as unusually fast is only meaningful per screen.
--
-- TEAM ROUTING, AND WHAT PRIORITY MEANS
-- One row per team, screen and lane: "the day shift works unreadable plates on
-- lanes 1 to 4". Lower numbers are more urgent. Null means unprioritised, and
-- unprioritised work sorts after everything prioritised, oldest first within
-- each group. The old system stored the same null and then patched it at query
-- time by substituting minus one, which quietly made unprioritised work the MOST
-- urgent thing in the queue. That patch is gone; the ordering is stated instead.
--
-- The old system also had no constraint on the team-screen-lane triple, so the
-- same rule could exist several times over. Here it is unique. Its denormalized
-- site and area columns do not port either — they are reachable through the lane
-- — and the one copied value that remains is the scope column, backed by a
-- foreign key like every other scoped table in this schema.

CREATE TABLE screen (
	screen_id              BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_screen PRIMARY KEY,
	external_id            VARCHAR(64)   NOT NULL CONSTRAINT uq_screen_external_id UNIQUE,
	site_external_id       VARCHAR(64)   NOT NULL CONSTRAINT fk_screen_site REFERENCES site (external_id),
	name                   NVARCHAR(255) NOT NULL,
	-- Which wait state, in which process design, this screen fronts. These two
	-- names are the workflow engine's own — the key of the deployed process, and
	-- the identifier of the task inside it — so they match exactly what the engine
	-- reports when a process pauses.
	process_definition_key VARCHAR(255)  NOT NULL,
	node_reference         VARCHAR(255)  NOT NULL,
	-- How long this work should take, in seconds: faster than expected, expected,
	-- and the point at which it has breached. Null means the installation-wide
	-- default decides.
	below_expected_sec     INT           NULL CONSTRAINT ck_screen_below_expected
		CHECK (below_expected_sec IS NULL OR below_expected_sec > 0),
	expected_sec           INT           NULL CONSTRAINT ck_screen_expected
		CHECK (expected_sec IS NULL OR expected_sec > 0),
	max_sec                INT           NULL CONSTRAINT ck_screen_max
		CHECK (max_sec IS NULL OR max_sec > 0),
	retired_at             DATETIME2(3)  NULL,
	created_at             DATETIME2(3)  NOT NULL CONSTRAINT df_screen_created_at DEFAULT SYSUTCDATETIME()
);

-- At most one active screen per wait state per site. Routing has to resolve to
-- exactly one screen; without this the query would silently pick whichever row
-- came back first, and an operator would get a different screen depending on
-- nothing. The constraint is what makes "one screen" a fact rather than an
-- assumption the query makes.
--
-- It leads with the site column, so it is also the index every scoped read of
-- this table uses. The build check `ScopeIndexRule` reads this file and fails
-- when a table carrying `site_external_id` has no index leading with it.
CREATE UNIQUE INDEX ux_screen_site_node
	ON screen (site_external_id, process_definition_key, node_reference)
	WHERE retired_at IS NULL;

CREATE TABLE team_routing (
	team_routing_id  BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_team_routing PRIMARY KEY,
	external_id      VARCHAR(64)  NOT NULL CONSTRAINT uq_team_routing_external_id UNIQUE,
	site_external_id VARCHAR(64)  NOT NULL CONSTRAINT fk_team_routing_site REFERENCES site (external_id),
	team_id          BIGINT       NOT NULL CONSTRAINT fk_team_routing_team REFERENCES team (team_id),
	screen_id        BIGINT       NOT NULL CONSTRAINT fk_team_routing_screen REFERENCES screen (screen_id),
	lane_id          BIGINT       NOT NULL CONSTRAINT fk_team_routing_lane REFERENCES lane (lane_id),
	priority         INT          NULL CONSTRAINT ck_team_routing_priority
		CHECK (priority IS NULL OR priority >= 0),
	retired_at       DATETIME2(3) NULL,
	created_at       DATETIME2(3) NOT NULL CONSTRAINT df_team_routing_created_at DEFAULT SYSUTCDATETIME()
);

-- One rule per team, screen and lane, among active rules. The old system had no
-- constraint here at all, so duplicates were possible.
--
-- Filtered to unretired rows because of how a rule set is replaced: an
-- administrator submits the whole set they want, the old rows are retired and the
-- new ones inserted. Nothing is ever deleted — retirement is how this platform
-- removes things, and the shared data-access code has no delete at all — so
-- without the filter a rule could never be reinstated after being withdrawn.
CREATE UNIQUE INDEX ux_team_routing_tuple ON team_routing (team_id, screen_id, lane_id)
	WHERE retired_at IS NULL;

-- The one read this table exists to serve: "which teams work this screen on this
-- lane?", asked every time a work item is created. Site first because the
-- caller's scope fixes it, then the two columns the question actually filters on,
-- and the answers carried along in the index itself so the rows never have to be
-- fetched.
CREATE INDEX ix_team_routing_scope ON team_routing (site_external_id, screen_id, lane_id)
	INCLUDE (team_id, priority);
GO

-- --------------------------------------------------------------------------
-- The published views. Other services cannot read this schema's tables — each
-- service logs in as itself and is granted access only to its own schema — so
-- everything they need is published as a read-only view and granted to them by
-- name. They then read it inside their own transaction: no call to orca-core, no
-- network delay on the path a truck is waiting on, and no way to write anything.
--
-- Same discipline as the first published views: if the login that is supposed to
-- read these does not exist, the migration fails on purpose rather than skipping
-- the grants. A database without that login has not been through the setup under
-- deploy/bootstrap, and a published view no consumer can read is not published.
-- Skipping quietly would leave orca-core starting up green while the service that
-- needs these refused to start, complaining about a missing view that is in fact
-- present — the worst of both failures to diagnose.
--
-- ⚠️ The `GO` separators below are required, not decoration. SQL Server demands
-- that CREATE VIEW be the first statement in its batch, and without an explicit
-- separator these views and their grants arrive as one batch — which fails with
-- the memorable but unhelpful "Incorrect syntax near the keyword 'CREATE'".
-- --------------------------------------------------------------------------
IF DATABASE_PRINCIPAL_ID(N'orca_runtime') IS NULL
	THROW 50109, 'orca_runtime does not exist in this database. Run deploy/bootstrap/run.sh before starting orca-core: a published view that no consumer can read is not published.', 1;
GO

CREATE VIEW topology_screen AS
SELECT
	sc.screen_id,
	sc.external_id          AS screen_external_id,
	sc.name                 AS screen_name,
	sc.process_definition_key,
	sc.node_reference,
	sc.below_expected_sec,
	sc.expected_sec,
	sc.max_sec,
	s.external_id           AS site_external_id
FROM screen sc
	JOIN site s ON s.external_id = sc.site_external_id
WHERE sc.retired_at IS NULL
  AND s.retired_at IS NULL;
GO

-- One row per routing rule, flattened into the vocabulary the gate software
-- actually has in hand at the moment it needs this: the wait state the engine
-- just reported, and the lane's external id. That flattening is the point —
-- deciding who may work a new item is one read of one view, on the path a truck
-- is waiting on, rather than a join across four tables it is not allowed to see.
CREATE VIEW topology_team_routing AS
SELECT
	tr.site_external_id,
	t.external_id           AS team_external_id,
	t.name                  AS team_name,
	t.handling_method,
	sc.external_id          AS screen_external_id,
	sc.process_definition_key,
	sc.node_reference,
	l.external_id           AS lane_external_id,
	tr.priority
FROM team_routing tr
	JOIN team t ON t.team_id = tr.team_id
	JOIN screen sc ON sc.screen_id = tr.screen_id
	JOIN lane l ON l.lane_id = tr.lane_id
WHERE tr.retired_at IS NULL
  AND t.retired_at IS NULL
  AND sc.retired_at IS NULL
  AND l.retired_at IS NULL;
GO

CREATE VIEW topology_team_member AS
SELECT
	tm.site_external_id,
	t.external_id           AS team_external_id,
	u.external_id           AS user_external_id
FROM team_member tm
	JOIN team t ON t.team_id = tm.team_id
	JOIN user_account u ON u.user_id = tm.user_id
WHERE tm.retired_at IS NULL
  AND t.retired_at IS NULL
  AND u.retired_at IS NULL;
GO

-- The operator directory: how the gate software turns the subject in a signed-in
-- operator's token into the platform user who is acting.
--
-- It carries `config_realm`, which is a constant, because user accounts belong to
-- the installation as a whole and have no site dimension to be scoped by. The
-- shared data-access code has no unscoped read at all, so a view with no site has
-- to offer some other dimension to read under — and reading installation-wide
-- data then stays a declared act rather than a hole in the mechanism.
CREATE VIEW topology_operator AS
SELECT
	u.config_realm,
	u.external_id           AS user_external_id,
	u.keycloak_subject,
	u.display_name
FROM user_account u
WHERE u.retired_at IS NULL;
GO

GRANT SELECT ON topology_screen TO [orca_runtime];
GO
GRANT SELECT ON topology_team_routing TO [orca_runtime];
GO
GRANT SELECT ON topology_team_member TO [orca_runtime];
GO
GRANT SELECT ON topology_operator TO [orca_runtime];
GO
