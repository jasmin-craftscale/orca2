-- The people who staff a gate, and the hours they staff it: teams, the users in
-- them, and the shift and break patterns a team works to.
--
-- WHAT THIS IS FOR
-- Not every truck clears the gate automatically. When one needs a human decision,
-- the work has to reach somebody who is on duty and responsible for that lane. A
-- team is that unit of responsibility — a named set of operators at one site. The
-- shift and break templates say when its members are expected to be at their
-- desks. V109__routing_screens.sql later adds the rules that tie a team to
-- particular lanes and screens; this one builds the teams themselves.
--
-- Administrators write all of it. The gate software reads it when it has work to
-- hand out.
--
-- HOW A TEAM RECEIVES WORK
-- `handling_method` on `team` is the one behavioural column here. PUSH means work
-- is assigned to a member; PROMPT means it is offered and a member takes it.
--
-- WHERE THE SHAPE CAME FROM
-- Translated from the Go system in production today, where teams are called
-- "groups". The translation fixes several defects rather than reproducing them,
-- and each fix is explained at the point it applies. Four are worth knowing
-- before you read the tables:
--
--   * A shift's DURATION is not stored. The old system stored it in a column
--     typed for a time-of-day, which is a category error, and nothing kept it
--     consistent with the start and end times beside it. A duration is derivable
--     from those, so it is derived.
--   * The overnight flag is stored, but a CHECK constraint ties it to the times,
--     so the flag can never contradict them. That is the whole reason it can
--     safely exist at all.
--   * Time zones are an IANA zone identifier in a column, validated against the
--     Java runtime's own zone database when written. The old system had a table
--     of four zones, all of them in the United States, and two separate program
--     fields that both wrote to the same column.
--   * A team's site is stated explicitly. In the old system a team had no site at
--     all and its site had to be inferred through whatever it was attached to,
--     which means every read had to know that inference and get it right.
--
-- ONE TRANSLATION DECISION THAT IS NOT OBVIOUS
-- Shift and break templates are NOT scoped to a site; teams are. In the old
-- system both templates carried a site column that was allowed to be null. A
-- nullable scope column is a trap rather than a convenience: every scoped read
-- filters on the scope, so a row with a null one is invisible to all of them and
-- nobody notices until a template silently stops appearing. Templates are instead
-- treated as an installation-wide catalog that a site-scoped team points at.
--
-- DELIBERATELY ABSENT
-- The old system's team routing rules — team, screen and lane together — are not
-- here. They refer to a screen, and screens do not exist yet at this point. They
-- arrive with the work that makes screens real. Named here so the absence reads
-- as a decision rather than an oversight.

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
	-- An IANA time-zone identifier, such as "Europe/Hamburg". The service checks
	-- it against the Java runtime's own zone database before writing, so an
	-- invented zone never reaches the table. One column, one meaning — the old
	-- system had two program fields writing to a single zone column.
	time_zone         VARCHAR(64)   NOT NULL,
	start_time        TIME(0)       NOT NULL,
	end_time          TIME(0)       NOT NULL,
	is_overnight      BIT           NOT NULL,
	retired_at        DATETIME2(3)  NULL,
	created_at        DATETIME2(3)  NOT NULL CONSTRAINT df_shift_template_created_at DEFAULT SYSUTCDATETIME(),
	-- The overnight flag cannot contradict the times it describes. An end at or
	-- before the start is exactly what crossing midnight looks like — and the two
	-- being equal is the round-the-clock shift, which is why the comparison is
	-- "at or before" rather than "before". An end after the start is an ordinary
	-- same-day shift and cannot be marked overnight.
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
	-- Both columns are NOT NULL. They are nullable in the old system only because
	-- the annotations describing them were malformed — the code plainly intends a
	-- break to have a start and a length. The intent is what ports, not the
	-- accident.
	break_start_time  TIME(0)      NOT NULL,
	duration_minutes  INT          NOT NULL CONSTRAINT ck_break_timing_duration CHECK (duration_minutes > 0),
	retired_at        DATETIME2(3) NULL,
	created_at        DATETIME2(3) NOT NULL CONSTRAINT df_break_timing_created_at DEFAULT SYSUTCDATETIME()
);

-- Two breaks in one template cannot start at the same time of day. Nothing
-- downstream could sensibly decide which of them applied.
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
	-- How work reaches this team: PUSH assigns it to a member, PROMPT offers it
	-- and lets a member take it. In the old system these two values were a whole
	-- table — two rows, seeded once, never added to — so they become a constrained
	-- column instead.
	--
	-- ⚠️ THE `COLLATE` CLAUSE IS LOAD-BEARING, NOT DECORATION. This database's
	-- default collation is case-insensitive, so a plain `IN ('PUSH', 'PROMPT')`
	-- would happily accept 'push' and 'Push' as well. That is precisely the defect
	-- being translated away: in the old system the same conceptual value appears
	-- in two different casings in two different tables, and one comparison between
	-- them works only because the collation hid the difference. Comparing under a
	-- binary collation makes the single casing a fact the database enforces. There
	-- is a test that watches it refuse the wrong casing, so this cannot be
	-- weakened silently.
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

-- One index doing two jobs: it makes a team's name unique within its site among
-- active teams, and because it leads with the site column it is also the index
-- every scoped read of this table uses. The build check `ScopeIndexRule` reads
-- this file and fails when a table carrying `site_external_id` has no index
-- leading with it — without
-- one the table can only be scanned, and a scan taken under a lock locks every
-- row at the site.
CREATE UNIQUE INDEX ux_team_site_name ON team (site_external_id, name) WHERE retired_at IS NULL;

CREATE TABLE team_member (
	team_member_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_team_member PRIMARY KEY,
	team_id           BIGINT       NOT NULL CONSTRAINT fk_team_member_team REFERENCES team (team_id),
	user_id           BIGINT       NOT NULL CONSTRAINT fk_team_member_user REFERENCES user_account (user_id),
	-- The site is copied onto the membership row even though it is reachable
	-- through the team. That copy is what lets every read of this table lead with
	-- the site condition without a join, and the foreign key is what stops the
	-- copy from drifting or naming a site that does not exist.
	site_external_id  VARCHAR(64)  NOT NULL CONSTRAINT fk_team_member_site REFERENCES site (external_id),
	retired_at        DATETIME2(3) NULL,
	created_at        DATETIME2(3) NOT NULL CONSTRAINT df_team_member_created_at DEFAULT SYSUTCDATETIME()
);

-- A person cannot be in the same team twice. The old system had an index on each
-- column separately and no unique constraint on the pair, so duplicate
-- memberships were possible there.
CREATE UNIQUE INDEX ux_team_member ON team_member (team_id, user_id) WHERE retired_at IS NULL;

-- The read path: site first, then team. Reads arrive asking "who is in this team"
-- with the site already fixed by the caller's scope, so this is the order they
-- need.
CREATE INDEX ix_team_member_scope ON team_member (site_external_id, team_id);
