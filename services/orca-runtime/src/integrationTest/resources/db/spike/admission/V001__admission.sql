-- WP0 · the admission proof — the two tables the property turns on.
--
-- This is a SPIKE schema, applied only by the integration suite. It is not a
-- service migration and is deliberately not under `db/migration`: WP6 places the
-- production shape in orca-runtime's own migrations once the property has been
-- proven. What is here is only what admission touches, in the shape it will ship.

-- One row per lane. Admission takes an UPDLOCK on it, so every admission for a
-- lane serialises on that lane's row and on nothing else — a busy lane cannot
-- block a quiet one.
--
-- It is a row rather than an application lock because §B8 says the database's
-- clock and the database's locks are the reference for anything two instances
-- must agree on. An in-process lock would be correct on one instance and
-- meaningless on two, which is ADR-015.
CREATE TABLE lane_session (
	lane_id           BIGINT       NOT NULL CONSTRAINT pk_lane_session PRIMARY KEY,
	lane_external_id  VARCHAR(64)  NOT NULL,
	bound_plate       VARCHAR(32)  NULL,
	bound_at          DATETIME2(3) NULL,
	updated_at        DATETIME2(3) NOT NULL
		CONSTRAINT df_lane_session_updated DEFAULT SYSUTCDATETIME()
);

-- The visit. `parent_execution_id` is NULL for a root visit and set for the
-- children a map-iterator creates (§C2) — which is the whole reason the index
-- below is filtered rather than a plain unique constraint on the lane.
CREATE TABLE execution (
	execution_id         BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_execution PRIMARY KEY,
	external_id          VARCHAR(64)  NOT NULL CONSTRAINT uq_execution_external_id UNIQUE,
	lane_id              BIGINT       NOT NULL,
	parent_execution_id  BIGINT       NULL,
	status               VARCHAR(16)  NOT NULL,
	plate                VARCHAR(32)  NULL,
	process_instance_id  VARCHAR(64)  NULL,
	started_at           DATETIME2(3) NOT NULL
		CONSTRAINT df_execution_started_at DEFAULT SYSUTCDATETIME(),
	completed_at         DATETIME2(3) NULL
);

-- THE BACKSTOP: at most one ACTIVE ROOT visit per lane.
--
-- The lane lock is the mechanism; this is what makes the property true even when
-- some future inbound path forgets the lock. It is filtered on both predicates
-- deliberately:
--
--   * `status = 'ACTIVE'`             — so the NEXT truck on the lane is allowed
--                                       once the previous visit has finished.
--   * `parent_execution_id IS NULL`   — so a map-iterator's children, which share
--                                       their parent's lane, are not rejected.
--                                       §C2 names this exact trap.
CREATE UNIQUE INDEX ux_execution_one_active_root_per_lane
	ON execution (lane_id)
	WHERE status = 'ACTIVE' AND parent_execution_id IS NULL;

-- Correlation reads this on every admission, under the lane lock.
CREATE INDEX ix_execution_lane_status ON execution (lane_id, status)
	INCLUDE (parent_execution_id, execution_id);
