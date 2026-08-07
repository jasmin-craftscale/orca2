-- orca-core · the minimal world model the vertical slice reads.
--
-- §C1 owns the customer → site → area → lane hierarchy and the device registry.
-- This migration builds only the part one truck through one lane needs. The other
-- 70-odd tables §C1 names arrive with the behaviour behind them; a table with no
-- reader is a schema decision taken before anybody knows what it has to answer.
--
-- CONVENTIONS THAT HOLD IN EVERY TABLE HERE (§B8, §D3):
--
--   * Internal key AND external identifier. Joins use the key; anything crossing
--     a boundary uses the external id, which is never reused.
--   * Retired, not removed. `retired_at` rather than DELETE — and the published
--     views below show only rows where it is NULL, so a consumer never has to
--     remember the rule.
--   * Unqualified names. They land in `core` because that is `orca_core`'s
--     DEFAULT_SCHEMA, which is the mechanism ADR-004 rests on.
--
-- Growth is declared in Java beside each table (@PersistentTable), because that
-- is where the build check can read it. All four are BOUNDED: a row appears when
-- somebody configures something, not when a truck arrives.

-- --------------------------------------------------------------------------
-- site
-- --------------------------------------------------------------------------
CREATE TABLE site (
	site_id      BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_site PRIMARY KEY,
	external_id  VARCHAR(64)   NOT NULL CONSTRAINT uq_site_external_id UNIQUE,
	code         VARCHAR(32)   NOT NULL,
	name         NVARCHAR(200) NOT NULL,
	-- The installation's own site, and the one the licence binds to (§C1).
	is_primary   BIT           NOT NULL CONSTRAINT df_site_is_primary DEFAULT 0,
	retired_at   DATETIME2(3)  NULL,
	created_at   DATETIME2(3)  NOT NULL CONSTRAINT df_site_created_at DEFAULT SYSUTCDATETIME()
);

-- §C1: "exactly one site is primary". A constraint can hold the AT MOST ONE half
-- of that; the AT LEAST ONE half is the installer's, because an empty database
-- has no primary site and refusing to create the first one would be a deadlock.
-- Filtered rather than a trigger: the database refuses the second one outright.
CREATE UNIQUE INDEX ux_site_one_primary ON site (is_primary)
	WHERE is_primary = 1 AND retired_at IS NULL;

-- --------------------------------------------------------------------------
-- area
-- --------------------------------------------------------------------------
CREATE TABLE area (
	area_id      BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_area PRIMARY KEY,
	external_id  VARCHAR(64)   NOT NULL CONSTRAINT uq_area_external_id UNIQUE,
	site_id      BIGINT        NOT NULL CONSTRAINT fk_area_site REFERENCES site (site_id),
	code         VARCHAR(32)   NOT NULL,
	name         NVARCHAR(200) NOT NULL,
	retired_at   DATETIME2(3)  NULL,
	created_at   DATETIME2(3)  NOT NULL CONSTRAINT df_area_created_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_area_site ON area (site_id);

-- --------------------------------------------------------------------------
-- lane
-- --------------------------------------------------------------------------
CREATE TABLE lane (
	lane_id            BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_lane PRIMARY KEY,
	external_id        VARCHAR(64)   NOT NULL CONSTRAINT uq_lane_external_id UNIQUE,
	area_id            BIGINT        NOT NULL CONSTRAINT fk_lane_area REFERENCES area (area_id),
	code               VARCHAR(32)   NOT NULL,
	name               NVARCHAR(200) NOT NULL,
	-- Where the .NET device host for this lane answers. §C3's commands go here,
	-- and it is per lane rather than per site because the frozen contract is.
	device_host_url    VARCHAR(512)  NULL,
	-- An operator takes a lane out of service; the gate loop must see it.
	is_out_of_service  BIT           NOT NULL CONSTRAINT df_lane_oos DEFAULT 0,
	lane_priority      INT           NOT NULL CONSTRAINT df_lane_priority DEFAULT 0,
	retired_at         DATETIME2(3)  NULL,
	created_at         DATETIME2(3)  NOT NULL CONSTRAINT df_lane_created_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_lane_area ON lane (area_id);

-- --------------------------------------------------------------------------
-- device
-- --------------------------------------------------------------------------
CREATE TABLE device (
	device_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_device PRIMARY KEY,
	external_id  VARCHAR(64)   NOT NULL CONSTRAINT uq_device_external_id UNIQUE,
	lane_id      BIGINT        NOT NULL CONSTRAINT fk_device_lane REFERENCES lane (lane_id),
	-- ⚠️ PROVISIONAL VOCABULARY, and deliberately a free VARCHAR rather than a
	-- CHECK over a closed list. Nothing in the architecture enumerates device
	-- types: §C3 enumerates command ACTIONS (RAISE_GATE · LOWER_GATE · PRINT ·
	-- SET_IO · PTZ_PRESET) and §C1 says only "identity, addressing, IO port layout".
	-- Writing a closed list here would settle a vocabulary the corpus has not, so
	-- the values this phase uses — LPR_CAMERA, BARRIER — are provisional and named
	-- as such. Reported in the phase report rather than decided here.
	device_type  VARCHAR(32)   NOT NULL,
	name         NVARCHAR(200) NOT NULL,
	-- The addressing the device host needs. Frozen-contract detail (§D2) arrives
	-- with the device-host work; this is what the slice reads.
	address      VARCHAR(512)  NULL,
	retired_at   DATETIME2(3)  NULL,
	created_at   DATETIME2(3)  NOT NULL CONSTRAINT df_device_created_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_device_lane ON device (lane_id);
