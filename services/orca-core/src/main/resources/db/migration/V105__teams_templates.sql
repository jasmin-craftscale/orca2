-- orca-core · WP2 — teams and shift/break templates.
--
-- Translated from 1.x's "groups" cluster per docs/core-config-schema-from-1x.md
-- §2. What changed in translation, and why (report §WP2 carries the reasoning):
--
--   * group_handling_methods — a 2-row table seeded forever (Push, Prompt) —
--     becomes a constrained column, exactly as the sheet directs. One casing
--     (rule 5): UPPERCASE, matching the platform's other wire enums.
--   * team gains an EXPLICIT site scope. 1.x group_details had no site_id at
--     all — site was implied transitively. §C1's world model makes teams
--     site-scoped, so the scope column is stated, FK'd and index-led.
--   * shift_templates: 1.x stored a DURATION in a TIME column and carried an
--     is_overnight flag nothing kept consistent (two Go fields even mapped to
--     one time_zone_id column). Here: duration is NOT STORED AT ALL (derivable
--     from start/end/overnight — storing it was the defect); is_overnight IS
--     stored but a CHECK ties it to the times, so the flag can never contradict
--     them. end <= start means the shift crosses midnight; start = end is the
--     24-hour shift.
--   * time_zones — 1.x's 4-row US-only table — does not port (sheet §2): the
--     template carries a validated IANA zone id, checked against the JVM's tz
--     database in the service layer.
--   * break_timings: NOT NULL TIME + NOT NULL minutes — the 1.x columns were
--     nullable only because the GORM tags were malformed; the INTENT ports
--     (rule 4).
--   * Templates are installation-realm, not site-scoped. 1.x had a NULLABLE
--     site_id on both — and a nullable scope column is invisible to every
--     site-scoped seam read, which is a trap, not a feature. A template is a
--     catalog the site-scoped team points at. Recorded as a translation
--     decision in the report.
--   * group_configuration_mappings (team routing rules) — DEFERRED, deliberately
--     (sheet §2): screens are a later phase's artifact, and topology.team_routing
--     becomes real there. Named here so nobody reads the absence as forgotten.

-- --------------------------------------------------------------------------
-- shift_template
-- --------------------------------------------------------------------------
CREATE TABLE shift_template (
	shift_template_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_shift_template PRIMARY KEY,
	external_id       VARCHAR(64)   NOT NULL CONSTRAINT uq_shift_template_external_id UNIQUE,
	config_realm      VARCHAR(16)   NOT NULL
		CONSTRAINT df_shift_template_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_shift_template_realm CHECK (config_realm = 'INSTALLATION'),
	name              NVARCHAR(100) NOT NULL,
	-- IANA zone id, validated against the JVM tz database on write. One
	-- reference — the 1.x double-mapped column does not port.
	time_zone         VARCHAR(64)   NOT NULL,
	start_time        TIME(0)       NOT NULL,
	end_time          TIME(0)       NOT NULL,
	is_overnight      BIT           NOT NULL,
	retired_at        DATETIME2(3)  NULL,
	created_at        DATETIME2(3)  NOT NULL CONSTRAINT df_shift_template_created_at DEFAULT SYSUTCDATETIME(),
	-- The flag cannot contradict the times: end at or before start IS the
	-- overnight shape (equal = the 24-hour shift), end after start is not.
	CONSTRAINT ck_shift_template_overnight CHECK (
		(is_overnight = 1 AND end_time <= start_time) OR
		(is_overnight = 0 AND end_time > start_time))
);

CREATE UNIQUE INDEX ux_shift_template_name ON shift_template (name) WHERE retired_at IS NULL;

-- --------------------------------------------------------------------------
-- break_template + break_timing
-- --------------------------------------------------------------------------
CREATE TABLE break_template (
	break_template_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_break_template PRIMARY KEY,
	external_id       VARCHAR(64)   NOT NULL CONSTRAINT uq_break_template_external_id UNIQUE,
	config_realm      VARCHAR(16)   NOT NULL
		CONSTRAINT df_break_template_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_break_template_realm CHECK (config_realm = 'INSTALLATION'),
	name              NVARCHAR(100) NOT NULL,
	description       NVARCHAR(500) NULL,
	retired_at        DATETIME2(3)  NULL,
	created_at        DATETIME2(3)  NOT NULL CONSTRAINT df_break_template_created_at DEFAULT SYSUTCDATETIME()
);

