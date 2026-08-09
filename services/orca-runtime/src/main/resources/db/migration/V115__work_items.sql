-- The work item: one unit of work that a human has to do, and the record of every
-- action taken on it.
--
-- WHAT THIS IS FOR
-- Most trucks clear the gate automatically. When a step of a site's process
-- cannot be finished by software — an unreadable plate, a mismatch the customer's
-- system will not resolve — the workflow engine parks the process at a wait state
-- and one of these rows is created for an operator to deal with. Completing it
-- lets the parked process carry on.
--
-- ⚠️ THE TWO THINGS ABOUT THIS THAT ARE EASY TO GET WRONG, AND ARE NOT
--
--   1. The engine parking and the work item being created happen IN THE SAME
--      TRANSACTION. There is no HTTP call between them and no window in which one
--      exists without the other. A parked process with no work item is a truck
--      nobody will ever attend to; a work item with no parked process is an
--      operator doing work that goes nowhere.
--   2. Completing the item and advancing the process are also one transaction,
--      for the same reason in reverse.
--
-- WHY THIS FILE IS NUMBERED WHERE IT IS
-- It comes after the workflow engine's own set of migrations, which occupy the
-- numbers just below it. Flyway refuses to apply a migration whose number is
-- lower than one already applied, and there are development databases that have
-- already applied the engine's set. Numbering had to go forward, not into the
-- gap.
--
-- WHY THERE IS NO QUEUE TABLE
-- The queue is not a thing; it is a query. An item is in the queue if its status
-- and assignee say so, ordered by when it was queued — which is what the first
-- index below serves. The old system kept a second representation of the same
-- queue as an in-memory array inside another service, which was not a system of
-- record and could disagree with the database it shadowed.
--
-- WHAT THE OLD SYSTEM HAD THAT IS DELIBERATELY ABSENT
--   * An iteration counter, left over from an earlier attempt at service-level
--     timing. Nothing ever incremented it. Timing here is a real engine timer,
--     and what it produces is the `sla_breached_at` column below.
--   * Statuses for escalation and re-queueing. They were declared and never
--     written by anything. The four statuses below are exactly the four that have
--     a writer.
--   * A team reference that was never wired up as a foreign key and was always
--     set to zero.
--   * Copies of the customer, site and area on every row. All are reachable from
--     the lane and the visit.
--   * A flag distinguishing a top-level process from a sub-process. Sub-processes
--     are not modelled in this system yet, so the column would have exactly one
--     writable value. What identifies the parked step instead is the pair of
--     names the engine itself uses: the process design's key, and the task's
--     identifier within it.
--
-- ONE THING STATED OPENLY RATHER THAN HIDDEN
-- `event_data` and `corrected_event_data` hold their content inline. The design
-- calls for large bodies to be stored once per distinct content and referenced,
-- rather than copied into every record that mentions them — but that store does
-- not exist yet, and the size threshold at which it would take over has not been
-- set. Inline text is the honest shape until it does; what bounds it in the
-- meantime is the retention policy declared for this table in Java.

-- --------------------------------------------------------------------------
-- The visit gains a fourth and final state: FAILED.
--
-- It has one writer — the lane reset an operator triggers when a lane is stuck
-- and has to be cleared. That one action aborts the visit and fails all of its
-- open work items together, in a single transaction, which is also what gives the
-- work item's own FAILED status below a writer. Until lane reset existed there
-- was no way to abort a visit at all, which is why the three original states did
-- not include one.
-- --------------------------------------------------------------------------
ALTER TABLE execution DROP CONSTRAINT ck_execution_status;
ALTER TABLE execution ADD CONSTRAINT ck_execution_status
	CHECK (status IN ('ACTIVE', 'COMPLETED', 'MANUAL', 'FAILED'));

