-- P4 · The recorded-key table.
--
-- The same key applied twice has the effect of once, and the SECOND caller gets
-- the recorded OUTCOME. Not a bare "duplicate": the caller retried because it
-- never saw the first answer, and an error is the one response it cannot use
-- (§B9, "A device command"; §D3, Idempotency).

CREATE TABLE idempotency_record (
	-- The caller's key. For a device command this is the node-execution id; for
	-- a replicated fact it is the fact's own key. The platform does not mint it.
	idempotency_key VARCHAR(200)  NOT NULL,

	-- Which operation the key was used for. Two different operations may
	-- legitimately reuse a key from two different callers; without this column
	-- the second would silently receive the first's answer.
	operation       VARCHAR(120)  NOT NULL,

	-- IN_PROGRESS — started, not finished. A WIRE STATUS AND NOT A TERMINAL
	--               STATE: the caller keeps waiting for the real outcome. This
	--               is the distinction the gate depends on — a host that DID
	--               raise the barrier but answered slowly must not have its step
	--               failed.
	-- COMPLETED   — finished; `outcome` is the answer to return on replay.
	-- FAILED      — finished, unsuccessfully; also a recorded outcome.
	status          VARCHAR(20)   NOT NULL,

	-- The recorded outcome, returned verbatim on replay. NULL only while
	-- IN_PROGRESS, and the constraint below is what keeps that true.
	outcome         NVARCHAR(MAX) NULL,

	-- Who is executing it, so an abandoned in-flight record is recoverable
	-- rather than permanently in progress.
	holder_id       VARCHAR(200)  NULL,

	-- The database's clock, never an instance's.
	created_at      DATETIME2(3)  NOT NULL
		CONSTRAINT df_idempotency_created_at DEFAULT SYSUTCDATETIME(),
	completed_at    DATETIME2(3)  NULL,

	CONSTRAINT pk_idempotency_record PRIMARY KEY (operation, idempotency_key),
	CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
	-- A terminal record with no outcome is the exact failure this primitive
	-- exists to prevent: a replay that finds the key, learns nothing, and has to
	-- guess. It cannot be written.
	CONSTRAINT ck_idempotency_outcome
		CHECK ((status = 'IN_PROGRESS' AND outcome IS NULL     AND completed_at IS NULL)
		    OR (status <> 'IN_PROGRESS' AND outcome IS NOT NULL AND completed_at IS NOT NULL))
);

-- Records are purged on a TTL by whoever owns the schema. The window is not
-- stated here and is not stated by the architecture: it is a retention setting,
-- and the closed retention-class list lives in the Data Dictionary, which is not
-- in this repository. See the Phase 0 report.
CREATE INDEX ix_idempotency_created_at ON idempotency_record (created_at);
