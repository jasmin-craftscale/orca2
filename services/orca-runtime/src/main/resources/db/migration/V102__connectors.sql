-- orca-runtime · connector configuration and its response routing (§C2).
--
-- WHY THIS IS DATA AND NOT CODE, AND NOT PROCESS VARIABLES EITHER.
--
-- §C2 puts a connector's endpoint, its authentication and its certificate trust in
-- configuration, and a process carries a connector NAME rather than a URL — so an
-- administrator can repoint a customer system without republishing every process
-- that calls it. The same argument covers the routing below: which answer means
-- "let the truck through" is a site's decision about its own customer system, and
-- a site that had to have code changed to add a status code would be a site whose
-- integrations are ours rather than theirs (§A2).

CREATE TABLE connector_config (
	site_external_id VARCHAR(64)  NOT NULL,
	connector_name   VARCHAR(64)  NOT NULL,
	base_url         VARCHAR(512) NOT NULL,
	request_path     VARCHAR(256) NOT NULL,

	-- §B8: every external call has a deadline AND a defined outcome when it is
	-- exceeded. Per connector, because a terminal operating system that answers in
	-- four seconds and a weighbridge that answers in two hundred milliseconds
	-- cannot share one number without the slow one dictating it.
	deadline_ms      INT          NOT NULL,

	is_enabled       BIT          NOT NULL
		CONSTRAINT df_connector_config_enabled DEFAULT 1,

	CONSTRAINT pk_connector_config PRIMARY KEY (site_external_id, connector_name)
);

-- HTTP status -> the branch discriminator the process routes on.
--
-- The token is what reaches the exclusive gateway (§C2, and the execution
-- profile's "branch discriminator" refinement). A status with no row here becomes
-- `HTTP_<status>`, which no compiled process has a branch for — so it takes the
-- default flow to a human. That is the intended behaviour and not a fallback: an
-- answer nobody wrote a branch for must never become an implicit approval.
CREATE TABLE connector_route (
	site_external_id VARCHAR(64) NOT NULL,
	connector_name   VARCHAR(64) NOT NULL,
	http_status      INT         NOT NULL,
	outcome          VARCHAR(32) NOT NULL,
	CONSTRAINT pk_connector_route PRIMARY KEY (site_external_id, connector_name, http_status)
);
