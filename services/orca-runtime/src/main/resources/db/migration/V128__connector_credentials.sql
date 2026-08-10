-- Recoverable connector credentials belong to runtime, encrypted before they
-- reach SQL Server. The current row is bounded one-per-connector; the audit is
-- append-only and deliberately contains no principal or sealed material.

CREATE TABLE connector_credential (
	site_external_id  VARCHAR(64)  NOT NULL,
	connector_name    VARCHAR(64)  NOT NULL,
	auth_mode         VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL,
	auth_principal    VARCHAR(256) NULL,
	secret_ciphertext VARCHAR(MAX) NULL,
	secret_nonce      VARCHAR(64)  NULL,
	key_id            VARCHAR(64) COLLATE Latin1_General_100_BIN2 NULL,
	credential_version BIGINT      NOT NULL,
	updated_at        DATETIME2(7) NOT NULL,
	updated_by        VARCHAR(128) NOT NULL,

	CONSTRAINT pk_connector_credential
		PRIMARY KEY (site_external_id, connector_name),
	CONSTRAINT fk_connector_credential_config
		FOREIGN KEY (site_external_id, connector_name)
		REFERENCES connector_config (site_external_id, connector_name),
	CONSTRAINT ck_connector_credential_mode
		CHECK (auth_mode IN ('NONE', 'BASIC')),
	CONSTRAINT ck_connector_credential_version
		CHECK (credential_version >= 1),
	CONSTRAINT ck_connector_credential_actor
		-- Match Java String.isBlank for the ASCII whitespace/control characters
		-- representable in these VARCHAR identity fields: space and CHAR(9)..CHAR(13).
		CHECK (PATINDEX('%[^ ' + CHAR(9) + CHAR(10) + CHAR(11) + CHAR(12) + CHAR(13) + ']%',
			updated_by COLLATE Latin1_General_100_BIN2) > 0),
	CONSTRAINT ck_connector_credential_state
		CHECK (
			(auth_mode = 'NONE'
				AND auth_principal IS NULL
				AND secret_ciphertext IS NULL
				AND secret_nonce IS NULL
				AND key_id IS NULL)
			OR
			(auth_mode = 'BASIC'
				AND auth_principal IS NOT NULL
				AND PATINDEX('%[^ ' + CHAR(9) + CHAR(10) + CHAR(11) + CHAR(12) + CHAR(13) + ']%',
					auth_principal COLLATE Latin1_General_100_BIN2) > 0
				AND CHARINDEX(':', auth_principal) = 0
				AND secret_ciphertext IS NOT NULL
				AND LEN(LTRIM(RTRIM(secret_ciphertext))) > 0
				AND secret_nonce IS NOT NULL
				AND LEN(LTRIM(RTRIM(secret_nonce))) > 0
				AND key_id IS NOT NULL
				AND LEN(LTRIM(RTRIM(key_id))) > 0)
		)
);

CREATE TABLE connector_credential_audit (
	credential_audit_id BIGINT IDENTITY(1,1) NOT NULL
		CONSTRAINT pk_connector_credential_audit PRIMARY KEY,
	site_external_id   VARCHAR(64)  NOT NULL,
	connector_name     VARCHAR(64)  NOT NULL,
	credential_version BIGINT       NOT NULL,
	auth_mode          VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL,
	action             VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL,
	occurred_at        DATETIME2(7) NOT NULL,
	actor              VARCHAR(128) NOT NULL,

	CONSTRAINT ck_connector_credential_audit_mode
		CHECK (auth_mode IN ('NONE', 'BASIC')),
	CONSTRAINT ck_connector_credential_audit_action
		CHECK (action IN ('SET', 'REPLACE', 'CLEAR', 'REWRAP')),
	CONSTRAINT ck_connector_credential_audit_version
		CHECK (credential_version >= 1),
	CONSTRAINT ck_connector_credential_audit_actor
		CHECK (PATINDEX('%[^ ' + CHAR(9) + CHAR(10) + CHAR(11) + CHAR(12) + CHAR(13) + ']%',
			actor COLLATE Latin1_General_100_BIN2) > 0)
);

CREATE INDEX ix_connector_credential_audit_scope_connector_time
	ON connector_credential_audit (site_external_id, connector_name, occurred_at);
