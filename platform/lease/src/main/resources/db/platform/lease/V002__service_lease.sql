-- The lease: how the platform makes sure that only one instance at a time does
-- something only one instance should do.
--
-- WHAT PROBLEM THIS SOLVES
-- This platform runs several instances of a service at once, and some jobs must
-- not run twice — sweeping old rows, draining a queue, owning a particular lane.
-- An instance takes a lease before doing such work, renews it while working, and
-- loses it if it stalls.
--
-- ⚠️ THE HARD PART IS NOT WHO GETS THE LEASE. IT IS WHAT HAPPENS TO A HOLDER THAT
-- STALLED. A process paused long enough for its lease to expire does not know it
-- has stopped being the owner, and will happily finish the write it began. You
-- cannot guarantee that a stalled process is dead — so this design does not try.
-- It guarantees that its writes are REFUSED, using the fence token below.
--
-- WHERE THIS FILE ACTUALLY RUNS
-- Once per service, in that service's own schema. All six carry their own copy —
-- orca-core, orca-runtime, orca-edge, orca-portal, orca-sync and orca-fleet —
-- because each service's database login can reach only its own schema, so a
-- single shared lease table would not be writable by the services that have to
-- write it.
--
-- ⚠️ Editing this file — including its comments — invalidates the recorded
-- checksum in all six schemas at once, not one. Flyway checksums the whole file,
-- so the mismatch appears once per schema it was applied into.
--
-- Those near-identical copies are a known and accepted cost of giving each
-- service its own schema in one database, not an oversight. That trade was made
-- deliberately and is written down as a cost of the decision: coordination state
-- is duplicated per schema. Whether it should stay that way is still an open
-- question, and it is not settled here.

CREATE TABLE service_lease (
	-- What is being leased: the service, and a name within it. There is no third
	-- column for "which lane" or "which pair of sites" — that detail is part of
	-- the NAME, written into it by the caller, as in 'edge.ingest:lane:47'.
	--
	-- That is what lets one mechanism cover things as different as a nightly
	-- retention sweep, a replication feed reader, and an election for who owns one
	-- particular lane. A column per kind of scope would have meant a new column
	-- every time a new kind of thing needed leasing.
	service      VARCHAR(60)   NOT NULL,
	lease_name   VARCHAR(200)  NOT NULL,

	-- The instance identity currently holding it.
	holder_id    VARCHAR(200)  NOT NULL,

	-- ⚠️ THE COLUMN THAT MAKES THE WHOLE MECHANISM SAFE. It goes up by one on
	-- EVERY acquisition, so a new holder always has a higher number than the one
	-- before it.
	--
	-- A holder presents its token with every write the lease protects, and the
	-- check happens INSIDE that write's own statement — never as a separate
	-- question asked beforehand. Asking first and writing after leaves a gap
	-- between the two in which the lease can be lost, which is the exact defect
	-- this exists to remove. A write arriving with a token lower than the current
	-- one is refused by the database, so a stalled process that wakes up and
	-- finishes its work changes nothing.
	fence_token  BIGINT        NOT NULL,

	-- All three timestamps are the DATABASE's clock, and every comparison against
	-- an expiry happens inside the guarded update on the server. Two machines with
	-- clocks that disagree therefore cannot disagree about who holds a lease —
	-- neither of their clocks is consulted.
	acquired_at  DATETIME2(3)  NOT NULL,
	renewed_at   DATETIME2(3)  NOT NULL,
	expires_at   DATETIME2(3)  NOT NULL,

	CONSTRAINT pk_service_lease PRIMARY KEY (service, lease_name),
	CONSTRAINT ck_service_lease_fence_token CHECK (fence_token > 0)
);

-- WHAT THIS TABLE DELIBERATELY DOES NOT HAVE. Every other table in this platform
-- carries most of these, so their absence here is a decision rather than an
-- omission:
--
--   No site column and no access-scoping. This is coordination between processes,
--     not anybody's data. Whoever holds a lease is a background job running under
--     a system identity, not a person at a console.
--
--   No created-by / modified-by / created-at / modified-at set. The holder's
--     identity plus the three timestamps above ARE the record of who has it and
--     since when; a second audit set would say the same thing again, differently.
--
--   No soft delete — no `deleted_at`. A lease is released by expiring, or by the
--     next acquisition taking it with a higher token. A deletion flag would be
--     one more thing to check before acting, on the one table whose entire
--     purpose is to make check-then-act impossible.
--
--   No external identifier, no partitioning, no retention class. None applies to
--     a table with one row per thing being coordinated.
--
-- AND WHAT IT DELIBERATELY DOES NOT DECIDE
-- No lease duration, no renewal interval, no allowance for clock skew appears
-- anywhere in this file. Those are configuration, set per deployment, and a
-- service REFUSES TO START when either is unset or when the expiry is not longer
-- than the renewal interval. That startup check is where the rule lives; putting
-- a number here would make it look settled when it is not.
