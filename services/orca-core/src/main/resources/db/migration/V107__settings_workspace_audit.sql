-- orca-core · WP4 — the settings registry, the per-user workspace, site
-- branding/localization, and the audit trail.
--
-- Translated from docs/core-config-schema-from-1x.md §4. §C1's own words for
-- settings are the specification: "a registry of known keys, validated writes,
-- history appended, secrets rejected."
--
--   * setting_definition is the registry — seeded (pinned UUIDv5, namespace
--     uuid5(NAMESPACE_URL, 'orca:2.0:setting-registry')) with the keys the
--     sheet names worth carrying: log level, the two work-item SLA defaults,
--     retention windows. 1.x's 403 keys across 21 services mostly dissolve —
--     2.0 has six services and env/keystore for infrastructure tunables.
--     EVERY secret-shaped 1.x key (*_CLIENT_SECRET, CLUSTER_PASSWORD,
--     SMTP_PASSWORD, AZURE_*_KEY, VAPID_PRIVATE_KEY) is deliberately ABSENT,
--     and the service refuses secret-shaped keys with a typed error before it
--     even consults the registry (rule 8).
--   * setting_value holds the current value (one per key); setting_history
--     appends every change — TRAFFIC-GROWING, retention class 'audit'
--     (PROVISIONAL, like every class name until the list is reconciled).
--   * grid_definition names its title column honestly (1.x called the display
--     name "table_name"). Only grids whose backing features THIS phase built
--     are seeded, with 2.0-minted codes — the 1.x 38-grid list is not
--     row-level extractable from the sheet, and most of it belongs to later
--     phases anyway (recorded in the report). Default column layouts are a
--     JSON document per grid (rule 11 — a UI schema, read and written whole),
--     NULL until a console defines them.
--   * user_grid_column_preference is RELATIONAL, deliberately (rule 11): 1.x
--     kept prefs as JSON blobs and paid for it with migrations that
--     string-rewrite inside them. A column preference is three scalars per
--     (user, grid, column) — exactly what rows are for.
--   * saved_filter keeps its expression as JSON WITH a schema version: a
--     filter is an expression tree (document-shaped, rule 11 satisfied
--     deliberately), and the version column is what spares 2.0 the blind
--     string-rewrite 1.x needed. Unique (user, grid, name); at most one
--     default per (user, grid) by filtered unique index — 1.x enforced
--     neither.
--   * site_color / site_language are per-site branding — created EMPTY (rule
--     10: migrations create no tenant data; 1.x seeded 8 colors + "english"
--     per site, which is exactly the seeding 2.0 refuses). Hex is normalized
--     to #RRGGBBAA by CHECK; 1.x mixed 6- and 8-digit values.
--   * audit_event is core's FIRST traffic-growing table: growth + retention
--     class 'audit' declared in Java, scope-leading index here. Its
--     site_external_id deliberately has NO FK: the value is the configured
--     installation site (the scope), and an audit write must not fail because
--     configuration data is mid-bootstrap. Typed columns, one casing.

-- --------------------------------------------------------------------------
-- settings
-- --------------------------------------------------------------------------
CREATE TABLE setting_definition (
	setting_definition_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_setting_definition PRIMARY KEY,
	external_id   VARCHAR(64)   NOT NULL CONSTRAINT uq_setting_definition_external_id UNIQUE,
	config_realm  VARCHAR(16)   NOT NULL
		CONSTRAINT df_setting_definition_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_setting_definition_realm CHECK (config_realm = 'INSTALLATION'),
	setting_key   VARCHAR(200)  NOT NULL CONSTRAINT uq_setting_definition_key UNIQUE,
	value_type    VARCHAR(16)   NOT NULL CONSTRAINT ck_setting_definition_type
		CHECK (value_type COLLATE Latin1_General_100_BIN2 IN ('STRING', 'INTEGER', 'BOOLEAN')),
	default_value NVARCHAR(500) NULL,
	description   NVARCHAR(500) NULL,
	retired_at    DATETIME2(3)  NULL,
	created_at    DATETIME2(3)  NOT NULL CONSTRAINT df_setting_definition_created_at DEFAULT SYSUTCDATETIME()
);

