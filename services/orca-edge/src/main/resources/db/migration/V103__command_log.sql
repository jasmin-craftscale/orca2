-- One row per command this site was asked to carry out on its hardware, and what
-- came of it.
--
-- WHAT THIS IS FOR
-- It is the answer to the only question anybody asks after a barrier does, or
-- does not, move: what were we told to do, what did we do, and what did the
-- hardware say back. An operator reads it after an incident; an engineer reads it
-- when a lane is behaving oddly.
--
-- ⚠️ WHY `command_id` IS UNIQUE, WHEN SOMETHING ELSE ALREADY PREVENTS DUPLICATES
-- Commands carry a key so that the same command delivered twice has the effect of
-- one, and `IdempotencyStore` — a shared platform component — records those keys
-- and gives a replay the first attempt's answer. This constraint is not that
-- mechanism; it is the line behind it. If two deliveries race closely enough that
-- both get past the claim, the database is what decides, rather than the
-- interleaving of two threads.
--
-- That belt-and-braces is specific to what these commands do. For an instruction
-- that raises a physical barrier over a lane a truck is sitting in, "almost
-- certainly once" is not a guarantee worth having.

CREATE TABLE command_log (
	command_log_id     BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_command_log PRIMARY KEY,

	-- The key that makes a repeated delivery of the same command harmless. It
	-- identifies one execution of one step of one process — not the truck's visit
	-- as a whole — and it stays the same for as long as that step is running,
	-- which is exactly the property a de-duplication key needs.
	--
	-- ⚠️ A key derived from the visit alone would be wrong, and dangerously so: a
	-- visit can legitimately raise the barrier twice, and the second command would
	-- be mistaken for a replay of the first and silently dropped.
	command_id         VARCHAR(64)   NOT NULL CONSTRAINT uq_command_log_command UNIQUE,

	site_external_id   VARCHAR(64)   NOT NULL,
	lane_external_id   VARCHAR(64)   NOT NULL,
	device_external_id VARCHAR(64)   NULL,
	action             VARCHAR(32)   NOT NULL,
	params             NVARCHAR(MAX) NULL,
	deadline_ms        BIGINT        NOT NULL,

	-- How it ended: EXECUTED, FAILED or UNKNOWN.
	--
	-- There is deliberately no IN_PROGRESS here, even though that value does exist
	-- in the messages sent over the wire. In flight is not an outcome, and a row
	-- recording it would be a recorded outcome that is not one.
	--
	-- ⚠️ UNKNOWN IS A REAL ANSWER, NOT A MISSING ONE. It means the deadline passed
	-- with no reply, so nobody can say whether the barrier moved. The correct
	-- response to that is to go and verify the barrier's actual position, never to
	-- assume. A schema without this value would force the code to write FAILED
	-- instead — recording that the barrier did not move when it may well have,
	-- which is precisely the hazard the whole design refuses to create. This
	-- platform never assumes the state of the physical world.
	status             VARCHAR(16)   NOT NULL,

	-- What the device host said, verbatim. For the operator reading this after an
	-- incident, the platform's interpretation is worth less than the raw answer.
	device_response    NVARCHAR(MAX) NULL,

	-- Why, when the status alone does not say — notably the difference between a
	-- host that refused and a command DISCARDED AS EXPIRED before it was sent.
	-- Both are FAILED; only one of them reached the hardware.
	detail             NVARCHAR(1000) NULL,

	received_at        DATETIME2(3)  NOT NULL
		CONSTRAINT df_command_log_received DEFAULT SYSUTCDATETIME(),
	acked_at           DATETIME2(3)  NULL,

	CONSTRAINT ck_command_log_status CHECK (status IN ('EXECUTED', 'FAILED', 'UNKNOWN'))
);

-- Serves both readers of this table: an operator asking what happened on a lane,
-- and the sweep that deletes rows once they are older than the retention window.
-- Site and lane first because that is how the question arrives, then time.
CREATE INDEX ix_command_log_lane ON command_log (site_external_id, lane_external_id, received_at);
