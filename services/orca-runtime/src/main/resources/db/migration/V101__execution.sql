-- A truck's visit to a lane: the visit itself, the lock that lets exactly one
-- start at a time, and the device events attached to it.
--
-- WHAT THIS IS FOR
-- orca-runtime is the service that runs the gate. When a plate is read at a lane
-- it must decide one of two things: this is a new truck, so start a visit and run
-- the site's process for it — or a truck is already there and this event belongs
-- to the visit already in progress. Everything below exists to make that decision
-- correct when two events arrive at the same instant, on two servers.
--
-- THE PROPERTY THESE THREE TABLES EXIST TO GUARANTEE
--
--     Two device events for one truck, at the same moment, handled by two
--     different instances: EXACTLY ONE visit starts.
--
-- This is the single hardest guarantee in the service, and every inbound path
-- depends on it. It is not argued for — it is measured. The proof is an
-- executable test suite, `AdmissionPropertiesIT`, run against the real workflow
-- engine and a real SQL Server: a thousand iterations, two simultaneous events
-- each time, and exactly a thousand visits.
--
-- Three mechanisms hold it up, and each is explained where it appears below: a
-- lock row per lane, a filtered unique index as the backstop behind it, and
-- starting the process inside the same transaction as the insert. Changing any of
-- the three means reading that test suite before deciding the guarantee still
-- holds.
--
-- ⚠️ A SECOND COPY OF THESE TABLES EXISTS, ON PURPOSE. `AdmissionPropertiesIT`
-- applies its own copy into a schema of its own, `it_admission`, because it
-- deliberately disables the lane lock in order to watch the backstop fire — which
-- is not a mode the shipping code has, and should not be. Two shapes of the same
-- tables is a real cost, and it is written down rather than hidden.

-- --------------------------------------------------------------------------
-- lane_session — one row per lane, and the thing everything admitting a truck
-- serialises on. Taking an update lock on this row is how two servers agree about
-- one lane. Nothing else is locked.
--
-- WHY A ROW RATHER THAN A LOCK IN MEMORY
-- Because this platform is designed to run more than one instance at a time, and
-- an in-process lock would be correct on one instance and meaningless on two. The
-- database's locks and the database's clock are the reference for anything two
-- instances have to agree on — that is a rule that holds everywhere here, not a
-- choice this table made.
--
-- The lane is identified by the numeric key orca-core uses for it, read from the
-- view `core.topology_lane`. That is deliberate: it is a key that is correlated
-- on, locked on and indexed on, so it wants to be a fixed narrow value rather
-- than the lane's display code — `lane_code` — which is what the old system
-- stored in its place.
--
-- ⚠️ THE PRIMARY KEY IS (site_external_id, lane_id), IN THAT ORDER, AND THE ORDER
-- IS NOT COSMETIC. IT WAS MEASURED.
--
-- Every read of every table in this service goes through shared code that puts
-- the caller's site condition FIRST and adds the caller's own filter after it. So
-- the query that arrives here always looks like "site is one of these AND lane is
-- that one".
--
-- With the key on the lane alone, that leading condition does not match the key's
-- leading column. On a table with a handful of rows SQL Server then answers it by
-- scanning the whole table — and a scan taken under the update lock that this row
-- exists to provide takes that lock on EVERY LANE AT THE SITE, not on one.
--
-- This is not a theoretical concern. An eight-lane load run deadlocked repeatedly
-- and exhausted its retries until the key was widened. The lane lock's entire
-- promise is that a busy lane never blocks a quiet one, and a key that does not
-- lead with the site column silently converts it into a site-wide lock — with no
-- error, no warning, and correct results throughout.
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
-- execution — one row per visit: this truck, at this lane, in this state, running
-- this process instance.
--
-- A visit can have children. When a site's process iterates over a collection —
-- the containers on one truck, say — each iteration is its own execution, and
-- those children point at their parent through `parent_execution_id`. A root
-- visit has none. That distinction is the entire reason the index below is
-- filtered rather than a plain unique constraint on the lane: children share
-- their parent's lane, and a naive constraint would reject them.
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

-- THE BACKSTOP: at most one active root visit per lane, enforced by the database.
--
-- The lane lock above is the mechanism that normally prevents a second visit.
-- This index is what keeps the guarantee true anyway, on the day some future
-- inbound path forgets to take that lock. The lock makes the system correct; this
-- makes it correct even when somebody is careless.
--
-- ⚠️ BOTH HALVES OF THE FILTER ARE LOAD-BEARING, AND REMOVING EITHER BREAKS
-- SOMETHING THAT WORKS TODAY:
--
--   * `status = 'ACTIVE'` — so that once a visit finishes, the next truck on that
--     lane is allowed in. Without it a lane would accept exactly one truck, ever.
--   * `parent_execution_id IS NULL` — so that a process iterating over a
--     collection can create child executions, which necessarily share their
--     parent's lane. Without it the second container on a truck is rejected as a
--     duplicate visit.
--
-- It is known to work, rather than assumed to: the proving suite disables the
-- lane lock and watches this index fire. With the lock removed it rejects a
-- duplicate 99 times in 100; with the lock in place it never has to, 0 times in
-- 1,000.
CREATE UNIQUE INDEX ux_execution_one_active_root_per_lane
	ON execution (lane_id)
	WHERE status = 'ACTIVE' AND parent_execution_id IS NULL;

-- The read that decides "is a truck already here?", made on every arriving event
-- while the lane lock is held. Site, lane and status are what the question asks
-- on; the three columns carried along in the index are the whole of the answer,
-- so the rows themselves never have to be fetched. On the path a truck is waiting
-- on, and holding a lock, that matters.
CREATE INDEX ix_execution_lane_status ON execution (site_external_id, lane_id, status)
	INCLUDE (parent_execution_id, execution_id, external_id);

-- --------------------------------------------------------------------------
-- execution_event — an accepted device event, attached to the visit it belongs
-- to.
--
-- WHY THIS TABLE EXISTS AT ALL
-- Admitting an event has two correct outcomes, not one: either this event STARTED
-- a visit, or a visit was already running at that lane and this event JOINS it.
-- The first outcome writes an `execution` row and is visible. Without this table
-- the second outcome would be a decision with nowhere to land — the event would
-- be acknowledged back to the hardware and then exist nowhere at all. That is the
-- shape of every "we definitely processed it" defect: an acknowledgement with no
-- record behind it.
--
-- `event_uuid` IS UNIQUE AS A SECOND LINE OF DEFENCE, NOT THE FIRST
-- A redelivered batch is already given a single effect by `IdempotencyStore`, the
-- shared component that records what has been handled. This constraint is what
-- holds if two deliveries race closely enough that both get past that claim: the
-- database decides, rather than the interleaving of two threads.
--
-- `attributes` IS THE DECODED EVENT, NOT THE RAW ONE
-- It is a small JSON map, produced by orca-edge, the service that talks to
-- hardware. The vendor's own message format — for the plate cameras, a framed XML
-- `ZapPacket` — stops at that service, deliberately: nothing in this schema, and
-- nothing in the process that runs the gate, knows what one is or how it is
-- shaped.
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
