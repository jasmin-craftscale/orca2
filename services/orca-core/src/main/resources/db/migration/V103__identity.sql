-- Who may use this installation, and what each of them is allowed to do: user
-- accounts, roles, which sites a role covers, the catalog of everything that can
-- be permitted, and which of those permissions each role holds.
--
-- WHAT THIS IS FOR
-- A person signs in to the console; the console has to decide which screens they
-- see, which buttons work, and which sites' data they may look at. Every one of
-- those answers comes from these tables. Administrators write them; orca-core
-- reads them on the authorization path, and — through published views — so does
-- the service that runs the gate, when it needs to know which operator is acting.
--
-- The permission model has one shape worth learning up front. Permissions are not
-- a flat list of strings: they form a four-level tree — application, module,
-- sub-module, and finally the individual action item ("Add Role", "Export
-- Excel"). Those four tables are a CATALOG of everything the product can gate on.
-- A role holds a set of grants, and a grant points at one leaf of that tree. A
-- user holds exactly one role.
--
-- WHERE THE SHAPE CAME FROM
-- These tables are translated from the Go system in production today — the schema
-- there is defined by object-relational mappings rather than by any CREATE TABLE,
-- so it is evidence of what the fielded product needs, not a specification. The
-- translation deliberately does not copy: several long-standing defects are fixed
-- here, and each fix is called out at the table it applies to. Where a
-- translation choice was made, the reasoning is written down beside it rather
-- than left for someone to reverse-engineer.
--
-- WHAT THE OLD SYSTEM HAD THAT IS DELIBERATELY ABSENT
--   * A customer id on every table. One installation serves exactly one customer
--     — that was ruled on 6–7 August 2026 — so the column would be a constant on
--     every row. Role names are unique across the installation instead.
--   * Everything to do with passwords: stored credentials, session records, login
--     attempt counters, a login-type column, an LDAP flag. The identity provider
--     owns authentication. A user row here is a profile plus the mapping from
--     the token's subject to that profile.
--   * An encrypted email column with a hash alongside it for searching. Whether
--     personal data is encrypted at rest is a security decision for the product
--     owner; until it is taken, the column is an ordinary one.
--   * The "override user" columns — a licence break-glass account. They belong
--     with the licensing work, which has not started.
--   * A user-to-site mapping table. It exists in the old system and nothing reads
--     it. Site access is granted through the role, in `role_site` below.
--   * A row-level data-scope column grafted onto the grants table. It points at
--     reference data and mixes two different ideas of permission; how 2.0 wants
--     per-data-scope grants is an open question, so it is not copied.
--   * Denormalized module and sub-module foreign keys on each grant. They are
--     derivable from the leaf, so only the leaf is stored.
--
-- THE TWO SCOPE COLUMNS, AND WHY EVERY TABLE CARRIES ONE
-- Every read of these tables goes through a single piece of shared code that
-- turns "what is this caller allowed to see" into the query's leading condition.
-- That code has no unscoped read at all, deliberately: there is no way to ask for
-- everything. So every table has to declare which dimension it is scoped by.
--
--   * `site_external_id` — for rows that belong to one site (here, `role_site`).
--     It is a foreign key to the site's external id, so a denormalized scope
--     value cannot drift or name a site that does not exist. Its index LEADS with
--     it, because every scoped read leads with that condition — a table without
--     such an index can only be scanned, and a scan taken under a lock locks
--     every row at the site. The build check `ScopeIndexRule` reads this file
--     and fails when a table declaring the column has no index leading with it.
--   * `config_realm` — a constant 'INSTALLATION', for rows that belong to the
--     installation as a whole: users, roles, the catalog, the grants. Reading
--     installation-wide data then becomes a declared act with a named dimension
--     rather than a hole in the mechanism, and a caller granted nothing still
--     sees nothing. A row also cannot quietly claim a site it does not have.
--
-- HOW BIG THESE GET
-- Growth is declared in Java beside each table, in `IdentityTables`, where the
-- build check `RetentionClassRule` can read it. All bounded: rows appear when an
-- administrator configures something, never when a truck arrives.

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

-- Two active roles may not share a name. The old system had no unique constraint
-- on the role name at all, so duplicates were possible there.
--
-- Filtered to unretired rows so that a retired role's name becomes free again.
-- That is the deliberate difference between the two identifiers: an external id
-- is never reused, a display name may be.
CREATE UNIQUE INDEX ux_role_name ON role (name) WHERE retired_at IS NULL;

-- --------------------------------------------------------------------------
-- user_account — one row per person who can sign in.
-- Named `user_account` rather than `user` because USER is a reserved word in SQL.
-- --------------------------------------------------------------------------
CREATE TABLE user_account (
	user_id             BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_user_account PRIMARY KEY,
	external_id         VARCHAR(64)   NOT NULL CONSTRAINT uq_user_account_external_id UNIQUE,
	config_realm        VARCHAR(16)   NOT NULL
		CONSTRAINT df_user_account_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_user_account_realm CHECK (config_realm = 'INSTALLATION'),
	-- The identity provider's own id for this person — the "subject" claim in the
	-- sign-in token. Authentication belongs to the identity provider (Keycloak);
	-- this column is the single link that turns a validated token into a user of
	-- this platform. Nullable, because an administrator can create the account
	-- before the person has ever signed in, and it is their first sign-in that
	-- links the two.
	keycloak_subject    VARCHAR(64)   NULL,
	first_name          NVARCHAR(100) NULL,
	middle_name         NVARCHAR(100) NULL,
	last_name           NVARCHAR(100) NULL,
	display_name        NVARCHAR(200) NOT NULL,
	-- Stored in the clear. The old system encrypts this and keeps a hash beside it
	-- so that search still works. Whether personal data is encrypted at rest here
	-- is a security decision for the product owner, and it has not been taken, so
	-- the column stays ordinary rather than half-implementing a scheme.
	-- 320 characters is the maximum length an email address can have by
	-- specification; the old system's 500 was sized for the ciphertext.
	email               NVARCHAR(320) NOT NULL,
	profile_image_url   VARCHAR(512)  NULL,
	-- A language code, not a display name. The old system stored the word
	-- "english" in a 200-character column and matched on it; a code is the thing
	-- software should compare, and the human-readable name belongs in the
	-- localization tables that arrive later.
	language_code       VARCHAR(32)   NULL,
	privacy_accepted_at DATETIME2(3)  NULL,
	terms_accepted_at   DATETIME2(3)  NULL,
	-- Exactly one role per user. That is a rule the old system holds and the new
	-- design keeps, and it is held here structurally: a single column that cannot
	-- be null. There is no user-to-roles table to drift out of step, and no
	-- question about what happens when a user has two roles that disagree.
	role_id             BIGINT        NOT NULL CONSTRAINT fk_user_account_role REFERENCES role (role_id),
	retired_at          DATETIME2(3)  NULL,
	created_at          DATETIME2(3)  NOT NULL CONSTRAINT df_user_account_created_at DEFAULT SYSUTCDATETIME()
);

-- One identity-provider subject resolves to at most one active user. Without
-- this, a token could match two rows and the answer to "who is this?" would
-- depend on which one the query happened to return first.
--
-- Filtered twice over: rows with no subject yet (an account created before its
-- first sign-in) are excluded, so any number of them may coexist, and retired
-- accounts are excluded, so a person who left and came back is expressible.
CREATE UNIQUE INDEX ux_user_account_keycloak_subject ON user_account (keycloak_subject)
	WHERE keycloak_subject IS NOT NULL AND retired_at IS NULL;

CREATE INDEX ix_user_account_role ON user_account (role_id);

-- --------------------------------------------------------------------------
-- role_site — which sites a role covers. This is the table that decides whether
-- an operator at one site can see another site's data, so it is on the path of
-- every authorization decision.
--
-- Its equivalent in the old system had no foreign keys, no unique constraint on
-- the pair and no index at all, despite being read on exactly that path. All
-- three are fixed below.
-- --------------------------------------------------------------------------
CREATE TABLE role_site (
	role_site_id      BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_role_site PRIMARY KEY,
	role_id           BIGINT       NOT NULL CONSTRAINT fk_role_site_role REFERENCES role (role_id),
	-- The scope column and the reference to the site are the same column. It is a
	-- foreign key to the site's external id, which is why a copied scope value
	-- here cannot name a site that does not exist or drift after the site changes.
	site_external_id  VARCHAR(64)  NOT NULL CONSTRAINT fk_role_site_site REFERENCES site (external_id),
	retired_at        DATETIME2(3) NULL,
	created_at        DATETIME2(3) NOT NULL CONSTRAINT df_role_site_created_at DEFAULT SYSUTCDATETIME()
);

-- One index doing two jobs: it refuses a duplicate (role, site) pair, and because
-- it leads with the site column it is also the index every scoped read uses.
-- Filtered to unretired rows, so access revoked today can be granted again
-- tomorrow rather than being blocked by the corpse of the old row.
CREATE UNIQUE INDEX ux_role_site ON role_site (site_external_id, role_id) WHERE retired_at IS NULL;

CREATE INDEX ix_role_site_role ON role_site (role_id);

-- --------------------------------------------------------------------------
-- The permission catalog — everything the product can gate on, as a four-level
-- tree: application → module → sub-module → action item. "GATE · Admin · Role
-- Management · Add Role" is one path through it, and the leaf is what a role is
-- granted.
--
-- This is reference data owned by the product, not by the customer: an
-- administrator picks from it, and never adds to it. The rows themselves are
-- inserted by V104__entitlement_catalog_seed.sql, with identifiers that are
-- computed rather than random, so that two clean installations end up with
-- byte-identical catalogs.
--
-- The four tables name their columns consistently. The old system did not — the
-- key and the identifier at each level used different prefixes from each other.
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
	-- The string the old system's licence check matches on. It is carried through
	-- verbatim because the licensing work will need it, and it must keep matching
	-- what a licence already says.
	--
	-- ⚠️ It is NOT unique across the tree. "ExportExcel", "DeleteRecord" and
	-- "View" each appear against many different action items, so filtering by
	-- licence route is coarser than the catalog it sits in: switching off a route
	-- switches off every action item that shares the string. That is a property of
	-- the fielded system, recorded here so nobody assumes a one-to-one mapping.
	licence_route  VARCHAR(64)   NOT NULL,
	retired_at     DATETIME2(3)  NULL,
	created_at     DATETIME2(3)  NOT NULL CONSTRAINT df_entitlement_action_item_created_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_entitlement_action_item_sub_module ON entitlement_action_item (sub_module_id);

-- --------------------------------------------------------------------------
-- role_entitlement — the grants themselves. One row means "this role may do this
-- one thing".
--
-- It references only the leaf of the catalog tree. The old system also stored the
-- module and sub-module on every grant row; those are reachable by following the
-- leaf upwards, and a stored copy of a derivable value is a copy that can end up
-- disagreeing. It also carried a column pointing at a row of reference data,
-- which turned the grant table into a half-built row-level access rule; how
-- per-data-scope grants should work is an open question, so it is not copied.
--
-- The old system had no unique constraint on the (role, action item) pair, so the
-- same grant could exist several times over. Here the database refuses it.
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

-- The unique pair, with the role first. The column order is deliberate and does
-- two jobs at once: it refuses duplicate grants, and it serves the only read
-- anybody makes of this table — "what may this role do?" — which arrives with the
-- role in hand. The old system needed a separate index for that read; here it is
-- the same one.
CREATE UNIQUE INDEX ux_role_entitlement ON role_entitlement (role_id, action_item_id)
	WHERE retired_at IS NULL;

CREATE INDEX ix_role_entitlement_action_item ON role_entitlement (action_item_id);
