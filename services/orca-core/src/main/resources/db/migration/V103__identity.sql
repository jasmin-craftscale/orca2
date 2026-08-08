-- orca-core · WP1 — identity: users, roles, role↔site scoping, the entitlement
-- catalog and role↔entitlement grants.
--
-- Translated from ORCA 1.x per docs/core-config-schema-from-1x.md §1, whose §0
-- translation rules OVERRIDE the 1.x shapes — and V101's conventions override
-- both where they conflict (internal key + external id, retired_at rather than
-- is_active/is_deleted, created_at only, unqualified names).
--
-- WHAT DOES NOT PORT, and why (each recorded in docs/phase-2-report.md):
--   * customer_id everywhere — one installation never carries more than one
--     customer (register NEW-1a/1b, ruled 6–7 Aug 2026). The customer dimension
--     is degenerate on an appliance; role names are unique per installation.
--   * The 1.x credential cluster (credential_password, session_store, login
--     attempts, user_login_type, is_ldap_user) — Keycloak owns credentials
--     (sheet rule 9, §B6). The user row is profile + claim mapping.
--   * email_hash / email-at-rest encryption — security-shaped; PROPOSED in the
--     report, decided by the product owner, not implemented here.
--   * is_override_user / override_user_details — licensing cluster, deferred.
--   * user_site_mappings — DEAD in 1.x (zero references). Site scoping is
--     role-based, via role_site below.
--   * role_entitlement_mappings.event_data_id — row-level data scope grafted
--     onto the grant table; surfaced in the report, not copied.
--   * The denormalized module/sub-module FKs on grants — leaf FK only.
--
-- SCOPE COLUMNS — the two dimensions core's seam reads use:
--   * site_external_id  — for site-dimensional rows (role_site here). FK to
--     site(external_id) so the denormalized scope column cannot drift, and a
--     scope-LEADING index because every seam read leads with the predicate
--     (ScopeIndexRule reads this file).
--   * config_realm      — constant 'INSTALLATION', for rows that belong to the
--     installation as a whole (users, roles, the catalog, grants). The seam has
--     no unscoped read, deliberately; a constant realm column makes the
--     installation-wide read a declared act rather than a bypass, and DENY
--     still returns nothing. A row cannot claim a site it does not have.
--
-- Growth is declared beside each table in Java (IdentityTables). All BOUNDED:
-- rows appear when an administrator configures something, never per truck.

-- --------------------------------------------------------------------------
-- role
-- --------------------------------------------------------------------------
CREATE TABLE role (
	role_id       BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_role PRIMARY KEY,
	external_id   VARCHAR(64)    NOT NULL CONSTRAINT uq_role_external_id UNIQUE,
	config_realm  VARCHAR(16)    NOT NULL
		CONSTRAINT df_role_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_role_realm CHECK (config_realm = 'INSTALLATION'),
	name          NVARCHAR(255)  NOT NULL,
	description   NVARCHAR(3000) NULL,
	retired_at    DATETIME2(3)   NULL,
	created_at    DATETIME2(3)   NOT NULL CONSTRAINT df_role_created_at DEFAULT SYSUTCDATETIME()
);

-- 1.x had NO unique on role_name (sheet §1). Filtered so a retired role's name
-- can be reused — external ids are never reused, names may be.
CREATE UNIQUE INDEX ux_role_name ON role (name) WHERE retired_at IS NULL;

