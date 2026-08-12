-- The visit's step trace: one row per node the process actually entered, and what
-- that step saw.
--
-- WHAT THIS IS FOR
-- The compiler (execution/compiler) turns a site's designed workflow into BPMN,
-- and the engine runs it. What the engine keeps about that run is its own state —
-- correlation and routing keys — which is deliberately thin (`activity` history).
-- The business record of "which steps ran, when, and with what payload" belongs
-- to this service, next to the visit, in this service's own schema. This table is
-- that record.
--
-- A large share of the site-authored selector surface reads a step's payload —
-- `$.<nodeUuid>.dataset.…` and `$.workflow.node.<alias>` shapes. Without the
-- payload column those selectors have no answer, and a connector body built from
-- them silently resolves to empty, takes the other branch, and looks like it
-- worked. That defect class is why the payload is recorded at all.
--
-- Rows are written by the engine-event recorder in the same transaction that
-- advances the step where possible, and are keyed to the visit through
-- `execution_id` — never through engine state, so the trace survives anything
-- the engine forgets.
CREATE TABLE node_execution (
	node_execution_id  BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_node_execution PRIMARY KEY,
	external_id        VARCHAR(64)  NOT NULL CONSTRAINT uq_node_execution_external_id UNIQUE,
	site_external_id   VARCHAR(64)  NOT NULL,
	execution_id       BIGINT       NOT NULL,
	node_uuid          VARCHAR(64)  NOT NULL,
	node_type          VARCHAR(32)  NOT NULL,
	status             VARCHAR(32)  NOT NULL,
	-- What the step saw, as JSON text. Typed by the reader — the selector
	-- evaluator resolves against it exactly as it would against a dataset value.
	execution_payload  NVARCHAR(MAX) NULL,
	entered_at         DATETIME2(3) NOT NULL
		CONSTRAINT df_node_execution_entered DEFAULT SYSUTCDATETIME(),
	completed_at       DATETIME2(3) NULL,
	CONSTRAINT fk_node_execution_execution FOREIGN KEY (execution_id)
		REFERENCES execution (execution_id)
);

-- Every seam read leads with the scope predicate; a table without a
-- scope-leading index can only be scanned.
CREATE INDEX ix_node_execution_site ON node_execution (site_external_id, execution_id);

-- The read path is always "the newest row for this node in this visit" — a visit
-- that re-enters a node answers with the latest pass, never the first.
CREATE INDEX ix_node_execution_node_lookup
	ON node_execution (execution_id, node_uuid, node_execution_id DESC);
