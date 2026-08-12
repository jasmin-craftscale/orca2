-- The declared shape of a site's custom entities. These are metadata tables:
-- nothing in this migration creates a customer-data table or executes a
-- declaration as DDL.
--
-- WHY THE PHYSICAL NAME IS MINTED NOW
-- A display name is customer language and may change. A table identifier is a
-- database address and must not. Every declaration therefore reserves one
-- opaque `ce_` plus UUID-hex identifier at creation. REFERENCE and EVENT share
-- that successor prefix; their different retention and query semantics remain
-- explicit metadata instead of being hidden in a mutable table name.
--
-- WHY FIELD IDENTIFIERS ARE NARROWER THAN DISPLAY NAMES
-- Identifiers may become SQL Server column names in a later, controlled step.
-- They are exact lower-case ASCII, start with a letter, and contain only letters,
-- digits and underscores. Display names remain Unicode and administrator-facing.
-- The future row keys are fixed as `row_id` and `external_id`, and the future
-- scope column is fixed as `site_external_id`; declaration fields cannot take
-- any of those names.
--
-- WHAT THIS DELIBERATELY DOES NOT SAY
-- There is no applied/pending status, generated-table registry, drift flag or
-- executor identity here. Those would claim that a DDL executor exists. It does
-- not. The declaration version records additive metadata evolution only.

CREATE TABLE custom_entity (
	custom_entity_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_custom_entity PRIMARY KEY,
	external_id         VARCHAR(64)  NOT NULL CONSTRAINT uq_custom_entity_external_id UNIQUE,
	site_external_id    VARCHAR(64)  NOT NULL CONSTRAINT fk_custom_entity_site REFERENCES site (external_id),
	entity_kind         VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL,
	name                NVARCHAR(100) NOT NULL,
	table_identifier    VARCHAR(35) COLLATE Latin1_General_100_BIN2 NOT NULL
		CONSTRAINT uq_custom_entity_table_identifier UNIQUE,
	declaration_version BIGINT NOT NULL CONSTRAINT df_custom_entity_version DEFAULT (1),
	created_at          DATETIME2(3) NOT NULL CONSTRAINT df_custom_entity_created_at DEFAULT SYSUTCDATETIME(),
	updated_at          DATETIME2(3) NOT NULL CONSTRAINT df_custom_entity_updated_at DEFAULT SYSUTCDATETIME(),
	CONSTRAINT ck_custom_entity_kind CHECK (entity_kind IN ('REFERENCE', 'EVENT')),
	CONSTRAINT ck_custom_entity_name CHECK (LEN(LTRIM(RTRIM(name))) > 0),
	CONSTRAINT ck_custom_entity_table_identifier CHECK (
		LEN(table_identifier) = 35
		AND LEFT(table_identifier, 3) = 'ce_'
		AND SUBSTRING(table_identifier, 4, 32) NOT LIKE '%[^0-9a-f]%'),
	CONSTRAINT ck_custom_entity_version CHECK (declaration_version >= 1),
	-- The composite key lets every field's copied scope be FK-backed. Without it,
	-- a field could claim another site while still pointing at this entity.
	CONSTRAINT uq_custom_entity_scope UNIQUE (custom_entity_id, site_external_id)
);

-- Name uniqueness is per site and semantic kind. Its site-leading order is also
-- the access path for every scoped entity read.
CREATE UNIQUE INDEX ux_custom_entity_site_kind_name
	ON custom_entity (site_external_id, entity_kind, name);

