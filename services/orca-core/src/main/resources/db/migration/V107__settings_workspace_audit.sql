-- Four unrelated things that all belong to running the console: the settings an
-- installation can be tuned with, each operator's own view of the data grids,
-- per-site branding, and the record of who changed what.
--
-- 1 · SETTINGS — three tables, not one
-- `setting_definition` is a registry of the keys that exist at all, seeded below.
-- `setting_value` holds the current value of a key, at most one per key.
-- `setting_history` appends a row every time one changes and is never updated.
--
-- The design for this is one sentence: a registry of known keys, validated
-- writes, history appended, secrets rejected. The registry is the important half
-- — a settings table anyone can insert a new key into is a settings table nobody
-- can validate, and that is what the old system had: 403 keys spread across 21
-- services, with no list of which were real.
--
-- ⚠️ NO SECRET EVER GOES IN HERE. The old system stored identity-provider client
-- secrets, mail passwords and push-notification signing keys in its settings
-- table, encrypted. None of those keys exist in this registry, and the service
-- refuses a secret-shaped key with a typed error BEFORE it even consults the
-- registry — so adding one here would not be enough to make it work, which is the
-- point. Secrets belong in configuration and a key store.
--
-- `setting_history` is the only table in this migration that grows with use
-- rather than with configuration. Its growth and how long it is kept are declared
-- in Java beside the entity, where a build check can read them.
--
-- 2 · WORKSPACE — what each operator has arranged for themselves
-- `grid_definition` names the data grids the console shows. Only the grids whose
-- underlying features exist at this point are seeded; the old system has 38 of
-- them, most belonging to features not built yet, and its list could not be
-- extracted row by row. The default column layout for a grid is a JSON document,
-- left null until a console defines one.
--
-- `user_grid_column_preference` and `saved_filter` are per-operator. The first is
-- deliberately RELATIONAL — one row per user, grid and column, holding three
-- scalars. The old system kept these as JSON strings and paid for it with
-- migrations that had to rewrite text inside them to rename a column.
--
-- `saved_filter` keeps its expression as JSON, and that is also deliberate: a
-- filter is an expression tree, document-shaped, read and written whole. What
-- makes it safe is the version number stored beside it — a document whose shape
-- is versioned can be migrated by parsing it, which is exactly what the old
-- system could not do.
--
-- 3 · BRANDING — created empty, on purpose
-- `site_color` and `site_language` are per-site presentation. No migration in
-- this system creates data belonging to a customer; the old one silently inserted
-- eight colours and a language for every site, which is precisely the habit being
-- broken. Colour values are normalized to eight hex digits with an alpha channel
-- and held to it by a CHECK; the old system mixed six- and eight-digit values and
-- every reader had to cope with both.
--
-- 4 · AUDIT — who changed what
-- `audit_event` is the first table in this schema that grows with use rather than
-- with configuration, and the first with a retention policy. Its details are at
-- the table itself, including the one surprising thing about it: its site column
-- deliberately has no foreign key.

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
	-- What the grid is called on screen. The old system named this column
	-- "table_name", which reads like the name of a database table and is not.
	title           NVARCHAR(200) NOT NULL,
	-- The default column layout for this grid, as a JSON document — a description
	-- of a user interface, read whole and written whole, with nothing querying
	-- inside it. Null until a console defines one.
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

-- One preference row per user, grid and column. The old system had no unique
-- constraint here at all, so a user could accumulate several conflicting
-- preferences for the same grid and the winner depended on row order.
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
	-- The filter itself, as JSON. An expression tree is document-shaped, so this
	-- is one of the deliberate exceptions to keeping data in columns.
	--
	-- The version number beside it is what makes that safe. When the filter format
	-- changes, a migration can parse each document, understand it from its
	-- version, and rewrite it properly. The old system had no version and
	-- therefore no choice but to rewrite text inside the strings and hope.
	filter_json           NVARCHAR(4000) NOT NULL,
	filter_schema_version INT            NOT NULL CONSTRAINT df_saved_filter_schema_version DEFAULT 1,
	is_default            BIT            NOT NULL CONSTRAINT df_saved_filter_default DEFAULT 0,
	retired_at            DATETIME2(3)   NULL,
	created_at            DATETIME2(3)   NOT NULL CONSTRAINT df_saved_filter_created_at DEFAULT SYSUTCDATETIME()
);