-- --------------------------------------------------------------------------
-- user_account  (USER is a reserved word; user_details' 2.0 shape)
-- --------------------------------------------------------------------------
CREATE TABLE user_account (
	user_id             BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_user_account PRIMARY KEY,
	external_id         VARCHAR(64)   NOT NULL CONSTRAINT uq_user_account_external_id UNIQUE,
	config_realm        VARCHAR(16)   NOT NULL
		CONSTRAINT df_user_account_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_user_account_realm CHECK (config_realm = 'INSTALLATION'),
	-- The claim mapping: Keycloak's subject for this user (§B6 — Keycloak owns
	-- authentication; this is how a token resolves to a platform user). Nullable
	-- because a user can be provisioned before their first login links them.
	keycloak_subject    VARCHAR(64)   NULL,
	first_name          NVARCHAR(100) NULL,
	middle_name         NVARCHAR(100) NULL,
	last_name           NVARCHAR(100) NULL,
	display_name        NVARCHAR(200) NOT NULL,
	-- Plain, not encrypted: 1.x encrypts email with an email_hash blind index.
	-- Whether 2.0 encrypts PII at rest is security-shaped — PROPOSED in the
	-- report (§5 of the sheet), and until ruled the column is ordinary. 320 is
	-- the addr-spec ceiling; 1.x's 500 was sized for ciphertext.
	email               NVARCHAR(320) NOT NULL,
	profile_image_url   VARCHAR(512)  NULL,
	-- 1.x language_name varchar(200) held a display name ("english"). A code is
	-- the 2.0 shape; display names belong to the localization tables (WP4).
	language_code       VARCHAR(32)   NULL,
	privacy_accepted_at DATETIME2(3)  NULL,
	terms_accepted_at   DATETIME2(3)  NULL,
	-- Exactly one role per user — a 1.x invariant §C1 keeps ("exactly one role
	-- per user"), held structurally by this being a single NOT NULL column.
	role_id             BIGINT        NOT NULL CONSTRAINT fk_user_account_role REFERENCES role (role_id),
	retired_at          DATETIME2(3)  NULL,
	created_at          DATETIME2(3)  NOT NULL CONSTRAINT df_user_account_created_at DEFAULT SYSUTCDATETIME()
);

-- One platform user per identity-provider subject, among active users.
CREATE UNIQUE INDEX ux_user_account_keycloak_subject ON user_account (keycloak_subject)
	WHERE keycloak_subject IS NOT NULL AND retired_at IS NULL;

CREATE INDEX ix_user_account_role ON user_account (role_id);

-- --------------------------------------------------------------------------
-- role_site — the real tenant-scoping table (1.x role_site_mappings, which had
-- no FKs, no unique pair and no index despite sitting on the auth hot path)
-- --------------------------------------------------------------------------
CREATE TABLE role_site (
	role_site_id      BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_role_site PRIMARY KEY,
	role_id           BIGINT       NOT NULL CONSTRAINT fk_role_site_role REFERENCES role (role_id),
	-- The scope column IS the reference: FK to site's unique external id, so the
	-- denormalized scope value cannot name a site that does not exist.
	site_external_id  VARCHAR(64)  NOT NULL CONSTRAINT fk_role_site_site REFERENCES site (external_id),
	retired_at        DATETIME2(3) NULL,
	created_at        DATETIME2(3) NOT NULL CONSTRAINT df_role_site_created_at DEFAULT SYSUTCDATETIME()
);

-- Unique pair AND the scope-leading index, in one statement. Filtered so a
-- revoked mapping can be granted again.
CREATE UNIQUE INDEX ux_role_site ON role_site (site_external_id, role_id) WHERE retired_at IS NULL;

CREATE INDEX ix_role_site_role ON role_site (role_id);

-- --------------------------------------------------------------------------
-- The entitlement catalog — four levels, normalized prefixes (1.x had
-- application_module_id vs module_uuid etc.). Product-owned reference data:
-- seeded by V104 with pinned identity per sheet rule 7.
-- --------------------------------------------------------------------------
CREATE TABLE entitlement_application (
	application_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_entitlement_application PRIMARY KEY,
	external_id    VARCHAR(64)   NOT NULL CONSTRAINT uq_entitlement_application_external_id UNIQUE,
	config_realm   VARCHAR(16)   NOT NULL
		CONSTRAINT df_entitlement_application_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_entitlement_application_realm CHECK (config_realm = 'INSTALLATION'),
	code           VARCHAR(128)  NOT NULL CONSTRAINT uq_entitlement_application_code UNIQUE,
	name           NVARCHAR(200) NOT NULL,
	retired_at     DATETIME2(3)  NULL,
	created_at     DATETIME2(3)  NOT NULL CONSTRAINT df_entitlement_application_created_at DEFAULT SYSUTCDATETIME()
);

CREATE TABLE entitlement_module (
	module_id      BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_entitlement_module PRIMARY KEY,
	application_id BIGINT        NOT NULL CONSTRAINT fk_entitlement_module_application
		REFERENCES entitlement_application (application_id),
	external_id    VARCHAR(64)   NOT NULL CONSTRAINT uq_entitlement_module_external_id UNIQUE,
	config_realm   VARCHAR(16)   NOT NULL
		CONSTRAINT df_entitlement_module_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_entitlement_module_realm CHECK (config_realm = 'INSTALLATION'),
	code           VARCHAR(128)  NOT NULL CONSTRAINT uq_entitlement_module_code UNIQUE,
	name           NVARCHAR(200) NOT NULL,
	retired_at     DATETIME2(3)  NULL,
	created_at     DATETIME2(3)  NOT NULL CONSTRAINT df_entitlement_module_created_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_entitlement_module_application ON entitlement_module (application_id);

CREATE TABLE entitlement_sub_module (
	sub_module_id  BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_entitlement_sub_module PRIMARY KEY,
	module_id      BIGINT        NOT NULL CONSTRAINT fk_entitlement_sub_module_module
		REFERENCES entitlement_module (module_id),
	external_id    VARCHAR(64)   NOT NULL CONSTRAINT uq_entitlement_sub_module_external_id UNIQUE,
	config_realm   VARCHAR(16)   NOT NULL
		CONSTRAINT df_entitlement_sub_module_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_entitlement_sub_module_realm CHECK (config_realm = 'INSTALLATION'),
	code           VARCHAR(128)  NOT NULL CONSTRAINT uq_entitlement_sub_module_code UNIQUE,
	name           NVARCHAR(200) NOT NULL,
	retired_at     DATETIME2(3)  NULL,
	created_at     DATETIME2(3)  NOT NULL CONSTRAINT df_entitlement_sub_module_created_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_entitlement_sub_module_module ON entitlement_sub_module (module_id);

CREATE TABLE entitlement_action_item (
	action_item_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_entitlement_action_item PRIMARY KEY,
	sub_module_id  BIGINT        NOT NULL CONSTRAINT fk_entitlement_action_item_sub_module
		REFERENCES entitlement_sub_module (sub_module_id),
	external_id    VARCHAR(64)   NOT NULL CONSTRAINT uq_entitlement_action_item_external_id UNIQUE,
	config_realm   VARCHAR(16)   NOT NULL
		CONSTRAINT df_entitlement_action_item_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_entitlement_action_item_realm CHECK (config_realm = 'INSTALLATION'),
	code           VARCHAR(128)  NOT NULL CONSTRAINT uq_entitlement_action_item_code UNIQUE,
	name           NVARCHAR(200) NOT NULL,
	-- The 1.x licence-gate string ("Routes"). NOT unique across the tree —
	-- ExportExcel, DeleteRecord and View recur — so licence filtering by route is
	-- coarser than the catalog (sheet §1). Kept verbatim for the licence work.
	licence_route  VARCHAR(64)   NOT NULL,
	retired_at     DATETIME2(3)  NULL,
	created_at     DATETIME2(3)  NOT NULL CONSTRAINT df_entitlement_action_item_created_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_entitlement_action_item_sub_module ON entitlement_action_item (sub_module_id);

-- --------------------------------------------------------------------------
-- role_entitlement — grants. Leaf FK only: 1.x also carried module and
-- sub-module FKs (derivable — dropped, rule 6) and event_data_id (a row-level
-- data-scope graft — surfaced in the report, not copied). 1.x had no unique on
-- the pair, so duplicate grants were possible; here the database refuses them.
-- --------------------------------------------------------------------------
CREATE TABLE role_entitlement (
	role_entitlement_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_role_entitlement PRIMARY KEY,
	role_id             BIGINT       NOT NULL CONSTRAINT fk_role_entitlement_role REFERENCES role (role_id),
	action_item_id      BIGINT       NOT NULL CONSTRAINT fk_role_entitlement_action_item
		REFERENCES entitlement_action_item (action_item_id),
	config_realm        VARCHAR(16)  NOT NULL
		CONSTRAINT df_role_entitlement_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_role_entitlement_realm CHECK (config_realm = 'INSTALLATION'),
	retired_at          DATETIME2(3) NULL,
	created_at          DATETIME2(3) NOT NULL CONSTRAINT df_role_entitlement_created_at DEFAULT SYSUTCDATETIME()
);

-- The unique pair, role-leading — which is also the 2.0 equivalent of 1.x's
-- hot-path covering index (role_id, is_active) INCLUDE (…): grant resolution
-- reads by role, and this index leads with it.
CREATE UNIQUE INDEX ux_role_entitlement ON role_entitlement (role_id, action_item_id)
	WHERE retired_at IS NULL;

CREATE INDEX ix_role_entitlement_action_item ON role_entitlement (action_item_id);