-- --------------------------------------------------------------------------
-- work_item — one unit of human work, parked on one waiting engine task.
--
-- `task_id` IS THE ENGINE'S OWN HANDLE, AND ITS UNIQUENESS IS A SAFETY PROPERTY
-- It identifies the exact wait state this item is parked on, and it is unique:
-- one item per parked task, created by the same transaction that parked it.
--
-- ⚠️ That is what makes a stale or out-of-order completion REFUSABLE rather than
-- merely unlikely. Completing an item hands this identifier back to the engine.
-- If the engine is no longer waiting on that task — because the process moved on,
-- or somebody else already completed it — the engine cannot resolve it, and the
-- entire completion rolls back. The operator is told; nothing half-applies.
--
-- `assignee` IS A NAME, NOT A FOREIGN KEY
-- It holds a user's external id in the vocabulary orca-core publishes, and null
-- means unassigned. There is no foreign key and there cannot be one: user
-- accounts live in another service's schema, which this service's database login
-- has no access to. The value arrives across the service boundary and is resolved
-- through the view orca-core publishes for exactly that purpose.
-- --------------------------------------------------------------------------
CREATE TABLE work_item (
	work_item_id            BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_work_item PRIMARY KEY,
	external_id             VARCHAR(64)   NOT NULL CONSTRAINT uq_work_item_external_id UNIQUE,
	site_external_id        VARCHAR(64)   NOT NULL,
	execution_id            BIGINT        NOT NULL
		CONSTRAINT fk_work_item_execution REFERENCES execution (execution_id),
	lane_id                 BIGINT        NOT NULL,
	-- The visit and the lane, as the external string ids that interfaces speak in,
	-- stored alongside the numeric keys just above.
	--
	-- That looks like storing the same fact twice, and it is worth saying why it
	-- is not the mistake it resembles. This service is split into modules with an
	-- enforced wall between them: the module owning work items may not read the
	-- module owning visits — not its tables and not its objects. So these values
	-- cannot be looked up later. They arrive once, across the narrow interface
	-- between the two modules, in the transaction that creates the row, and they
	-- are then this module's own copy of them.
	visit_external_id       VARCHAR(64)   NOT NULL,
	lane_external_id        VARCHAR(64)   NOT NULL,
	process_instance_id     VARCHAR(64)   NOT NULL,
	task_id                 VARCHAR(64)   NOT NULL CONSTRAINT uq_work_item_task UNIQUE,
	process_definition_key  VARCHAR(255)  NOT NULL,
	node_reference          VARCHAR(255)  NOT NULL,
	screen_external_id      VARCHAR(64)   NULL,
	-- The four states an item can be in, and all four have a writer: QUEUED when
	-- it is created and again when an operator puts it back, IN_PROGRESS when an
	-- operator claims it or takes it over from someone else, COMPLETED when it is
	-- finished and the process advances, FAILED when a lane reset clears it.
	--
	-- The `COLLATE` clause is load-bearing: this database's default collation is
	-- case-insensitive, so a plain list would also accept 'queued' and 'Queued'.
	-- Comparing under a binary collation makes the single casing something the
	-- database enforces rather than something the code remembers.
	status                  VARCHAR(16)   NOT NULL
		CONSTRAINT df_work_item_status DEFAULT 'QUEUED'
		CONSTRAINT ck_work_item_status
			CHECK (status COLLATE Latin1_General_100_BIN2 IN ('QUEUED', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
	assignee                VARCHAR(64)   NULL,
	queued_at               DATETIME2(3)  NOT NULL
		CONSTRAINT df_work_item_queued_at DEFAULT SYSUTCDATETIME(),
	started_at              DATETIME2(3)  NULL,
	completed_at            DATETIME2(3)  NULL,
	-- How long the operator took, computed on the server from the two timestamps
	-- above and NEVER taken from the request. A duration a client can supply is a
	-- duration a client can be wrong about, and this one feeds productivity
	-- reporting.
	completion_duration_sec INT           NULL,
	-- Set when the timer attached to the parked wait state fires — meaning this
	-- item took longer than the site allows.
	--
	-- ⚠️ A COLUMN, DELIBERATELY, AND NOT A STATUS. A breach does not move the item
	-- out of the queue it is in; it marks it. Making it a status would mean an
	-- operator's queue silently emptied when work went overdue, which is exactly
	-- backwards.
	sla_breached_at         DATETIME2(3)  NULL,
	event_data              NVARCHAR(MAX) NULL,
	corrected_event_data    NVARCHAR(MAX) NULL
);

-- The queue itself, as an index. Site, then status, then when it was queued —
-- exactly the question an operator's queue screen asks — and the columns the
-- screen displays are carried along in the index, so listing a queue never
-- fetches a single row.
--
-- It leads with the site column, as every index in this service does. A build
-- check insists on it, and the reason is measured rather than stylistic: the
-- shared code every read goes through puts the site condition first, and an index
-- that does not lead with it cannot be used for that condition, so the table gets
-- scanned instead. The lane-lock table in an earlier migration carries the full
-- account of what that cost.
CREATE INDEX ix_work_item_scope_status
	ON work_item (site_external_id, status, queued_at)
	INCLUDE (external_id, execution_id, lane_id, lane_external_id, visit_external_id,
		assignee, screen_external_id);

-- The other read: all of one visit's items. Lane reset uses it to fail every open
-- item belonging to the visit it is aborting.
CREATE INDEX ix_work_item_scope_execution
	ON work_item (site_external_id, execution_id);

-- --------------------------------------------------------------------------
-- work_item_audit — one row per action taken on an item, in typed columns. Who
-- did what, when, and how long they had held it.
--
-- Every action in the list has a writer: TAKE and TAKE_OVER when an operator
-- claims an item or takes it from a colleague, PARK when they put it back, ASSIGN
-- when it is given to someone, COMPLETE and FAIL at the end, and SLA_BREACH when
-- the timer fires.
--
-- The breach is recorded here as well as on the item itself, deliberately: the
-- column on the item says an item is overdue now, while this row says WHEN it
-- went overdue — which survives the item being completed afterwards.
--
-- The two durations feed the operator-productivity reporting the old system fed:
-- how long the acting operator held the item, and how long it had been sitting in
-- the queue. Both are computed on the server, never supplied by the caller.
-- --------------------------------------------------------------------------
CREATE TABLE work_item_audit (
	work_item_audit_id      BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_work_item_audit PRIMARY KEY,
	site_external_id        VARCHAR(64)  NOT NULL,
	work_item_id            BIGINT       NOT NULL
		CONSTRAINT fk_work_item_audit_item REFERENCES work_item (work_item_id),
	action                  VARCHAR(16)  NOT NULL
		CONSTRAINT ck_work_item_audit_action
			CHECK (action COLLATE Latin1_General_100_BIN2
				IN ('TAKE', 'TAKE_OVER', 'PARK', 'ASSIGN', 'COMPLETE', 'FAIL', 'SLA_BREACH')),
	actor                   VARCHAR(64)  NOT NULL,
	previous_assignee       VARCHAR(64)  NULL,
	occurred_at             DATETIME2(3) NOT NULL
		CONSTRAINT df_work_item_audit_occurred DEFAULT SYSUTCDATETIME(),
	processing_duration_sec INT          NULL,
	elapsed_sec             INT          NULL
);

CREATE INDEX ix_work_item_audit_scope_item
	ON work_item_audit (site_external_id, work_item_id, occurred_at);