CREATE TABLE custom_entity_field (
	custom_entity_field_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_custom_entity_field PRIMARY KEY,
	external_id            VARCHAR(64) NOT NULL CONSTRAINT uq_custom_entity_field_external_id UNIQUE,
	custom_entity_id       BIGINT NOT NULL,
	site_external_id       VARCHAR(64) NOT NULL,
	identifier             VARCHAR(63) COLLATE Latin1_General_100_BIN2 NOT NULL,
	display_name           NVARCHAR(100) NOT NULL,
	field_type             VARCHAR(16) COLLATE Latin1_General_100_BIN2 NOT NULL,
	max_length             INT NULL,
	number_precision       INT NULL,
	number_scale           INT NULL,
	is_nullable            BIT NOT NULL,
	is_business_key        BIT NOT NULL,
	ordinal                INT NOT NULL,
	created_at             DATETIME2(3) NOT NULL CONSTRAINT df_custom_entity_field_created_at DEFAULT SYSUTCDATETIME(),
	CONSTRAINT fk_custom_entity_field_entity_scope FOREIGN KEY (custom_entity_id, site_external_id)
		REFERENCES custom_entity (custom_entity_id, site_external_id),
	CONSTRAINT ck_custom_entity_field_identifier CHECK (
		LEN(identifier) BETWEEN 1 AND 63
		AND LEFT(identifier, 1) LIKE '[a-z]'
		AND identifier NOT LIKE '%[^a-z0-9_]%'
		AND identifier NOT IN ('row_id', 'external_id', 'site_external_id')),
	CONSTRAINT ck_custom_entity_field_display_name CHECK (LEN(LTRIM(RTRIM(display_name))) > 0),
	CONSTRAINT ck_custom_entity_field_type CHECK (field_type IN ('TEXT', 'NUMBER', 'BOOLEAN', 'DATE')),
	CONSTRAINT ck_custom_entity_field_shape CHECK (
		(field_type = 'TEXT'
			AND max_length BETWEEN 1 AND 4000
			AND number_precision IS NULL
			AND number_scale IS NULL)
		OR (field_type = 'NUMBER'
			AND max_length IS NULL
			AND number_precision BETWEEN 1 AND 38
			AND number_scale BETWEEN 0 AND number_precision)
		OR (field_type IN ('BOOLEAN', 'DATE')
			AND max_length IS NULL
			AND number_precision IS NULL
			AND number_scale IS NULL)),
	CONSTRAINT ck_custom_entity_field_business_key_required CHECK (
		is_business_key = 0 OR is_nullable = 0),
	CONSTRAINT ck_custom_entity_field_ordinal CHECK (ordinal > 0),
	CONSTRAINT uq_custom_entity_field_identifier UNIQUE (custom_entity_id, identifier),
	CONSTRAINT uq_custom_entity_field_display_name UNIQUE (custom_entity_id, display_name),
	CONSTRAINT uq_custom_entity_field_ordinal UNIQUE (custom_entity_id, ordinal)
);

-- At most one business key is a database fact. Declaration creation also
-- requires one, which is a cross-table completeness rule kept atomic by the
-- service transaction: SQL Server has no deferred constraint with which to
-- express "the parent must have a child" without making insertion impossible.
CREATE UNIQUE INDEX ux_custom_entity_field_business_key
	ON custom_entity_field (custom_entity_id)
	WHERE is_business_key = 1;

-- Fields are always read by site, then entity, in their declared order.
CREATE INDEX ix_custom_entity_field_scope
	ON custom_entity_field (site_external_id, custom_entity_id, ordinal);
GO

-- Runtime sees only the flattened declaration contract, one row per field. It
-- cannot read or mutate the owning tables. Literal row-key and scope-column
-- names make the future contract explicit without pretending those columns exist.
IF DATABASE_PRINCIPAL_ID(N'orca_runtime') IS NULL
	THROW 50111, 'orca_runtime does not exist in this database. Run deploy/bootstrap/run.sh before starting orca-core: a published view that no consumer can read is not published.', 1;
GO

CREATE VIEW topology_custom_entity AS
SELECT
	ce.site_external_id,
	ce.custom_entity_id,
	ce.external_id AS custom_entity_external_id,
	ce.entity_kind,
	ce.name AS custom_entity_name,
	ce.table_identifier,
	CAST('row_id' AS VARCHAR(63)) AS row_id_column,
	CAST('external_id' AS VARCHAR(63)) AS row_external_id_column,
	CAST('site_external_id' AS VARCHAR(63)) AS row_site_external_id_column,
	ce.declaration_version,
	cef.custom_entity_field_id,
	cef.external_id AS field_external_id,
	cef.identifier AS field_identifier,
	cef.display_name AS field_display_name,
	cef.field_type,
	cef.max_length,
	cef.number_precision,
	cef.number_scale,
	cef.is_nullable,
	cef.is_business_key,
	cef.ordinal AS field_ordinal,
	cef.created_at AS field_created_at,
	ce.created_at,
	ce.updated_at
FROM custom_entity ce
	JOIN custom_entity_field cef ON cef.custom_entity_id = ce.custom_entity_id
		AND cef.site_external_id = ce.site_external_id
	JOIN site s ON s.external_id = ce.site_external_id
WHERE s.retired_at IS NULL;
GO

GRANT SELECT ON topology_custom_entity TO [orca_runtime];
GO
