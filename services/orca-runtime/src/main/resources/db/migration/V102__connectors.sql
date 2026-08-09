-- Where the customer's own systems live, and what their answers mean.
--
-- WHAT A CONNECTOR IS
-- Somewhere in the middle of a gate process, the site's design says "ask the
-- terminal operating system whether this container may be collected". A connector
-- is that outbound call: an address, a path, a deadline. `connector_config` holds
-- them, one row per named connector per site.
--
-- `connector_route` holds the other half — what to do with the answer. It maps an
-- HTTP status code the customer's system returned onto a plain word the process
-- branches on.
--
-- WHY BOTH ARE DATA IN A TABLE, RATHER THAN CODE OR VALUES BAKED INTO A PROCESS
-- A process carries a connector's NAME, never its address. An administrator can
-- then repoint a customer system — a new host, a longer deadline — without
-- republishing every process that calls it.
--
-- The routing is the same argument taken one step further. Which answer means
-- "let the truck through" is a decision about the customer's own system, and the
-- customer's own system is theirs. A site that had to wait for a code change to
-- handle one more status code would be a site whose integrations belong to us
-- rather than to them, and the whole point of this platform is that a site's
-- processes belong to the site.

CREATE TABLE connector_config (
	site_external_id VARCHAR(64)  NOT NULL,
	connector_name   VARCHAR(64)  NOT NULL,
	base_url         VARCHAR(512) NOT NULL,
	request_path     VARCHAR(256) NOT NULL,

	-- How long to wait for an answer, in milliseconds. Every external call in this
	-- platform has a deadline AND a defined outcome when the deadline passes; no
	-- call waits indefinitely, because a truck is sitting at the barrier while it
	-- does.
	--
	-- Per connector rather than one global number, because a terminal operating
	-- system that answers in four seconds and a weighbridge that answers in two
	-- hundred milliseconds cannot share one value without the slow one dictating
	-- it for everybody.
	deadline_ms      INT          NOT NULL,

	is_enabled       BIT          NOT NULL
		CONSTRAINT df_connector_config_enabled DEFAULT 1,

	CONSTRAINT pk_connector_config PRIMARY KEY (site_external_id, connector_name)
);

-- Turns the HTTP status the customer's system returned into the plain word the
-- process branches on — 200 becomes APPROVED, 409 becomes ALREADY_COLLECTED, and
-- so on. That word is what reaches the decision point in the process design.
--
-- ⚠️ WHAT HAPPENS TO A STATUS NOBODY MAPPED, AND WHY IT IS THE INTENDED
-- BEHAVIOUR RATHER THAN A FALLBACK.
--
-- A status with no row here becomes the literal word `HTTP_<status>` — HTTP_503,
-- say. No process has a branch for that, so the decision takes its default path,
-- which sends the truck to a human.
--
-- That is exactly what should happen. An answer nobody wrote a branch for must
-- never turn into an implicit approval and open a barrier. Sending it to a person
-- is the only safe reading of "we do not know what this means".
CREATE TABLE connector_route (
	site_external_id VARCHAR(64) NOT NULL,
	connector_name   VARCHAR(64) NOT NULL,
	http_status      INT         NOT NULL,
	outcome          VARCHAR(32) NOT NULL,
	CONSTRAINT pk_connector_route PRIMARY KEY (site_external_id, connector_name, http_status)
);