CREATE TABLE setting_value (
	setting_value_id      BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_setting_value PRIMARY KEY,
	setting_definition_id BIGINT         NOT NULL CONSTRAINT fk_setting_value_definition
		REFERENCES setting_definition (setting_definition_id)
		CONSTRAINT uq_setting_value_definition UNIQUE,
	config_realm          VARCHAR(16)    NOT NULL
		CONSTRAINT df_setting_value_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_setting_value_realm CHECK (config_realm = 'INSTALLATION'),
	setting_value         NVARCHAR(2000) NOT NULL,
	updated_by            NVARCHAR(200)  NOT NULL,
	updated_at            DATETIME2(3)   NOT NULL CONSTRAINT df_setting_value_updated_at DEFAULT SYSUTCDATETIME()
);

CREATE TABLE setting_history (
	setting_history_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_setting_history PRIMARY KEY,
	setting_definition_id BIGINT         NOT NULL CONSTRAINT fk_setting_history_definition
		REFERENCES setting_definition (setting_definition_id),
	config_realm          VARCHAR(16)    NOT NULL
		CONSTRAINT df_setting_history_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_setting_history_realm CHECK (config_realm = 'INSTALLATION'),
	old_value             NVARCHAR(2000) NULL,
	new_value             NVARCHAR(2000) NOT NULL,
	changed_by            NVARCHAR(200)  NOT NULL,
	changed_at            DATETIME2(3)   NOT NULL CONSTRAINT df_setting_history_changed_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_setting_history_definition ON setting_history (setting_definition_id, changed_at);
GO

INSERT INTO setting_definition (external_id, setting_key, value_type, default_value, description) VALUES ('357ad8b4-d3d8-59c0-8ca3-3407039e53a8', 'LOG_LEVEL', 'STRING', N'INFO', N'Root log level for the on-site services; a genuine setting 1.x carried');
INSERT INTO setting_definition (external_id, setting_key, value_type, default_value, description) VALUES ('9bb589c6-5b4e-53a8-a70a-bcb5d7ef4dd0', 'EXPECTED_PROCESSING_TIME_SEC', 'INTEGER', NULL, N'Work-item SLA default: expected processing seconds (runtime reads this in the work-item phase)');
INSERT INTO setting_definition (external_id, setting_key, value_type, default_value, description) VALUES ('a1fd8118-e1ec-5203-92b6-23c3a868c1eb', 'MAX_PROCESSING_TIME_SEC', 'INTEGER', NULL, N'Work-item SLA default: maximum processing seconds (runtime reads this in the work-item phase)');
INSERT INTO setting_definition (external_id, setting_key, value_type, default_value, description) VALUES ('30dedfaa-52e3-57ff-9059-f244b9982317', 'RETENTION_DAYS_DEVICE_EVENT', 'INTEGER', NULL, N'Retention window for the device_event class. PROVISIONAL - the class list is not closed (phase-1 report)');
INSERT INTO setting_definition (external_id, setting_key, value_type, default_value, description) VALUES ('ccb4273a-b563-5098-b51f-43cca57de821', 'RETENTION_DAYS_VISIT', 'INTEGER', NULL, N'Retention window for the visit class. PROVISIONAL');
INSERT INTO setting_definition (external_id, setting_key, value_type, default_value, description) VALUES ('e60bb768-da74-5413-961c-3557e04d7b04', 'RETENTION_DAYS_DEVICE_COMMAND', 'INTEGER', NULL, N'Retention window for the device_command class. PROVISIONAL');
INSERT INTO setting_definition (external_id, setting_key, value_type, default_value, description) VALUES ('d3730dab-62fb-5656-8683-0433616f5ab5', 'RETENTION_DAYS_AUDIT', 'INTEGER', NULL, N'Retention window for the audit class this phase introduces. PROVISIONAL');
GO

-- --------------------------------------------------------------------------
-- workspace
-- --------------------------------------------------------------------------
CREATE TABLE grid_definition (
	grid_definition_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_grid_definition PRIMARY KEY,
	external_id     VARCHAR(64)   NOT NULL CONSTRAINT uq_grid_definition_external_id UNIQUE,
	config_realm    VARCHAR(16)   NOT NULL
		CONSTRAINT df_grid_definition_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_grid_definition_realm CHECK (config_realm = 'INSTALLATION'),
	code            VARCHAR(64)   NOT NULL CONSTRAINT uq_grid_definition_code UNIQUE,
	-- The honest name for what 1.x misleadingly called "table_name".
	title           NVARCHAR(200) NOT NULL,
	-- The default column layout, as a JSON document per grid (rule 11).
	default_columns NVARCHAR(MAX) NULL,
	retired_at      DATETIME2(3)  NULL,
	created_at      DATETIME2(3)  NOT NULL CONSTRAINT df_grid_definition_created_at DEFAULT SYSUTCDATETIME()
);
GO

INSERT INTO grid_definition (external_id, code, title) VALUES ('08140a90-c04a-5a39-918c-ccfea39e71b0', 'USER_MANAGEMENT', N'User Management');
INSERT INTO grid_definition (external_id, code, title) VALUES ('1bb4878c-69e3-5133-aeb4-ca65626e5e23', 'ROLE_MANAGEMENT', N'Role Management');
INSERT INTO grid_definition (external_id, code, title) VALUES ('7517da6f-33a6-5564-9ea2-92de74e9f2dc', 'TEAM_MANAGEMENT', N'Team Management');
INSERT INTO grid_definition (external_id, code, title) VALUES ('c93dd376-1f90-5a40-a71e-1e8fa01fe621', 'SHIFT_TEMPLATES', N'Shift Templates');
INSERT INTO grid_definition (external_id, code, title) VALUES ('77e94808-d978-5d2c-8aae-b35495c2ffc6', 'BREAK_TEMPLATES', N'Break Templates');
INSERT INTO grid_definition (external_id, code, title) VALUES ('2eed7b16-2438-5c1e-8ed6-ca8ddd037356', 'DEVICE_REGISTRY', N'Device Registry');
INSERT INTO grid_definition (external_id, code, title) VALUES ('9f3de4fc-c72b-5db9-a2ec-981af349d9c4', 'SETTINGS', N'Settings');
INSERT INTO grid_definition (external_id, code, title) VALUES ('8f072a9e-57b1-58c2-97e9-cfae77930a3d', 'AUDIT_HISTORY', N'Audit History');
GO

CREATE TABLE user_grid_column_preference (
	user_grid_column_preference_id BIGINT IDENTITY(1,1) NOT NULL
		CONSTRAINT pk_user_grid_column_preference PRIMARY KEY,
	user_id            BIGINT       NOT NULL CONSTRAINT fk_user_grid_column_preference_user
		REFERENCES user_account (user_id),
	grid_definition_id BIGINT       NOT NULL CONSTRAINT fk_user_grid_column_preference_grid
		REFERENCES grid_definition (grid_definition_id),
	config_realm       VARCHAR(16)  NOT NULL
		CONSTRAINT df_user_grid_column_preference_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_user_grid_column_preference_realm CHECK (config_realm = 'INSTALLATION'),
	column_code        VARCHAR(64)  NOT NULL,
	display_order      INT          NOT NULL,
	width_px           INT          NULL,
	is_visible         BIT          NOT NULL CONSTRAINT df_user_grid_column_preference_visible DEFAULT 1,
	retired_at         DATETIME2(3) NULL,
	created_at         DATETIME2(3) NOT NULL
		CONSTRAINT df_user_grid_column_preference_created_at DEFAULT SYSUTCDATETIME()
);

-- The unique 1.x never had on (user, grid) — here per column, since the model
-- is relational rather than a blob.
CREATE UNIQUE INDEX ux_user_grid_column_preference
	ON user_grid_column_preference (user_id, grid_definition_id, column_code)
	WHERE retired_at IS NULL;

CREATE TABLE saved_filter (
	saved_filter_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_saved_filter PRIMARY KEY,
	external_id        VARCHAR(64)    NOT NULL CONSTRAINT uq_saved_filter_external_id UNIQUE,
	user_id            BIGINT         NOT NULL CONSTRAINT fk_saved_filter_user
		REFERENCES user_account (user_id),
	grid_definition_id BIGINT         NOT NULL CONSTRAINT fk_saved_filter_grid
		REFERENCES grid_definition (grid_definition_id),
	config_realm       VARCHAR(16)    NOT NULL
		CONSTRAINT df_saved_filter_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_saved_filter_realm CHECK (config_realm = 'INSTALLATION'),
	name               NVARCHAR(200)  NOT NULL,
	-- An expression tree is document-shaped; JSON is the deliberate choice
	-- (rule 11) — and the version column is what makes it evolvable without
	-- 1.x's blind string-rewrites.
	filter_json           NVARCHAR(4000) NOT NULL,
	filter_schema_version INT            NOT NULL CONSTRAINT df_saved_filter_schema_version DEFAULT 1,
	is_default            BIT            NOT NULL CONSTRAINT df_saved_filter_default DEFAULT 0,
	retired_at            DATETIME2(3)   NULL,
	created_at            DATETIME2(3)   NOT NULL CONSTRAINT df_saved_filter_created_at DEFAULT SYSUTCDATETIME()
);

-- (user, grid, filter_name) unique — 1.x enforced it racily.
CREATE UNIQUE INDEX ux_saved_filter ON saved_filter (user_id, grid_definition_id, name)
	WHERE retired_at IS NULL;

-- At most one default per (user, grid) — 1.x enforced nothing.
CREATE UNIQUE INDEX ux_saved_filter_default ON saved_filter (user_id, grid_definition_id)
	WHERE is_default = 1 AND retired_at IS NULL;

-- --------------------------------------------------------------------------
-- site branding & localization (per-site, created EMPTY — rule 10)
-- --------------------------------------------------------------------------
CREATE TABLE site_color (
	site_color_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_site_color PRIMARY KEY,
	site_external_id VARCHAR(64)   NOT NULL CONSTRAINT fk_site_color_site REFERENCES site (external_id),
	code             VARCHAR(64)   NOT NULL,
	-- Normalized to #RRGGBBAA (1.x mixed 6- and 8-digit hex). The service
	-- appends FF alpha to 6-digit input and upper-cases; the CHECK holds the
	-- invariant.
	hex_value        CHAR(9)       NOT NULL CONSTRAINT ck_site_color_hex CHECK (
		hex_value COLLATE Latin1_General_100_BIN2 LIKE
			'#[0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F]'),
	retired_at       DATETIME2(3)  NULL,
	created_at       DATETIME2(3)  NOT NULL CONSTRAINT df_site_color_created_at DEFAULT SYSUTCDATETIME()
);

CREATE UNIQUE INDEX ux_site_color ON site_color (site_external_id, code) WHERE retired_at IS NULL;

CREATE TABLE site_language (
	site_language_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_site_language PRIMARY KEY,
	site_external_id VARCHAR(64)   NOT NULL CONSTRAINT fk_site_language_site REFERENCES site (external_id),
	code             VARCHAR(32)   NOT NULL,
	name             NVARCHAR(100) NOT NULL,
	resource_path    VARCHAR(512)  NULL,
	retired_at       DATETIME2(3)  NULL,
	created_at       DATETIME2(3)  NOT NULL CONSTRAINT df_site_language_created_at DEFAULT SYSUTCDATETIME()
);

CREATE UNIQUE INDEX ux_site_language ON site_language (site_external_id, code) WHERE retired_at IS NULL;

-- --------------------------------------------------------------------------
-- audit_event — core's first traffic-growing table. Growth and the retention
-- class are declared in Java (AuditTables) where the build check reads them;
-- the scope-leading index is here, where ScopeIndexRule reads it.
-- --------------------------------------------------------------------------
CREATE TABLE audit_event (
	audit_event_id     BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_audit_event PRIMARY KEY,
	-- Deliberately NO FK: the value is the configured installation site (the
	-- scope dimension), and an audit write must not depend on config rows.
	site_external_id   VARCHAR(64)    NOT NULL,
	occurred_at        DATETIME2(3)   NOT NULL CONSTRAINT df_audit_event_occurred_at DEFAULT SYSUTCDATETIME(),
	-- A user's external id, or a system identity (system:orca-core/task).
	-- §B6: no path runs with no identity, so this is never blank.
	actor              NVARCHAR(200)  NOT NULL,
	entity_type        VARCHAR(64)    NOT NULL,
	entity_external_id VARCHAR(200)   NULL,
	action             VARCHAR(32)    NOT NULL CONSTRAINT ck_audit_event_action CHECK (
		action COLLATE Latin1_General_100_BIN2 IN ('CREATED', 'UPDATED', 'RETIRED', 'REINSTATED', 'REPLACED')),
	detail             NVARCHAR(1000) NULL,
	CONSTRAINT ck_audit_event_actor CHECK (LEN(actor) > 0)
);

CREATE INDEX ix_audit_event_scope ON audit_event (site_external_id, occurred_at);
