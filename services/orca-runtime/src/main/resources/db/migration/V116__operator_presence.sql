-- Operator presence: whether each person at this site is available to be given
-- work right now, and the history of how their availability changed.
--
-- WHAT THIS IS FOR
-- When a work item is created and the team it routes to is set up to have work
-- pushed to a member rather than offered to the room, something has to decide who
-- is actually there. This table is that answer.
--
-- HOW IT IS SHAPED, AND WHY THAT IS RIGHT
-- One row per transition, with the row that has no end time being the current
-- status. There is deliberately no status column on any user record: a current
-- state stored in two places is a current state that can disagree with itself,
-- and here the history IS the state. This shape is inherited from the old system
-- and kept because it is correct.
--
-- ⚠️ WHAT THE OLD SYSTEM NEVER HAD, AND WHAT MAKES THE SHAPE TRUSTWORTHY: at most
-- one open row per operator, enforced by the filtered unique index below rather
-- than hoped for by the application. Without it, two rows with no end time make
-- "the current status" a question with two answers.
--
-- WHICH STATUSES MEAN "GIVE THIS PERSON WORK"
-- IDLE and WORKING are assignable. DND, BREAK, OFFLINE and ACTIVE are not.
--
-- That last pair is a decision rather than an inheritance, and worth stating: the
-- source material named idle and working explicitly as the assignable ones, and
-- said nothing about the rest. BREAK and ACTIVE are carried because they exist in
-- the old system and operators genuinely set them — but treating them as
-- assignable would be a guess, and the safe direction for a guess about "is this
-- person at their desk" is no.
--
-- ONE MORE TRANSLATION FIX
-- The status was a six-row lookup table in the old system, seeded in one casing
-- and compared in another by every piece of code that read it — which worked only
-- because the database's collation ignores case. Here it is a constrained column
-- in one canonical casing, compared under a binary collation so that the casing
-- is enforced rather than assumed.
CREATE TABLE user_activity (
	user_activity_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_user_activity PRIMARY KEY,
	site_external_id VARCHAR(64)  NOT NULL,
	-- A user's external id, in the vocabulary orca-core publishes. There is no
	-- foreign key and there cannot be one: user accounts live in another service's
	-- schema, which this service's database login has no access to. The value
	-- arrives across the service boundary and is resolved through the view
	-- orca-core publishes for it.
	user_external_id VARCHAR(64)  NOT NULL,
	status           VARCHAR(16)  NOT NULL
		CONSTRAINT ck_user_activity_status
			CHECK (status COLLATE Latin1_General_100_BIN2
				IN ('IDLE', 'WORKING', 'DND', 'BREAK', 'OFFLINE', 'ACTIVE')),
	started_at       DATETIME2(3) NOT NULL
		CONSTRAINT df_user_activity_started_at DEFAULT SYSUTCDATETIME(),
	ended_at         DATETIME2(3) NULL
);

-- THE invariant this table rests on: one current status per operator.
--
-- Unique over the operator, filtered to rows that have not ended — so any number
-- of closed rows may exist as history, and only one may be open. When two
-- transitions race, both trying to open a row, the second is refused here and
-- retried once the first one's close is visible. That is the same discipline
-- every claim in this service follows: attempt the guarded write, let the
-- database decide, retry on the loss.
CREATE UNIQUE INDEX ux_user_activity_open ON user_activity (user_external_id)
	WHERE ended_at IS NULL;

-- Serves both presence reads — one operator's current status, and the whole
-- site's set of available operators. Site first, as every index in this service
-- is, because the shared code every read goes through puts the site condition
-- first; status and end time are carried along so the answer comes from the index
-- alone.
CREATE INDEX ix_user_activity_scope ON user_activity (site_external_id, user_external_id, started_at)
	INCLUDE (status, ended_at);
