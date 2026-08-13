-- Which designed workflow a visit runs, and the engine-correlation backstop.
--
-- The compiler deploys per-workflow definitions; a visit must record WHICH
-- workflow (and which published version of it) it was admitted under, or the
-- trace and dataset above cannot be read back against the design that produced
-- them. Both columns are NULL for visits of the hand-written gate process, which
-- predates the compiler — that is the honest representation, not a default.
ALTER TABLE execution ADD
	workflow_id        BIGINT NULL,
	definition_version INT    NULL;

-- One engine instance IS one visit. The correlation column has existed since the
-- table was created; this makes the database enforce what the code already
-- assumes, the same backstop discipline as the one-active-root-per-lane index.
CREATE UNIQUE INDEX ux_execution_process_instance
	ON execution (process_instance_id)
	WHERE process_instance_id IS NOT NULL;
