-- The physical world a gate installation runs in: sites, the areas inside them,
-- the lanes inside those, and the devices bolted to each lane.
--
-- WHAT THIS IS FOR
-- orca-core owns every piece of configuration at an installation, and this is the
-- backbone of it — the hierarchy that answers "where is this truck?". A site is a
-- facility (a container terminal, a distribution centre); an area is a part of it
-- (the inbound gate, the weighbridge); a lane is one physical driveway with a
-- barrier across it; a device is a camera, a barrier arm or a printer attached to
-- that lane.
--
-- Administrators write these rows through the console. The gate software reads
-- them constantly: to know which lane a plate was read at, and which barrier to
-- command. Other services do not read these tables directly — they read the
-- published views that the next migration creates.
--
-- WHY IT IS ONLY FOUR TABLES
-- The design for orca-core names roughly seventy tables. This builds the four
-- that one truck through one lane actually needs, and no more. A table with no
-- reader is a schema decision taken before anybody knows what it has to answer,
-- and it is far more expensive to change a wrong table than to add a missing one.
--
-- CONVENTIONS THAT HOLD IN EVERY TABLE HERE
--
--   * Two identifiers per row: an internal numeric key, and an external string
--     id. Joins inside the database use the key; anything crossing a service
--     boundary or appearing in an interface uses the external id. An external id
--     is never reused, even after the row it named is gone.
--   * Rows are retired, never deleted. Setting `retired_at` is how something
--     stops being current. The published views filter retired rows out, so a
--     consumer never has to remember the rule — and cannot forget it.
--   * Table names are written unqualified. They land in the `core` schema because
--     that is the default schema of the `orca_core` login this migration runs as.
--     That default is the whole mechanism keeping each service inside its own
--     schema; do not add an explicit schema prefix here and make it look
--     optional.
--
-- HOW BIG THESE GET
-- Each table's expected growth is declared in Java next to the entity, because
-- that is where a build check can read it and fail when a declaration is missing.
-- All four are bounded: a row appears when somebody configures something, never
-- when a truck arrives.

-- --------------------------------------------------------------------------
-- site
-- --------------------------------------------------------------------------
CREATE TABLE site (
	site_id      BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_site PRIMARY KEY,
	external_id  VARCHAR(64)   NOT NULL CONSTRAINT uq_site_external_id UNIQUE,
	code         VARCHAR(32)   NOT NULL,
	name         NVARCHAR(200) NOT NULL,
	-- Marks the installation's own site — the facility this server sits in, and
	-- the one the licence is issued against. An installation may know about
	-- several sites; exactly one of them is primary.
	is_primary   BIT           NOT NULL CONSTRAINT df_site_is_primary DEFAULT 0,
	retired_at   DATETIME2(3)  NULL,
	created_at   DATETIME2(3)  NOT NULL CONSTRAINT df_site_created_at DEFAULT SYSUTCDATETIME()
);

-- Exactly one site must be primary. This index holds the "at most one" half of
-- that: it is unique over `is_primary`, but only across rows where the flag is
-- set and the row is not retired, so any number of non-primary sites coexist and
-- a second primary site is refused by the database outright.
--
-- The "at least one" half is not enforced here and cannot be. An empty database
-- has no primary site, so a constraint demanding one would refuse the very first
-- insert and leave no way in. Whoever installs the system is responsible for that
-- half.
--
-- A filtered unique index rather than a trigger, because a trigger would have to
-- decide what to do about a race between two concurrent inserts; a unique index
-- simply makes the second one fail.
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
	-- Where the device host for this lane answers. The device host is a vendor
	-- component, written in .NET, that talks to the actual hardware; every command
	-- that moves a barrier is an HTTP call to this address. It is recorded per
	-- lane rather than per site because that is how the vendor's interface is
	-- deployed, and that interface cannot be changed without re-certifying every
	-- device vendor.
	device_host_url    VARCHAR(512)  NULL,
	-- Set when an operator takes the lane out of service. The gate software must
	-- see it: a lane out of service does not admit trucks.
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
	-- ⚠️ A PROVISIONAL VOCABULARY, deliberately left as a free VARCHAR rather
	-- than constrained to a closed list.
	--
	-- The design settles what commands a device can be sent — raise the gate,
	-- lower it, print, set an IO port, move a camera to a preset — but it never
	-- enumerates device TYPES; it says only that a device has identity, addressing
	-- and an IO port layout. Writing a CHECK over a closed list here would invent
	-- a vocabulary nobody has agreed, and inventing one in a shipped migration is
	-- expensive to undo. So the two values used at this point — LPR_CAMERA and
	-- BARRIER — are provisional, and are named as provisional rather than dressed
	-- up as a decision.
	--
	-- (This was later settled: a subsequent migration replaces this column with a
	-- foreign key into a catalog of device types translated from the system in
	-- production today, and BARRIER becomes that catalog's GATE_ARM.)
	device_type  VARCHAR(32)   NOT NULL,
	name         NVARCHAR(200) NOT NULL,
	-- How the device host addresses this particular device. The full detail of
	-- what the device host expects here is fixed by the vendor's interface and
	-- arrives with the work that talks to it; this column is what the first
	-- end-to-end gate run needs.
	address      VARCHAR(512)  NULL,
	retired_at   DATETIME2(3)  NULL,
	created_at   DATETIME2(3)  NOT NULL CONSTRAINT df_device_created_at DEFAULT SYSUTCDATETIME()
);

CREATE INDEX ix_device_lane ON device (lane_id);
