-- The visit's dataset: one current value per key, next to the execution that
-- produced it.
--
-- WHAT THIS IS FOR
-- A connector request body is built by resolving the connector's field mappings
-- through the selector evaluator, and the evaluator asks its provider for
-- `$.workflow.dataset.<key>`. Until this table the runtime could not answer that
-- question about its own visits: a visit's data lived only as the engine's `v_*`
-- variable mirrors, which is enough to route a truck and not enough to call
-- anybody. Engine state stays thin — correlation and routing keys — and the
-- payload lives here.
--
-- ONE ROW PER KEY PER VISIT, DELIBERATELY. The system being replaced appends
-- every write and makes readers take the newest, so a visit that writes one key
-- four times carries four rows and every read is an ORDER BY over garbage. Here a
-- key has one current value: the writer upserts, and the unique index makes a
-- second row impossible rather than merely unusual.
CREATE TABLE visit_dataset (
	visit_dataset_id  BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_visit_dataset PRIMARY KEY,
	site_external_id  VARCHAR(64)  NOT NULL,
	execution_id      BIGINT       NOT NULL,
	data_key          NVARCHAR(255) NOT NULL,
	-- Values arrive as whatever the source produced — a scan string, a
	-- connector's extracted field, a clerk's answer. Stored as text, typed by
	-- the reader, exactly as the selector evaluator expects.
	data_value        NVARCHAR(MAX) NULL,
	written_at        DATETIME2(3) NOT NULL
		CONSTRAINT df_visit_dataset_written DEFAULT SYSUTCDATETIME(),
	CONSTRAINT fk_visit_dataset_execution FOREIGN KEY (execution_id)
		REFERENCES execution (execution_id)
);

-- The upsert's target: a second row per (visit, key) is a constraint violation,
-- not a duplicate to sort out later.
CREATE UNIQUE INDEX ux_visit_dataset_key ON visit_dataset (execution_id, data_key);

-- Scope-leading access path, as every table carrying the scope column has.
CREATE INDEX ix_visit_dataset_site ON visit_dataset (site_external_id, execution_id);
