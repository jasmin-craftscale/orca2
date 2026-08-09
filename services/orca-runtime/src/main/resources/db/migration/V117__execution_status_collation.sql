-- Makes the visit's status check case-sensitive, like every other status check in
-- this schema.
--
-- WHAT WAS WRONG
-- The constraint on `execution.status` compared under the database's default
-- collation, which ignores case — so 'Failed' or 'active' would have been
-- accepted alongside the canonical spellings. Every other value-list check in
-- this system compares under a binary collation precisely to prevent that, and
-- for a specific reason: the system being translated from carries the same
-- conceptual value in two different casings in different places, and it survives
-- only because the collation hides the difference.
--
-- This one was missed. The migration that added the FAILED state recreated the
-- constraint in its original shape, while the two new checks it wrote alongside
-- followed the convention. Found by review before handover.
--
-- ⚠️ NOTHING WAS BROKEN BY IT. Only constant values are ever written to this
-- column today, so no wrong-cased row can exist. This is closing the gap, not
-- repairing damage.
--
-- WHY A NEW MIGRATION RATHER THAN EDITING THE OLD ONE
-- Because the old one has already been applied to development databases. Flyway
-- records a checksum over each migration file and refuses to start when one it
-- has applied no longer matches — which is exactly the protection that stops two
-- databases quietly disagreeing about their schema. The answer to a schema
-- mistake is always a new migration, never an edit to one that has run.
ALTER TABLE execution DROP CONSTRAINT ck_execution_status;
ALTER TABLE execution ADD CONSTRAINT ck_execution_status
	CHECK (status COLLATE Latin1_General_100_BIN2 IN ('ACTIVE', 'COMPLETED', 'MANUAL', 'FAILED'));
