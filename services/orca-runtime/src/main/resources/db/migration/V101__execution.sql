-- orca-runtime · the visit, the lane it holds, and the events attached to it (§C2).
--
-- This is WP0's proven shape, moved from the spike schema
-- (src/integrationTest/resources/db/spike/admission) into the migrations that
-- ship. WP0 proved the property against real Flowable and real SQL Server —
-- 1,000 iterations, two simultaneous events each, exactly 1,000 visits — and
-- nothing about the three mechanisms below is changed here. Two things are added:
-- the scope column every service table carries, and the table an accepted event
-- is attached to.
--
-- ⚠️ The spike schema still exists and is still applied by AdmissionPropertiesIT,
-- into its own `it_admission` schema. It carries the deliberate lock bypass that
-- makes the backstop below observable, which is not a mode the shipping operation
-- has. Two shapes of the same tables is a real cost and it is recorded in the
-- phase report rather than hidden.

-- --------------------------------------------------------------------------
-- lane_session — one row per lane. Admission serialises on it and on nothing else.
--
-- A row rather than an application lock because §B8 says the database's clock and
-- the database's locks are the reference for anything two instances must agree on
-- (ADR-015). An in-process lock would be correct on one instance and meaningless
-- on two.
--
-- `lane_id` is core's surrogate key, read from core.topology_lane. §C2 gives
-- runtime.execution a lane_id that admission "correlates, locks and indexes on",
-- explicitly in contrast to the denormalised lane_code the old model had.
--
-- ⚠️ THE KEY ORDER IS (site_external_id, lane_id) AND THAT IS NOT COSMETIC.
--
-- Every read through the scope seam is `site_external_id IN (…) AND <filter>`,
-- because the seam puts the scope predicate first and ANDs the caller's after it.
-- With the key on lane_id alone, that predicate does not match the key's leading
-- column, and on a table with a handful of rows SQL Server answers it with a
-- CLUSTERED INDEX SCAN — which under the UPDLOCK this row exists to provide takes
-- an update lock on EVERY LANE AT THE SITE, not on one.
--
-- That was measured, not reasoned about: WP6's eight-lane run deadlocked
-- repeatedly and exhausted its retries until the key was widened. The lane lock's
-- whole promise is "a busy lane never blocks a quiet one", and a key that does not
-- lead with the scope column silently converts it into a site-wide lock.
CREATE TABLE lane_session (
	site_external_id  VARCHAR(64)  NOT NULL,
	lane_id           BIGINT       NOT NULL,
	lane_external_id  VARCHAR(64)  NOT NULL,
	bound_plate       VARCHAR(32)  NULL,
	bound_at          DATETIME2(3) NULL,
	updated_at        DATETIME2(3) NOT NULL
		CONSTRAINT df_lane_session_updated DEFAULT SYSUTCDATETIME(),
	CONSTRAINT pk_lane_session PRIMARY KEY (site_external_id, lane_id)
);

-- --------------------------------------------------------------------------
-- execution — the visit. `parent_execution_id` is NULL for a root visit and set
-- for the children a map-iterator creates (§C2), which is the whole reason the
-- index below is filtered rather than a plain unique constraint on the lane.
CREATE TABLE execution (
	execution_id         BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_execution PRIMARY KEY,
	external_id          VARCHAR(64)  NOT NULL CONSTRAINT uq_execution_external_id UNIQUE,
	site_external_id     VARCHAR(64)  NOT NULL,
	lane_id              BIGINT       NOT NULL,
	parent_execution_id  BIGINT       NULL,
	status               VARCHAR(16)  NOT NULL,
	plate                VARCHAR(32)  NULL,
	process_instance_id  VARCHAR(64)  NULL,
	started_at           DATETIME2(3) NOT NULL
		CONSTRAINT df_execution_started_at DEFAULT SYSUTCDATETIME(),
	completed_at         DATETIME2(3) NULL,
	CONSTRAINT ck_execution_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'MANUAL'))
);

-- THE BACKSTOP: at most one ACTIVE ROOT visit per lane.
--
-- The lane lock is the mechanism; this is what makes the property true even when
-- some future inbound path forgets the lock. Filtered on both predicates
-- deliberately:
--
--   * `status = 'ACTIVE'`             — so the NEXT truck on the lane is allowed
--                                       once the previous visit has finished.
--   * `parent_execution_id IS NULL`   — so a map-iterator's children, which share
--                                       their parent's lane, are not rejected.
--                                       §C2 names this exact trap.
--
-- It is known to work because WP0's suite removes the lane lock and watches it
-- fire: 99 times in 100 with the lock gone, 0 times in 1,000 with it in place.
CREATE UNIQUE INDEX ux_execution_one_active_root_per_lane
	ON execution (lane_id)
	WHERE status = 'ACTIVE' AND parent_execution_id IS NULL;

-- Correlation reads this on every admission, under the lane lock.
CREATE INDEX ix_execution_lane_status ON execution (site_external_id, lane_id, status)
	INCLUDE (parent_execution_id, execution_id, external_id);

-- --------------------------------------------------------------------------
-- execution_event — an accepted device event, attached to the visit it belongs to.
--
-- WHY THIS EXISTS AT ALL. Admission has two correct outcomes: this event STARTED
-- the visit, or a visit was already running and this event JOINS it (§C2's
-- correlate-or-start). Without a row here the second outcome is a decision with
-- nowhere to land — the event would be acknowledged and then exist nowhere, which
-- is the shape of every "we processed it, honestly" defect.
--
-- `event_uuid` is UNIQUE, and that is a second line of defence rather than the
-- first: IdempotencyStore already gives a redelivered batch one effect. The
-- constraint is what holds if the two deliveries race hard enough to both pass
-- the store's claim — the database decides, not the ordering of two threads.
--
-- `attributes` is the NORMALISED half of the event, as JSON, produced by edge.
-- The vendor's dialect stops at edge (§C3): nothing in this schema knows what a
-- ZapPacket is.
CREATE TABLE execution_event (
	execution_event_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_execution_event PRIMARY KEY,
	event_uuid         VARCHAR(64)   NOT NULL CONSTRAINT uq_execution_event_uuid UNIQUE,
	site_external_id   VARCHAR(64)   NOT NULL,
	execution_id       BIGINT        NOT NULL,
	lane_id            BIGINT        NOT NULL,
	event_type         VARCHAR(32)   NOT NULL,
	device_external_id VARCHAR(64)   NULL,
	attributes         NVARCHAR(MAX) NULL,
	received_at        DATETIME2(3)  NOT NULL
		CONSTRAINT df_execution_event_received DEFAULT SYSUTCDATETIME(),
	CONSTRAINT fk_execution_event_execution FOREIGN KEY (execution_id)
		REFERENCES execution (execution_id)
);

CREATE INDEX ix_execution_event_execution ON execution_event (site_external_id, execution_id);