CREATE UNIQUE INDEX ux_break_template_name ON break_template (name) WHERE retired_at IS NULL;

CREATE TABLE break_timing (
	break_timing_id   BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_break_timing PRIMARY KEY,
	break_template_id BIGINT       NOT NULL CONSTRAINT fk_break_timing_template
		REFERENCES break_template (break_template_id),
	config_realm      VARCHAR(16)  NOT NULL
		CONSTRAINT df_break_timing_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_break_timing_realm CHECK (config_realm = 'INSTALLATION'),
	-- The 1.x intent, not the 1.x accident (rule 4): both NOT NULL.
	break_start_time  TIME(0)      NOT NULL,
	duration_minutes  INT          NOT NULL CONSTRAINT ck_break_timing_duration CHECK (duration_minutes > 0),
	retired_at        DATETIME2(3) NULL,
	created_at        DATETIME2(3) NOT NULL CONSTRAINT df_break_timing_created_at DEFAULT SYSUTCDATETIME()
);

-- Two breaks cannot start at the same instant in one template.
CREATE UNIQUE INDEX ux_break_timing ON break_timing (break_template_id, break_start_time)
	WHERE retired_at IS NULL;

-- --------------------------------------------------------------------------
-- team + team_member
-- --------------------------------------------------------------------------
CREATE TABLE team (
	team_id           BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_team PRIMARY KEY,
	external_id       VARCHAR(64)    NOT NULL CONSTRAINT uq_team_external_id UNIQUE,
	site_external_id  VARCHAR(64)    NOT NULL CONSTRAINT fk_team_site REFERENCES site (external_id),
	name              NVARCHAR(255)  NOT NULL,
	description       NVARCHAR(3000) NULL,
	-- §C1 names the two methods; the sheet's 2-row lookup table becomes this.
	-- COLLATE is load-bearing: the database's default collation is case-
	-- insensitive, so a bare IN would accept 'push' — the exact 1.x trap rule 5
	-- names (an enum in two casings, working only by collation accident). The
	-- binary collation makes the single casing a fact, and the property test
	-- watches it refuse.
	handling_method   VARCHAR(16)    NOT NULL
		CONSTRAINT ck_team_handling_method
		CHECK (handling_method COLLATE Latin1_General_100_BIN2 IN ('PUSH', 'PROMPT')),
	shift_template_id BIGINT         NULL CONSTRAINT fk_team_shift_template
		REFERENCES shift_template (shift_template_id),
	break_template_id BIGINT         NULL CONSTRAINT fk_team_break_template
		REFERENCES break_template (break_template_id),
	retired_at        DATETIME2(3)   NULL,
	created_at        DATETIME2(3)   NOT NULL CONSTRAINT df_team_created_at DEFAULT SYSUTCDATETIME()
);

-- Natural key AND the scope-leading index in one statement (ScopeIndexRule
-- reads this file): team names are unique per site among active teams.
CREATE UNIQUE INDEX ux_team_site_name ON team (site_external_id, name) WHERE retired_at IS NULL;

CREATE TABLE team_member (
	team_member_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_team_member PRIMARY KEY,
	team_id           BIGINT       NOT NULL CONSTRAINT fk_team_member_team REFERENCES team (team_id),
	user_id           BIGINT       NOT NULL CONSTRAINT fk_team_member_user REFERENCES user_account (user_id),
	-- Denormalized scope, the fielded pattern (edge's lane_session): every seam
	-- read leads with the site predicate, and the FK keeps it honest.
	site_external_id  VARCHAR(64)  NOT NULL CONSTRAINT fk_team_member_site REFERENCES site (external_id),
	retired_at        DATETIME2(3) NULL,
	created_at        DATETIME2(3) NOT NULL CONSTRAINT df_team_member_created_at DEFAULT SYSUTCDATETIME()
);

-- The unique pair 1.x never enforced (two single-column indexes, no unique).
CREATE UNIQUE INDEX ux_team_member ON team_member (team_id, user_id) WHERE retired_at IS NULL;

-- The scope-leading read path.
CREATE INDEX ix_team_member_scope ON team_member (site_external_id, team_id);
