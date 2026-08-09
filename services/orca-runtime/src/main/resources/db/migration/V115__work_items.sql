-- orca-runtime · Phase 3 WP1 — the work item and its trail (§C2, sheet §1/§3).
--
-- Numbered AFTER the Flowable set (V110–V114) because Flyway refuses an
-- out-of-order migration on a database that has already applied them — the
-- phase-1 demo database is exactly such a database.
--
-- The unit of human work. A process step automation cannot finish parks the
-- engine at a wait state and creates one of these IN THE SAME TRANSACTION —
-- there is no separate HTTP call and no window where one exists without the
-- other (inversion 1 of docs/work-items-schema-from-1x.md §0). Completing it
-- advances the parked process, also in one transaction.
--
-- Translated from 1.x `work_items`, with the sheet's drops applied:
--   * `iteration` does not port — the dead SLA remnant (never incremented).
--     SLA in 2.0 is an engine timer (WP3), and its record is a column here
--     (`sla_breached_at`), not a status — the dead ESCALATE_* statuses are
--     not inherited (sheet §2).
--   * `group_id` does not port — never wired as an FK in 1.x, set to 0.
--   * the denormalized customer/site/area ids do not port — derivable from
--     the lane and the execution.
--   * `mipn_type` (WORKFLOW/SUBFLOW discriminator) does not port yet —
--     subflows are not modelled in this repository (BPMN profile §2 lists
--     subProcess as unsettled), so the discriminator would be a column with
--     one writable value. The node reference is the BPMN task definition key
--     plus the process definition key, which is what the engine actually
--     parks on. Recorded in the phase report.
--   * `event_data`/`corrected_event_data` are INLINE for now, stated openly:
--     §C2's content-addressed payload store (`payload_blob`) is not built in
--     any phase yet, and ADR-017's thresholds are unset (register #27). When
--     the store lands, these columns join it; until then inline text is the
--     honest shape, bounded by the retention class below.
--
-- Statuses: exactly the four with a writer (sheet §2). QUEUED (creation;
-- park re-queues), IN_PROGRESS (the guarded claim; takeover), COMPLETED
-- (complete-and-advance), FAILED (lane reset fails the visit and its open
-- work items together). ESCALATE_TO_LANE / ESCALATE_TO_CUSTOMER / RE_QUEUED
-- are 1.x declarations with no writer and are deliberately absent.
--
-- Queue membership is status + queued_at + assignee. THERE IS NO QUEUE TABLE
-- (§C2) — 1.x's second representation, an in-memory array in a tracker
-- service, was not a system of record and does not port.

-- --------------------------------------------------------------------------
-- The visit learns one more terminal state: FAILED, written by lane reset
-- (§C2's POST /lanes/{id}/reset — abort the visit, fail its open work items,
-- one transaction). Phase 1's three states had no writer for an abort;
-- Phase 3's lane reset is that writer, and it is what gives work_item.FAILED
-- its writer too.
-- --------------------------------------------------------------------------
ALTER TABLE execution DROP CONSTRAINT ck_execution_status;
ALTER TABLE execution ADD CONSTRAINT ck_execution_status
	CHECK (status IN ('ACTIVE', 'COMPLETED', 'MANUAL', 'FAILED'));

