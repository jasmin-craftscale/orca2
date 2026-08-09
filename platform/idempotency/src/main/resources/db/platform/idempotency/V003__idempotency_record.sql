-- The record of keys already used, and what happened when they were.
--
-- WHAT PROBLEM THIS SOLVES
-- Every command and every applied fact in this platform carries a key, so that
-- the same one arriving twice has the effect of arriving once. This table is
-- where that is enforced — not by remembering it in memory, which does not
-- survive a restart or a second instance, but by recording it.
--
-- ⚠️ THE PART THAT IS EASY TO GET WRONG: THE SECOND CALLER GETS THE FIRST
-- CALLER'S ANSWER — NOT AN ERROR SAYING "DUPLICATE".
--
-- Think about why a caller retried at all. It sent a command to raise a barrier
-- and never saw a reply — the connection dropped, or the reply timed out. It does
-- not know whether the barrier moved. "Duplicate" is the one response that leaves
-- it exactly as ignorant as before, and the caller's only remaining option is to
-- guess. Replaying the recorded outcome tells it what actually happened, which is
-- the whole point. This is why the `outcome` column exists and why the constraint
-- at the bottom of the table refuses to let a finished record exist without one.
--
-- WHERE THIS FILE ACTUALLY RUNS
-- Once per service that needs it, in that service's own schema — orca-runtime,
-- orca-edge and orca-sync — applied by that service's own migration run with its
-- own credentials. Each gets its own copy, because a service's database login can
-- reach only its own schema.
--
-- ⚠️ Editing this file — including its comments — invalidates the recorded
-- checksum in all three schemas at once, not one. Flyway checksums the whole
-- file, so the mismatch appears once per schema it was applied into.

CREATE TABLE idempotency_record (
	-- The key the CALLER supplied. This platform never invents one — the caller
	-- knows what makes its request the same request. For a device command it
	-- identifies one execution of one process step; for a fact being replicated
	-- it is the fact's own key.
	idempotency_key VARCHAR(200)  NOT NULL,

	-- Which operation the key was used for, and part of the primary key alongside
	-- it. Two unrelated callers doing two unrelated things may legitimately choose
	-- the same key; without this column the second one would silently be handed
	-- the first one's answer, which is a far worse failure than a duplicate.
	operation       VARCHAR(120)  NOT NULL,

	-- IN_PROGRESS — started, not finished.
	--
	--   ⚠️ NOT A TERMINAL STATE, and the distinction matters at a gate. A caller
	--   that finds this keeps waiting for the real outcome. A device host that DID
	--   raise the barrier but answered slowly must not have its step recorded as
	--   failed just because nobody had heard back yet.
	--
	-- COMPLETED   — finished; `outcome` holds the answer to replay.
	-- FAILED      — finished unsuccessfully. Also a recorded outcome, and also
	--               replayed: "it failed" is information the caller can act on.
	status          VARCHAR(20)   NOT NULL,

	-- The answer, stored verbatim and returned unchanged to any later caller
	-- presenting the same key. Null only while still in progress, which the
	-- constraint at the bottom of the table enforces.
	outcome         NVARCHAR(MAX) NULL,

	-- Which instance is carrying out the operation. This is what makes an
	-- abandoned attempt recoverable: without it, an instance that died mid-flight
	-- would leave a key stuck in progress forever, and every retry would be told
	-- to keep waiting.
	holder_id       VARCHAR(200)  NULL,

	-- The database's own clock, never the machine's that inserted the row.
	created_at      DATETIME2(3)  NOT NULL
		CONSTRAINT df_idempotency_created_at DEFAULT SYSUTCDATETIME(),
	completed_at    DATETIME2(3)  NULL,

	CONSTRAINT pk_idempotency_record PRIMARY KEY (operation, idempotency_key),
	CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
	-- The status, the outcome and the completion time must agree. A finished
	-- record with no outcome is the exact failure this whole mechanism exists to
	-- prevent — a retry that finds its key, learns nothing from it, and is left
	-- guessing. The database refuses to let such a row exist at all.
	CONSTRAINT ck_idempotency_outcome
		CHECK ((status = 'IN_PROGRESS' AND outcome IS NULL     AND completed_at IS NULL)
		    OR (status <> 'IN_PROGRESS' AND outcome IS NOT NULL AND completed_at IS NOT NULL))
);

-- Old records are deleted by age, by whichever service owns the schema this table
-- was applied into. The index is what makes that sweep affordable.
--
-- ⚠️ HOW OLD IS DELIBERATELY NOT STATED HERE. It is a retention setting, and the
-- full list of retention classes and their windows is held in a data dictionary
-- that is not part of this repository. Writing a number into this file would make
-- it look decided; the correct place to look is the retention configuration.
--
-- The window has one hard floor whatever it is set to: it must comfortably exceed
-- the longest a caller could retry over, or a retry arriving after its record was
-- swept finds nothing and executes a second time.
CREATE INDEX ix_idempotency_created_at ON idempotency_record (created_at);
