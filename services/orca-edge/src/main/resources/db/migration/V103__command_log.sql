-- orca-edge · the command log (§C3).
--
-- One row per command this site was asked to perform, and its outcome. It is the
-- answer to the only question that matters after a barrier does or does not move:
-- what were we told to do, what did we do, and what did the hardware say.
--
-- ⚠️ `command_id` is UNIQUE, and that is a second line of defence rather than the
-- first. IdempotencyStore already gives a replayed command one effect; this
-- constraint is what holds if two deliveries race hard enough to both pass the
-- store's claim. The database decides, not the ordering of two threads — and for
-- a command that moves a barrier, "probably once" is not a guarantee.

CREATE TABLE command_log (
	command_log_id     BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_command_log PRIMARY KEY,

	-- §C3: the node-execution id. It is the idempotency key, and it is stable for
	-- the life of that node execution — which is the property the key needs. A key
	-- derived from the visit alone would make a second, legitimate barrier command
	-- look like a replay of the first.
	command_id         VARCHAR(64)   NOT NULL CONSTRAINT uq_command_log_command UNIQUE,

	site_external_id   VARCHAR(64)   NOT NULL,
	lane_external_id   VARCHAR(64)   NOT NULL,
	device_external_id VARCHAR(64)   NULL,
	action             VARCHAR(32)   NOT NULL,
	params             NVARCHAR(MAX) NULL,
	deadline_ms        BIGINT        NOT NULL,

	-- EXECUTED · FAILED · UNKNOWN. IN_PROGRESS is a WIRE status and is deliberately
	-- not here: it describes a delivery in flight, not an outcome, and a row that
	-- recorded it would be a recorded outcome that is not one.
	--
	-- UNKNOWN is a value, not an omission (§B10). The deadline passed with no
	-- answer, so the barrier's actual state must be VERIFIED rather than assumed —
	-- and a schema that could not express it would force the code to write FAILED,
	-- which is the exact hazard.
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

-- An operator asking what happened on a lane, and retention reading by age.
CREATE INDEX ix_command_log_lane ON command_log (site_external_id, lane_external_id, received_at);