-- --------------------------------------------------------------------------
-- work_item — one unit of human work, parked on one engine task.
--
-- `task_id` is the engine's own handle for the wait state this item parks on.
-- It is UNIQUE: one item per parked task, created by the engine transaction
-- that parked it. Completion presents it back to the engine, which is what
-- makes an out-of-order submit REFUSABLE (inversion 3): a task the engine is
-- not waiting on does not resolve, and the whole completion rolls back.
--
-- `assignee` is a user external id in core's published vocabulary; NULL means
-- unassigned. Runtime does not FK into core's schema (ADR-004 — it cannot),
-- and the value is resolved through core's published views at the boundary.
-- --------------------------------------------------------------------------
CREATE TABLE work_item (
	work_item_id            BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_work_item PRIMARY KEY,
	external_id             VARCHAR(64)   NOT NULL CONSTRAINT uq_work_item_external_id UNIQUE,
	site_external_id        VARCHAR(64)   NOT NULL,
	execution_id            BIGINT        NOT NULL
		CONSTRAINT fk_work_item_execution REFERENCES execution (execution_id),
	lane_id                 BIGINT        NOT NULL,
	-- The visit and lane in published vocabulary (§B8 — interfaces speak
	-- external ids). NOT a rule-6 violation to store them: the module wall
	-- keeps workitem out of execution's tables, so these linkage values arrive
	-- once, across the api seam, in the creating transaction — the same
	-- precedent as lane_session carrying lane_external_id beside lane_id.
	visit_external_id       VARCHAR(64)   NOT NULL,
	lane_external_id        VARCHAR(64)   NOT NULL,
	process_instance_id     VARCHAR(64)   NOT NULL,
	task_id                 VARCHAR(64)   NOT NULL CONSTRAINT uq_work_item_task UNIQUE,
	process_definition_key  VARCHAR(255)  NOT NULL,
	node_reference          VARCHAR(255)  NOT NULL,
	screen_external_id      VARCHAR(64)   NULL,
	status                  VARCHAR(16)   NOT NULL
		CONSTRAINT df_work_item_status DEFAULT 'QUEUED'
		CONSTRAINT ck_work_item_status
			CHECK (status COLLATE Latin1_General_100_BIN2 IN ('QUEUED', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
	assignee                VARCHAR(64)   NULL,
	queued_at               DATETIME2(3)  NOT NULL
		CONSTRAINT df_work_item_queued_at DEFAULT SYSUTCDATETIME(),
	started_at              DATETIME2(3)  NULL,
	completed_at            DATETIME2(3)  NULL,
	-- Server-computed as completed_at - started_at, NEVER taken from the
	-- request (sheet §1). The one persisted SLA outcome 1.x had, kept.
	completion_duration_sec INT           NULL,
	-- WP3 writes this when the boundary timer on the wait state fires. A
	-- COLUMN, not a status: the sheet's §2 choice stated — a breach does not
	-- move the item out of its queue, it marks it, so the dead ESCALATE_*
	-- statuses stay dead.
	sla_breached_at         DATETIME2(3)  NULL,
	event_data              NVARCHAR(MAX) NULL,
	corrected_event_data    NVARCHAR(MAX) NULL
);

-- The queue read: status + queued_at under the scope, covering what the grid
-- lists. Leads with the scope column — ScopeIndexRule insists, and the
-- lane_session key order comment in V101 is the measured reason why.
CREATE INDEX ix_work_item_scope_status
	ON work_item (site_external_id, status, queued_at)
	INCLUDE (external_id, execution_id, lane_id, lane_external_id, visit_external_id,
		assignee, screen_external_id);

-- Lane reset walks a visit's open items; the §C2 data model's EXECUTION ||--o{
-- WORK_ITEM edge, as an index.
CREATE INDEX ix_work_item_scope_execution
	ON work_item (site_external_id, execution_id);

-- --------------------------------------------------------------------------
-- work_item_audit — one row per action on an item (sheet §3).
--
-- Typed columns where 1.x had a loose child of the audit trail. The action
-- list is 2.0's writers: TAKE / TAKE_OVER / PARK / ASSIGN / COMPLETE / FAIL,
-- plus SLA_BREACH (WP3's timer records the breach here as well as on the
-- item, so the trail carries WHEN it fired even after the item completes).
-- 1.x's ESCALATE action had a writer only in the sense that the SLA path
-- would have written it; 2.0's SLA path is real and writes SLA_BREACH.
--
-- `processing_duration_sec` (how long the acting operator held the item) and
-- `elapsed_sec` (how long since it queued) feed the operator-productivity
-- reads 1.x fed; both are server-computed.
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
