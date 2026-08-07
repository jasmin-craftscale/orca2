-- orca-edge · the durable capture buffer and minimal device state (§C3).
--
-- §B10: "a device event survives a link outage — edge buffers durably, per lane,
-- in order, and drains when runtime returns". Everything about this table's shape
-- follows from that one sentence.
--
-- ⚠️ `site_external_id` is the scope dimension, and it is the EXTERNAL id rather
-- than core's surrogate key. Edge reads lanes through `core.topology_lane`, which
-- publishes both; keying on the external one means edge does not carry a foreign
-- key into another service's numbering. The value comes from configuration
-- (`orca.installation.site-external-id`) — this is an appliance, and its own site
-- is an installation fact, not something derived per request.

CREATE TABLE event_buffer (
	-- The per-lane FIFO order. IDENTITY rather than a timestamp: two captures in
	-- the same millisecond must still have an order, and §B8 says the database's
	-- clock is the reference for anything two instances must agree on — but a
	-- clock cannot order events inside its own resolution.
	sequence_no       BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_event_buffer PRIMARY KEY,

	-- The dedup key the producer supplies. A camera that retries after a lost
	-- acknowledgement sends the same uuid, and it must not become two events.
	event_uuid        VARCHAR(64)   NOT NULL CONSTRAINT uq_event_buffer_uuid UNIQUE,

	site_external_id  VARCHAR(64)   NOT NULL,
	lane_external_id  VARCHAR(64)   NOT NULL,
	device_external_id VARCHAR(64)  NULL,
	event_type        VARCHAR(32)   NOT NULL,

	-- The capture as it arrived. §D2's LPR contract references images by
	-- filesystem path rather than carrying bytes, so this stays small — which is
	-- also why the payload is inline here and not in a content-addressed blob.
	payload           NVARCHAR(MAX) NOT NULL,

	status            VARCHAR(16)   NOT NULL CONSTRAINT df_event_buffer_status DEFAULT 'PENDING',
	attempts          INT           NOT NULL CONSTRAINT df_event_buffer_attempts DEFAULT 0,
	last_error        NVARCHAR(1000) NULL,

	received_at       DATETIME2(3)  NOT NULL CONSTRAINT df_event_buffer_received DEFAULT SYSUTCDATETIME(),
	dispatched_at     DATETIME2(3)  NULL,
	acked_at          DATETIME2(3)  NULL,

	CONSTRAINT ck_event_buffer_status CHECK (status IN ('PENDING', 'DISPATCHED', 'ACKED', 'DEAD'))
);

-- The pump's only query: this lane's undelivered events, in order. Filtered so
-- the index stays the size of the backlog rather than the size of history — the
-- buffer holds ≥72 h of peak traffic (§C3) and almost all of it is ACKED.
CREATE INDEX ix_event_buffer_undelivered
	ON event_buffer (site_external_id, lane_external_id, sequence_no)
	WHERE status IN ('PENDING', 'DISPATCHED');

-- Retention and the diagnostics endpoint both read by status.
CREATE INDEX ix_event_buffer_status ON event_buffer (status, received_at);

-- --------------------------------------------------------------------------
-- device_state — the latest known state per device (§C3).
--
-- Deliberately minimal: what the slice reads. §C3's full shape carries heartbeat
-- and IO levels, and those arrive with the device host's own inbound contract.
CREATE TABLE device_state (
	device_state_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_device_state PRIMARY KEY,
	site_external_id   VARCHAR(64)   NOT NULL,
	lane_external_id   VARCHAR(64)   NOT NULL,
	device_external_id VARCHAR(64)   NOT NULL CONSTRAINT uq_device_state_device UNIQUE,
	state              NVARCHAR(MAX) NOT NULL,
	observed_at        DATETIME2(3)  NOT NULL CONSTRAINT df_device_state_observed DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_device_state_lane ON device_state (site_external_id, lane_external_id);
