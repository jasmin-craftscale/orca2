-- orca-core · Phase 3 WP2 — the screen IDENTITY and the team routing rules,
-- plus the views runtime reads them through.
--
-- The routing rules were deferred from Phase 2 WP2 because they reference a
-- screen (core-config sheet §2: "routing rules land with the work-items/screens
-- phase") — they come home here, because work items need them.
--
--   * screen — the IDENTITY ONLY (work-items sheet §5): external id, name, the
--     three SLA thresholds, and the node it binds to. NOT the renderer, NOT the
--     component tree — those are the builder-developer's, deferred. 1.x
--     `manual_inputs` translated; the node reference becomes the BPMN task
--     definition key plus the process definition key, which is what the engine
--     actually parks on (profile §8a: the compiler keeps task ids stable).
--     The three thresholds are per-screen and nullable — NULL falls back to the
--     two global settings V107 seeded (EXPECTED_PROCESSING_TIME_SEC,
--     MAX_PROCESSING_TIME_SEC; there is deliberately no global below_expected).
--   * team_routing — 1.x `group_configuration_mappings`: one row per
--     team x screen x lane, priority nullable. The tuple is UNIQUE here —
--     1.x had no constraint at all (sheet: duplicates possible). The
--     denormalized site/area ids do not port (derivable; rule 6) — the scope
--     column is the one denormalized value, FK-backed like every Phase 2 table.
--     Priority: lower = more urgent, NULL = unprioritised (ordered after the
--     prioritised, FIFO within — the grid ordering the work-items sheet §5
--     states; the 1.x COALESCE(priority,-1) patch dies here).
--
-- The views are ADR-009 contracts like topology_lane: runtime evaluates
-- eligibility and thresholds through them in its own transaction, and can
-- reach nothing core has not deliberately published.

CREATE TABLE screen (
	screen_id              BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_screen PRIMARY KEY,
	external_id            VARCHAR(64)   NOT NULL CONSTRAINT uq_screen_external_id UNIQUE,
	site_external_id       VARCHAR(64)   NOT NULL CONSTRAINT fk_screen_site REFERENCES site (external_id),
	name                   NVARCHAR(255) NOT NULL,
	-- The node binding: which wait state in which process design this screen
	-- fronts. The engine's own vocabulary (profile §8a), stable across
	-- republication by the compiler's discipline.
	process_definition_key VARCHAR(255)  NOT NULL,
	node_reference         VARCHAR(255)  NOT NULL,
	-- Per-screen SLA thresholds, seconds. NULL = the global setting decides.
	below_expected_sec     INT           NULL CONSTRAINT ck_screen_below_expected
		CHECK (below_expected_sec IS NULL OR below_expected_sec > 0),
	expected_sec           INT           NULL CONSTRAINT ck_screen_expected
		CHECK (expected_sec IS NULL OR expected_sec > 0),
	max_sec                INT           NULL CONSTRAINT ck_screen_max
		CHECK (max_sec IS NULL OR max_sec > 0),
	retired_at             DATETIME2(3)  NULL,
	created_at             DATETIME2(3)  NOT NULL CONSTRAINT df_screen_created_at DEFAULT SYSUTCDATETIME()
);

-- One active screen identity per node per site — routing must resolve to ONE
-- screen, and this is the constraint that makes that a fact rather than a
-- query's assumption. Also the scope-leading index (ScopeIndexRule reads this).
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

-- The tuple 1.x never constrained, unique among ACTIVE rules. Replacing a
-- team's rule set retires the old rows and inserts the new — the same
-- declarative-set shape as team_member, and the seam's shape (it has no
-- delete; retirement is the platform's removal).
CREATE UNIQUE INDEX ux_team_routing_tuple ON team_routing (team_id, screen_id, lane_id)
	WHERE retired_at IS NULL;

-- The evaluation read: "which teams for this screen on this lane" — scope first,
-- then the join columns runtime filters by (ScopeIndexRule reads this file).
CREATE INDEX ix_team_routing_scope ON team_routing (site_external_id, screen_id, lane_id)
	INCLUDE (team_id, priority);
GO

-- --------------------------------------------------------------------------
-- The published views (ADR-009). Same discipline as V102: the consumer
-- principal must exist, or the migration refuses — a published view no
-- consumer can read is not published.
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

-- One row per (team x screen x lane) rule, denormalised to the vocabulary
-- runtime evaluates in: the screen's node binding and the lane's external id,
-- so eligibility at work-item creation is ONE read of ONE view.
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

-- The operator directory: how runtime resolves an identity-provider subject to
-- the platform user acting. Users are installation-realm (phase-2 §5.1), so
-- this view carries config_realm and is read under that dimension — the seam
-- has no unscoped read, and this is the declared way to read installation data.
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