-- A user cannot have two filters with the same name on one grid. The old system
-- checked this by selecting first and inserting after, which two requests
-- arriving together can both pass.
CREATE UNIQUE INDEX ux_saved_filter ON saved_filter (user_id, grid_definition_id, name)
	WHERE retired_at IS NULL;

-- At most one filter per user per grid may be the default one. This is the same
-- trick used for the primary site: a unique index over the pair, filtered to rows
-- where the flag is actually set, so any number of non-default filters coexist
-- and the second default is refused. The old system enforced nothing, and a user
-- with two defaults got whichever the query returned first.
CREATE UNIQUE INDEX ux_saved_filter_default ON saved_filter (user_id, grid_definition_id)
	WHERE is_default = 1 AND retired_at IS NULL;

-- --------------------------------------------------------------------------
-- Per-site branding and language. Created EMPTY: no migration in this system
-- inserts data belonging to a customer. The demo seed under deploy/demo is a
-- deliberate script somebody runs, not something that happens on startup.
-- --------------------------------------------------------------------------
CREATE TABLE site_color (
	site_color_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_site_color PRIMARY KEY,
	site_external_id VARCHAR(64)   NOT NULL CONSTRAINT fk_site_color_site REFERENCES site (external_id),
	code             VARCHAR(64)   NOT NULL,
	-- A colour in exactly one form: a hash followed by eight upper-case hex
	-- digits — red, green, blue, then alpha. The service normalizes what it is
	-- given, upper-casing it and appending a fully-opaque alpha to a six-digit
	-- value; this CHECK is what guarantees nothing else ever lands in the column.
	--
	-- The `COLLATE` clause is what makes the pattern case-sensitive. Under this
	-- database's default collation, a case-insensitive comparison would accept
	-- lower-case hex and the normalization would be advisory rather than true.
	--
	-- The old system stored a mixture of six- and eight-digit values, so every
	-- reader had to handle both and some of them did it differently.
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
-- audit_event — an append-only record of every configuration change: who did it,
-- to what, when, and what kind of change it was. Nothing updates a row here.
--
-- This is the first table in this schema that grows with use rather than with
-- configuration, which means it is also the first that must be bounded. How fast
-- it grows and how long its rows are kept are declared in Java beside the entity,
-- where a build check can fail when a growing table has no retention declared.
-- The index it is read by is here, where a different build check reads it.
-- --------------------------------------------------------------------------
CREATE TABLE audit_event (
	audit_event_id     BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_audit_event PRIMARY KEY,
	-- ⚠️ Deliberately NOT a foreign key, unlike every other site column in this
	-- schema. The value is the installation's configured site — the scope the
	-- write happened under — and an audit record must never fail to be written
	-- because the configuration it refers to is missing, half-created or being
	-- changed by the very operation being audited. An audit trail that can be
	-- prevented from recording something is not an audit trail.
	site_external_id   VARCHAR(64)    NOT NULL,
	occurred_at        DATETIME2(3)   NOT NULL CONSTRAINT df_audit_event_occurred_at DEFAULT SYSUTCDATETIME(),
	-- Who did it: a user's external id, or a system identity such as
	-- "system:orca-core/task" when a scheduled job or a reconciler made the
	-- change. Every entry point in this platform runs under some identity —
	-- there is no path that runs under none — which is why this can be NOT NULL
	-- and why the CHECK at the bottom of the table can insist it is non-empty.
	actor              NVARCHAR(200)  NOT NULL,
	entity_type        VARCHAR(64)    NOT NULL,
	entity_external_id VARCHAR(200)   NULL,
	action             VARCHAR(32)    NOT NULL CONSTRAINT ck_audit_event_action CHECK (
		action COLLATE Latin1_General_100_BIN2 IN ('CREATED', 'UPDATED', 'RETIRED', 'REINSTATED', 'REPLACED')),
	detail             NVARCHAR(1000) NULL,
	CONSTRAINT ck_audit_event_actor CHECK (LEN(actor) > 0)
);

CREATE INDEX ix_audit_event_scope ON audit_event (site_external_id, occurred_at);
