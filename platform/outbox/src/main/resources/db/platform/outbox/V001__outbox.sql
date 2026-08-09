-- The two tables behind the transactional outbox: how one service tells another
-- that something happened, without a message broker and without ever losing or
-- duplicating the news.
--
-- WHAT PROBLEM THIS SOLVES
-- A service records a fact — a visit completed — and something elsewhere needs to
-- know. Send the message first and the recording can fail: news of something that
-- never happened. Record first and send after, and the send can fail: something
-- happened that nobody was told about. There is no ordering of two separate
-- systems that fixes this.
--
-- So the fact and the row saying "this needs publishing" are written in ONE
-- database transaction. They commit together or not at all. A relay then reads
-- this table and offers each row to every registered consumer until each one
-- acknowledges. That gives the same guarantee a message broker would — delivered
-- at least once, in order per key — at the cost of a table instead of a server to
-- run, patch and monitor.
--
-- WHERE THIS FILE ACTUALLY RUNS
-- It is written once, here, beside the code that implements the mechanism. It is
-- then applied SEPARATELY into each owning service's own schema, by that
-- service's own migration run, using that service's own database credentials — so
-- several services each end up with their own pair of these tables, and no
-- service ever migrates another's schema.
--
-- That is deliberately not a shared migrations module. A shared module would move
-- schema definition away from the services that own it, and it would not remove
-- the ordering dependency between them — only hide it behind a single runner.
--
-- ⚠️ THE TABLE NAMES ARE UNQUALIFIED, AND THAT IS THE WHOLE MECHANISM. Each
-- service's database login has its own default schema, set up by the scripts
-- under deploy/bootstrap and checked by the last of them. The same file therefore
-- lands in a different schema for each service that applies it. If a default
-- schema is ever wrong, these tables land silently in the database's own default
-- schema — where the next service to run can see them — which is precisely why
-- the bootstrap verifies it rather than assuming it.

-- ---------------------------------------------------------------------------
-- outbox — the news itself, one row per fact, written in the same transaction as
-- the fact it describes.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
	-- An always-increasing number, assigned by the database. It is what a
	-- consumer's position is expressed in: each one remembers the last number it
	-- has taken and asks for what came after.
	publish_seq   BIGINT         IDENTITY(1,1) NOT NULL,

	-- What this fact is about — a lane, a visit, a device. Facts are delivered in
	-- order WITHIN one key, and deliberately not in order across keys, so two
	-- lanes never wait for each other.
	--
	-- ⚠️ A consumer that genuinely needs a single global order is using the wrong
	-- mechanism, not this one with a constant key.
	ordering_key  VARCHAR(200)   NOT NULL,

	event_type    VARCHAR(120)   NOT NULL,
	payload       NVARCHAR(MAX)  NOT NULL,

	-- Stamped by the database's own clock, never by the machine that inserted the
	-- row. Anything two instances have to agree on is timed by the database here.
	created_at    DATETIME2(3)   NOT NULL CONSTRAINT df_outbox_created_at DEFAULT SYSUTCDATETIME(),

	CONSTRAINT pk_outbox PRIMARY KEY (publish_seq)
);

-- The relay takes the oldest unacknowledged row for each ordering key. Without
-- this index that is a table scan — and it happens on the path a truck is waiting
-- on.
CREATE INDEX ix_outbox_ordering_key_seq ON outbox (ordering_key, publish_seq);

-- ---------------------------------------------------------------------------
-- outbox_delivery — one row per fact per registered consumer. Three consumers
-- means three rows, acknowledged independently.
--
-- WHY THE ACKNOWLEDGEMENT IS PER CONSUMER RATHER THAN PER FACT
-- Because it turns "this row may be deleted once EVERY consumer has taken it"
-- into a question the database can answer, instead of something the code hopes is
-- true. That matters most for the sweep that deletes old rows: retention must
-- never destroy a fact that a peer — a replicating tier, a slow consumer that has
-- been offline for a day — has not yet taken. With one flag per fact there would
-- be no way to know.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_delivery (
	publish_seq   BIGINT         NOT NULL,
	consumer      VARCHAR(120)   NOT NULL,

	-- PENDING  — offered, not yet acknowledged
	-- ACKED    — this consumer has taken it; it no longer holds up deletion
	-- PARKED   — delivery failed in a way retrying will not fix; a human looks
	status        VARCHAR(20)    NOT NULL CONSTRAINT df_outbox_delivery_status DEFAULT 'PENDING',

	attempts      INT            NOT NULL CONSTRAINT df_outbox_delivery_attempts DEFAULT 0,

	-- Who is currently working on this delivery, and until when.
	--
	-- Two relay instances never fight over the same row: each claims work with a
	-- read that skips rows another instance already holds. These two columns are
	-- what makes a claim RECOVERABLE — an instance that dies mid-delivery leaves a
	-- claim that expires, and the next instance picks it up. Without an expiry the
	-- row would be held forever by a process that no longer exists.
	claimed_by    VARCHAR(200)   NULL,
	claimed_until DATETIME2(3)   NULL,

	acked_at      DATETIME2(3)   NULL,
	last_error    NVARCHAR(1000) NULL,

	CONSTRAINT pk_outbox_delivery PRIMARY KEY (publish_seq, consumer),
	CONSTRAINT fk_outbox_delivery_outbox FOREIGN KEY (publish_seq)
		REFERENCES outbox (publish_seq),
	CONSTRAINT ck_outbox_delivery_status CHECK (status IN ('PENDING', 'ACKED', 'PARKED')),
	-- The status and the acknowledgement time cannot contradict each other. An
	-- acknowledged row with no timestamp is one nobody can audit; an
	-- unacknowledged row with a timestamp is simply false. Neither can be written.
	CONSTRAINT ck_outbox_delivery_acked_at
		CHECK ((status = 'ACKED' AND acked_at IS NOT NULL)
		    OR (status <> 'ACKED' AND acked_at IS NULL))
);

-- The relay's claim query, as an index: outstanding work for one consumer, oldest
-- first, with the claim expiry carried along so the relay can tell an abandoned
-- claim from a live one without fetching the row.
CREATE INDEX ix_outbox_delivery_claim
	ON outbox_delivery (consumer, status, publish_seq)
	INCLUDE (claimed_until);
