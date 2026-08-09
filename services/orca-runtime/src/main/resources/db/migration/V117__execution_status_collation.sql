-- orca-runtime · Phase 3 review — align ck_execution_status with the
-- binary-collation convention.
--
-- V115 re-created the constraint (adding FAILED) in V101's original shape — a
-- bare IN, comparing under the database's case-insensitive collation, so a
-- case-mismatched 'Failed' would pass. Every enum CHECK written since Phase 2
-- §7.1 applies COLLATE Latin1_General_100_BIN2 precisely because that trap is
-- how 1.x's dual-casing enums survived; V115's own two new CHECKs and V116's
-- follow the convention, and this was the one that did not. Latent (only
-- constants are written today), found by the pre-handover review; a new
-- migration rather than an edit, because V115 has been applied to development
-- databases and checksums do not drift (§B7).
ALTER TABLE execution DROP CONSTRAINT ck_execution_status;
ALTER TABLE execution ADD CONSTRAINT ck_execution_status
	CHECK (status COLLATE Latin1_General_100_BIN2 IN ('ACTIVE', 'COMPLETED', 'MANUAL', 'FAILED'));
