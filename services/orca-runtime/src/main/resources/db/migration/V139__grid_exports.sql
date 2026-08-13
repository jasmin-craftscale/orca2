-- Stream 2 grid support that adds storage, rather than another recomputed view.
--
-- The completed-work grid reads terminal work items over a completion window, so
-- it needs a scope-leading access path on the timestamp it orders by. The export
-- job is readmodel's own traffic-growing record of a bounded CSV export; purge
-- is owned by the later retention stream, but the table declares its shape and
-- retention class in Java from the start.

CREATE INDEX ix_work_item_scope_completed_grid
	ON work_item (site_external_id, status, completed_at)
	INCLUDE (external_id, execution_id, lane_id, lane_external_id, visit_external_id,
		process_definition_key, node_reference, screen_external_id, assignee, queued_at,
		started_at, completion_duration_sec, sla_breached_at);

CREATE TABLE grid_export_job (
	grid_export_job_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_grid_export_job PRIMARY KEY,
	external_id         VARCHAR(64)   NOT NULL CONSTRAINT uq_grid_export_job_external_id UNIQUE,
	site_external_id   VARCHAR(64)   NOT NULL,
	grid_name          VARCHAR(32)   NOT NULL
		CONSTRAINT ck_grid_export_job_grid
			CHECK (grid_name COLLATE Latin1_General_100_BIN2
				IN ('LANE_MONITORS', 'QUEUE', 'ALERTS', 'COMPLETED_WORK')),
	status             VARCHAR(16)   NOT NULL
		CONSTRAINT ck_grid_export_job_status
			CHECK (status COLLATE Latin1_General_100_BIN2 IN ('COMPLETED', 'FAILED')),
	content_type       VARCHAR(100)  NOT NULL,
	file_name          VARCHAR(255)  NOT NULL,
	row_count          INT           NOT NULL,
	body               NVARCHAR(MAX) NULL,
	error_message      NVARCHAR(1000) NULL,
	created_at         DATETIME2(3)  NOT NULL CONSTRAINT df_grid_export_job_created DEFAULT SYSUTCDATETIME(),
	completed_at       DATETIME2(3)  NULL
);

CREATE INDEX ix_grid_export_job_scope_created
	ON grid_export_job (site_external_id, created_at)
	INCLUDE (external_id, grid_name, status, row_count, completed_at);
