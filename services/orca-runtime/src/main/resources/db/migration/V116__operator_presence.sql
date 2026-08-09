-- orca-runtime · Phase 3 WP4 — operator presence (sheet §6).
--
-- 1.x: a 6-row `user_status` lookup seeded TitleCase, compared UPPERCASE by
-- every code path — working only because the collation is case-insensitive
-- (the same trap Phase 2's §7.1 caught in enum CHECKs). 2.0 makes the status a
-- CONSTRAINED COLUMN in one canonical casing with a binary-collated CHECK —
-- the sheet's own recommendation, and the same shape as team.handling_method.
--
-- The model is 1.x's, kept because it is right: one row per transition, and
-- the OPEN row (ended_at IS NULL) IS the current status — there is no status
-- column on any user row to drift out of sync. What 1.x never had is the
-- invariant that makes that reading trustworthy: AT MOST ONE OPEN ROW PER
-- OPERATOR, as a filtered unique index rather than an application's hope.
--
-- Assignability (the Push path reads this): IDLE and WORKING are assignable;
-- DND, BREAK, OFFLINE and ACTIVE are not. The sheet names idle/working
-- explicitly; BREAK and ACTIVE are carried as statuses (they exist in 1.x and
-- operators use them) but not as assignable ones — a decision stated in the
-- phase report, not inherited ambiguity.
CREATE TABLE user_activity (
	user_activity_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_user_activity PRIMARY KEY,
	site_external_id VARCHAR(64)  NOT NULL,
	-- Core's published user vocabulary (topology_operator). No cross-schema FK
	-- exists to give it (ADR-004) — the value arrives through the api boundary.
	user_external_id VARCHAR(64)  NOT NULL,
	status           VARCHAR(16)  NOT NULL
		CONSTRAINT ck_user_activity_status
			CHECK (status COLLATE Latin1_General_100_BIN2
				IN ('IDLE', 'WORKING', 'DND', 'BREAK', 'OFFLINE', 'ACTIVE')),
	started_at       DATETIME2(3) NOT NULL
		CONSTRAINT df_user_activity_started_at DEFAULT SYSUTCDATETIME(),
	ended_at         DATETIME2(3) NULL
);

-- THE invariant: one current status per operator. Two racing transitions both
-- try to open a row; the second is refused here and retried after the first's
-- close is visible — the same guarded-write discipline as every claim.
CREATE UNIQUE INDEX ux_user_activity_open ON user_activity (user_external_id)
	WHERE ended_at IS NULL;

-- The presence reads: current status per operator, and the site's assignable
-- set. Scope-leading (ScopeIndexRule reads this file).
CREATE INDEX ix_user_activity_scope ON user_activity (site_external_id, user_external_id, started_at)
	INCLUDE (status, ended_at);
