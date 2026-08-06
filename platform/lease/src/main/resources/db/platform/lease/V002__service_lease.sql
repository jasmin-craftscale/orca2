-- P2 · The fenced coordination lease.
--
-- One per service schema, in the owning service's own schema — `core`,
-- `runtime`, `edge`, `portal`, `sync` and `fleet` — because a service can reach
-- only its own schema by credential, so a single shared lease table would not be
-- writable by the services that must write it (§C2).
--
-- The six near-identical copies are a KNOWN COST of ADR-004, not an oversight:
-- the ADR says so in as many words ("coordination state is duplicated per
-- schema"), and the open-questions register carries it as item S2. It is not
-- resolved here.

CREATE TABLE service_lease (
	-- The natural key. Per-client, per-site-pair and per-lane scope RIDES IN
	-- `lease_name` — which is how one mechanism covers a retention job, a feed
	-- reader and a per-lane owner election alike
	-- (e.g. 'edge.ingest:lane:<lane_id>').
	service      VARCHAR(60)   NOT NULL,
	lease_name   VARCHAR(200)  NOT NULL,

	-- The instance identity currently holding it.
	holder_id    VARCHAR(200)  NOT NULL,

	-- Increments on EVERY acquisition, and is verified INSIDE the claim
	-- statement, never in a preceding check. A holder presents its token with
	-- any write the lease protects; a stale token is refused. You cannot
	-- guarantee a stalled process is dead — you can guarantee its writes are
	-- refused.
	fence_token  BIGINT        NOT NULL,

	-- All three are the DATABASE's clock. Every expiry comparison happens
	-- server-side inside the guarded UPDATE, so two hosts with disagreeing
	-- clocks cannot disagree about who holds the lease (§D3, Time).
	acquired_at  DATETIME2(3)  NOT NULL,
	renewed_at   DATETIME2(3)  NOT NULL,
	expires_at   DATETIME2(3)  NOT NULL,

	CONSTRAINT pk_service_lease PRIMARY KEY (service, lease_name),
	CONSTRAINT ck_service_lease_fence_token CHECK (fence_token > 0)
);

-- What this table DELIBERATELY LACKS, and why (§C2):
--
--   site_id and any row-level-security policy — this is process-coordination
--     state, not tenant data. Every holder runs under the system context.
--
--   the audit quartet — holder_id with the three timestamps IS the record.
--
--   soft delete — a lease is released by expiry or by the next acquisition with
--     a higher token. A `deleted_at` would be a second way to get check-then-act
--     wrong on the one table whose entire purpose is to make check-then-act
--     impossible.
--
--   external_id, partitioning, a retention class — none applies.
--
-- Nothing here states a lease DURATION, a renewal interval or a clock-skew
-- allowance. Those are profile configuration that must be set explicitly, and a
-- service refuses to start when either is unset or when expiry <= the renewal
-- interval. That startup validation is the specification; this file asserts no
-- number, and neither does the architecture.
