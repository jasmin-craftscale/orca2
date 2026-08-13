-- Lane monitor projection: the operator board's one row per lane.
--
-- WHAT THIS IS FOR
-- The console needs running visits next to queued work items and device indicators.
-- Those facts are owned by different runtime modules, so the board is not served by
-- joining their tables. This table is readmodel's own copy, updated when those
-- modules change something.
--
-- HOW IT IS SHAPED
-- One row per lane, keyed by the installation scope first. It is a projection, not
-- a system of record: if it is wrong it can be rebuilt from the owning modules'
-- facts, and if it is missing the owning records are still the truth.
--
-- WHY THERE IS NO RETENTION CLASS
-- The row count is bounded by configured lanes rather than by traffic. Trucks
-- update rows in place; they do not append rows here. Retention applies to the
-- traffic-growing sources, not to this derived copy.
CREATE TABLE lane_monitor (
	site_external_id                 VARCHAR(64)   NOT NULL,
	site_code                        VARCHAR(64)   NULL,
	site_is_primary                  BIT           NOT NULL
		CONSTRAINT df_lane_monitor_site_is_primary DEFAULT 0,
	area_id                          BIGINT        NULL,
	area_external_id                 VARCHAR(64)   NULL,
	area_code                        VARCHAR(64)   NULL,
	lane_id                          BIGINT        NOT NULL,
	lane_external_id                 VARCHAR(64)   NOT NULL,
	lane_code                        VARCHAR(64)   NULL,
	lane_name                        NVARCHAR(255) NULL,
	lane_priority                    INT           NULL,
	is_out_of_service                BIT           NOT NULL
		CONSTRAINT df_lane_monitor_out_of_service DEFAULT 0,
	traffic_status                   VARCHAR(16)   NOT NULL
		CONSTRAINT df_lane_monitor_traffic_status DEFAULT 'CLEAR'
		CONSTRAINT ck_lane_monitor_traffic_status
			CHECK (traffic_status COLLATE Latin1_General_100_BIN2
				IN ('CLEAR', 'ACTIVE', 'COMPLETED', 'MANUAL', 'FAILED')),
	traffic_color                    VARCHAR(16)   NOT NULL
		CONSTRAINT df_lane_monitor_traffic_color DEFAULT 'NEUTRAL'
		CONSTRAINT ck_lane_monitor_traffic_color
			CHECK (traffic_color COLLATE Latin1_General_100_BIN2
				IN ('NEUTRAL', 'BLUE', 'GREEN', 'AMBER', 'RED')),
	visit_external_id                VARCHAR(64)   NULL,
	plate                            VARCHAR(32)   NULL,
	queued_work_item_external_id     VARCHAR(64)   NULL,
	queued_work_item_queued_at       DATETIME2(3)  NULL,
	queued_work_item_assignee        VARCHAR(64)   NULL,
	queued_work_item_sla_breached_at DATETIME2(3)  NULL,
	gate_arm                         VARCHAR(64)   NULL,
	red_lamp                         VARCHAR(64)   NULL,
	orange_lamp                      VARCHAR(64)   NULL,
	green_lamp                       VARCHAR(64)   NULL,
	loop_inputs                      NVARCHAR(MAX) NULL,
	last_device_event_uuid           VARCHAR(64)   NULL,
	last_device_event_type           VARCHAR(32)   NULL,
	last_device_observed_at          DATETIME2(3)  NULL,
	updated_at                       DATETIME2(3)  NOT NULL
		CONSTRAINT df_lane_monitor_updated_at DEFAULT SYSUTCDATETIME(),
	CONSTRAINT pk_lane_monitor PRIMARY KEY (site_external_id, lane_id)
);

-- Scope first, because every read through the seam leads with the site predicate.
-- The board's normal order is the lane priority core publishes, with the lane
-- external id as the stable tiebreak.
CREATE INDEX ix_lane_monitor_scope_order
	ON lane_monitor (site_external_id, lane_priority, lane_external_id)
	INCLUDE (traffic_status, traffic_color, visit_external_id, plate,
		queued_work_item_external_id, queued_work_item_queued_at,
		queued_work_item_assignee, queued_work_item_sla_breached_at);

CREATE UNIQUE INDEX ux_lane_monitor_scope_lane_external
	ON lane_monitor (site_external_id, lane_external_id);
