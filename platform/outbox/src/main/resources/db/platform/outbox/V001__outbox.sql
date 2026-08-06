-- P1 · The transactional outbox and its per-consumer acknowledgement.
--
-- Defined ONCE, here, by the primitive that implements it. Applied into each
-- owning service's own schema by THAT service's own Flyway, with THAT service's
-- own credentials — so `core`, `runtime`, `edge` and `portal` each get their own
-- pair and nobody migrates anybody else's schema.
--
-- This is deliberately not a shared migrations module. A shared module would
-- centralise schema definition away from the services that own it and would not
-- remove the ordering dependency — it would only hide it behind one runner.
--
-- Object names are UNQUALIFIED on purpose. Each service's database user has its
-- own DEFAULT_SCHEMA, set by the bootstrap and asserted by V004__verify.sql, so
-- the same file lands in a different schema for each service. That is the whole
-- mechanism; if a default schema is ever wrong, tables silently land in `dbo`
-- where the next service can read them, which is why the bootstrap checks it.

-- ---------------------------------------------------------------------------
-- outbox — the fact, recorded in the same transaction as the fact itself.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
	-- The monotonic sequence. Consumers and the replication feed advance a
	-- cursor over it (§C1, §C2: "outbox feed by publish_seq cursor").
	publish_seq   BIGINT         IDENTITY(1,1) NOT NULL,

	-- Facts are ordered PER KEY, never globally (§D3, Ordering). A consumer that
	-- needs global order is using the wrong mechanism.
	ordering_key  VARCHAR(200)   NOT NULL,

	event_type    VARCHAR(120)   NOT NULL,
	payload       NVARCHAR(MAX)  NOT NULL,

	-- The database's clock, never an instance's (§D3, Time).
	created_at    DATETIME2(3)   NOT NULL CONSTRAINT df_outbox_created_at DEFAULT SYSUTCDATETIME(),

	CONSTRAINT pk_outbox PRIMARY KEY (publish_seq)
);

-- The relay claims the oldest unacknowledged row per ordering key. Without this
-- index that claim is a scan, and it runs on the gate path.
CREATE INDEX ix_outbox_ordering_key_seq ON outbox (ordering_key, publish_seq);

-- ---------------------------------------------------------------------------
-- outbox_delivery — one row per (fact, registered consumer).
--
-- A fact is acknowledged by EACH registered consumer separately, which is what
-- makes "a row is deletable only when every consumer has acknowledged it"
-- expressible as a query rather than as a hope. It is also what stops retention
-- from destroying data a peer has not taken (§B10, §C5).
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_delivery (
	publish_seq   BIGINT         NOT NULL,
	consumer      VARCHAR(120)   NOT NULL,

	-- PENDING  — offered, not yet acknowledged
	-- ACKED    — this consumer has taken it; it no longer holds up deletion
	-- PARKED   — delivery failed in a way retrying will not fix; a human looks
	status        VARCHAR(20)    NOT NULL CONSTRAINT df_outbox_delivery_status DEFAULT 'PENDING',

	attempts      INT            NOT NULL CONSTRAINT df_outbox_delivery_attempts DEFAULT 0,

	-- Who currently holds the claim, and until when. The relay's claim is a
	-- skip-locked read, so two instances never contend for the same row; these
	-- columns are what makes an abandoned claim recoverable rather than stuck.
	claimed_by    VARCHAR(200)   NULL,
	claimed_until DATETIME2(3)   NULL,

	acked_at      DATETIME2(3)   NULL,
	last_error    NVARCHAR(1000) NULL,

	CONSTRAINT pk_outbox_delivery PRIMARY KEY (publish_seq, consumer),
	CONSTRAINT fk_outbox_delivery_outbox FOREIGN KEY (publish_seq)
		REFERENCES outbox (publish_seq),
	CONSTRAINT ck_outbox_delivery_status CHECK (status IN ('PENDING', 'ACKED', 'PARKED')),
	-- An ACKED row without a timestamp is a row nobody can audit, and an
	-- un-acked row with one is a lie. Neither is allowed to exist.
	CONSTRAINT ck_outbox_delivery_acked_at
		CHECK ((status = 'ACKED' AND acked_at IS NOT NULL)
		    OR (status <> 'ACKED' AND acked_at IS NULL))
);

-- The relay's claim query: pending work for one consumer, oldest first.
CREATE INDEX ix_outbox_delivery_claim
	ON outbox_delivery (consumer, status, publish_seq)
	INCLUDE (claimed_until);
