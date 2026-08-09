-- The buffer every device event lands in before anything else sees it, and the
-- last known state of each device.
--
-- WHAT THIS IS FOR
-- orca-edge is the service that talks to hardware. When a camera reads a plate or
-- a loop detector trips, the event arrives here first, is written to disk, and is
-- only then forwarded to the service that runs the gate. That order is the whole
-- point: the gate must keep working when other things do not, so an event has to
-- survive the rest of the system being unreachable.
--
-- The guarantee this table exists to provide is one sentence: a device event
-- survives a link outage — buffered durably, per lane, in order, and drained when
-- the far end comes back. Every column and index below follows from it.
--
-- ⚠️ THE SITE AND LANE ARE EXTERNAL IDENTIFIERS, NOT THE OTHER SERVICE'S KEYS.
-- orca-core owns the world model — sites, lanes, devices — and publishes the
-- read-only view `core.topology_lane`, which carries both an internal numeric key
-- and an external string id for each row. This service stores the external one.
-- Storing the numeric key would mean holding a reference into another service's
-- private numbering, which it has no way to keep valid and no right to depend on.
--
-- The site itself comes from this installation's configuration, under the
-- property `orca.installation.site-external-id`, rather than being worked out per
-- request. This is a box sitting at one facility; which facility it is, is an
-- installation fact.

CREATE TABLE event_buffer (
	-- What puts events in order: a number the database hands out, ascending, one
	-- per row. Events are drained in this order per lane, which is what "in order"
	-- in the guarantee above actually means.
	--
	-- A generated number rather than the arrival timestamp, deliberately. Two
	-- captures in the same millisecond must still have a definite order, and no
	-- clock can order events inside its own resolution. The database's clock is
	-- the reference for anything two instances have to agree on — but agreeing on
	-- the time is not the same as agreeing on the sequence.
	sequence_no       BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_event_buffer PRIMARY KEY,

	-- The identifier the device itself supplied for this event, and what makes a
	-- retry harmless. A camera whose acknowledgement was lost sends the same event
	-- again with the same identifier; the UNIQUE constraint is what stops that
	-- from becoming two events and, further downstream, two trucks.
	event_uuid        VARCHAR(64)   NOT NULL CONSTRAINT uq_event_buffer_uuid UNIQUE,

	site_external_id  VARCHAR(64)   NOT NULL,
	lane_external_id  VARCHAR(64)   NOT NULL,
	device_external_id VARCHAR(64)  NULL,
	event_type        VARCHAR(32)   NOT NULL,

	-- The event exactly as it arrived on the wire, kept verbatim. When a plate read
	-- is disputed, the question is what the camera said — not what this service
	-- understood it to mean.
	--
	-- It stays small in practice, which is why it is stored inline rather than
	-- shifted into separate content-addressed storage the way large bodies are
	-- elsewhere: the camera protocol refers to images by filesystem path rather
	-- than carrying the image itself.
	payload           NVARCHAR(MAX) NOT NULL,

	status            VARCHAR(16)   NOT NULL CONSTRAINT df_event_buffer_status DEFAULT 'PENDING',
	attempts          INT           NOT NULL CONSTRAINT df_event_buffer_attempts DEFAULT 0,
	last_error        NVARCHAR(1000) NULL,

	received_at       DATETIME2(3)  NOT NULL CONSTRAINT df_event_buffer_received DEFAULT SYSUTCDATETIME(),
	dispatched_at     DATETIME2(3)  NULL,
	acked_at          DATETIME2(3)  NULL,

	CONSTRAINT ck_event_buffer_status CHECK (status IN ('PENDING', 'DISPATCHED', 'ACKED', 'DEAD'))
);

-- The one query the component draining this buffer makes: "this lane's
-- undelivered events, oldest first". Site, then lane, then sequence — the exact
-- order the question asks them in.
--
-- ⚠️ The filter is what keeps it affordable. This buffer is sized to hold at
-- least 72 hours of peak traffic so that a long outage loses nothing, and at any
-- moment nearly all of it has already been acknowledged. Filtering to the two
-- undelivered states means the index is the size of the current backlog rather
-- than the size of the history — usually a handful of rows against millions.
CREATE INDEX ix_event_buffer_undelivered
	ON event_buffer (site_external_id, lane_external_id, sequence_no)
	WHERE status IN ('PENDING', 'DISPATCHED');

-- A second, unfiltered index for the two readers that ask by state rather than by
-- lane: the sweep that deletes old acknowledged rows, and the diagnostics
-- endpoint an engineer uses to see whether the buffer is draining.
CREATE INDEX ix_event_buffer_status ON event_buffer (status, received_at);

-- --------------------------------------------------------------------------
-- device_state — the most recent thing each device said about itself. One row per
-- device, overwritten; this is a current-state table, not a history.
--
-- Deliberately minimal — it holds what the first end-to-end gate run needs and no
-- more. The full design also carries heartbeat timing and the live levels of each
-- input and output port; those arrive with the work that implements the device
-- host's inbound interface, since that interface is what supplies them.
CREATE TABLE device_state (
	device_state_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_device_state PRIMARY KEY,
	site_external_id   VARCHAR(64)   NOT NULL,
	lane_external_id   VARCHAR(64)   NOT NULL,
	device_external_id VARCHAR(64)   NOT NULL CONSTRAINT uq_device_state_device UNIQUE,
	state              NVARCHAR(MAX) NOT NULL,
	observed_at        DATETIME2(3)  NOT NULL CONSTRAINT df_device_state_observed DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_device_state_lane ON device_state (site_external_id, lane_external_id);
