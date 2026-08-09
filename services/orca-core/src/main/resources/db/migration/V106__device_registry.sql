-- The device registry, finished: what kinds of device exist, what a device's
-- input and output ports are wired to, where a camera's saved viewpoints are, and
-- a general place to hang extra configuration on anything in the world model.
--
-- WHAT THIS IS FOR
-- V101__world_model.sql created a bare `device` table with just enough on it to
-- get one truck through one lane. This turns it into the registry the console
-- actually administers: the manufacturer and model of each unit, its network
-- address and stream settings, which physical port on it is the loop detector and
-- which is the barrier relay, and what "preset 3" means on a pan-tilt-zoom
-- camera. The service that talks to hardware reads several of these columns; the
-- rest is administration.
--
-- THE THREE CATALOGS, AND A RULE WORTH UNDERSTANDING
-- `device_type`, `device_io_port_name` and `io_device_kind` are catalogs: fixed
-- lists the product owns and an administrator picks from. Their rows are seeded
-- below with computed rather than random identifiers — a version-5 UUID derived
-- by hashing the row's kind and code inside a fixed namespace — so regenerating
-- the seed produces byte-identical rows and two clean installations agree.
--
-- ⚠️ THE SEEDS BELOW ARE KNOWINGLY INCOMPLETE, AND THE GAPS ARE LEFT AS GAPS.
-- These lists were translated from the Go system in production today, and the
-- extraction of the day could only name some of the rows: 12 device types of 16,
-- 36 port names of 40, and none at all of the 19 input/output device kinds, whose
-- count and examples were known but whose exact values were not. The missing rows
-- are NOT invented. Seeding a plausible guess into a catalog other tables point
-- at is how a wrong value becomes permanent, and this project would rather ship a
-- visible hole. (V108__device_catalog_completion.sql fills all three from a
-- proper extraction.)
--
-- WHAT HAPPENS TO `device`
-- It gains the full translated column set, plus two structural changes:
--
--   * The free-text `device_type` column V101__world_model.sql called provisional
--     is replaced by a foreign key into the type catalog, and then dropped. The
--     old system carried the same fact three times over — a code, a display name
--     and a message-topic string — which is three chances to disagree.
--   * It gains a site column, backfilled by walking lane → area → site, backed by
--     a foreign key, and indexed leading with it. That is the shape every scoped
--     table in this schema has: reads lead with the site condition, so the index
--     must too.
--
-- WHAT THE OLD SYSTEM HAD THAT IS DELIBERATELY ABSENT
--   * Encrypted username and password columns on a device. How device credentials
--     are stored is a security decision for the product owner and has not been
--     taken; until it is, the columns do not exist rather than existing in a
--     provisional form somebody starts writing to.
--   * A device-host address on the device. The address of the host that drives
--     hardware is already recorded per LANE, which is how the vendor's interface
--     is actually deployed. Two places to record one address is how the two end
--     up disagreeing.
--   * The message-topic strings. They were computed values written to the
--     database, and they described a message broker this system does not have.
--
-- OTHER TRANSLATION FIXES, EACH EXPLAINED AT THE POINT IT APPLIES
--   * The port-type values are a single casing, enforced under a binary
--     collation. The old system had them in two different casings in two
--     different tables.
--   * A port assignment points at the catalog rather than copying its name.
--   * Two flags that the old system left NOT NULL with no default — so an insert
--     that forgot either simply failed — get their electrically-neutral defaults.
--   * A camera preset's pan, tilt and zoom are numbers. They were 255-character
--     strings.
--   * The old system's two seeded rows of extra configuration were a login
--     lockout policy hardcoded against the first site. They do not port: lockout
--     policy belongs to the identity provider.

-- --------------------------------------------------------------------------
-- The three catalogs. They belong to the installation as a whole rather than to
-- any one site, which is what the constant `config_realm` column on each of them
-- declares: every read of these tables goes through shared scoping code that has
-- no unscoped read, so a table with no site dimension has to name the dimension
-- it does have.
-- --------------------------------------------------------------------------
CREATE TABLE device_type (
	device_type_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_device_type PRIMARY KEY,
	external_id    VARCHAR(64)   NOT NULL CONSTRAINT uq_device_type_external_id UNIQUE,
	config_realm   VARCHAR(16)   NOT NULL
		CONSTRAINT df_device_type_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_device_type_realm CHECK (config_realm = 'INSTALLATION'),
	code           VARCHAR(64)   NOT NULL CONSTRAINT uq_device_type_code UNIQUE,
	name           NVARCHAR(200) NOT NULL,
	-- Describes the form the console shows when an administrator configures a
	-- device of this type — which fields, of what kind, in what order.
	--
	-- Held as a JSON document on purpose. The general rule in this schema is that
	-- data goes in columns and rows, because the old system kept far too much in
	-- unsized JSON strings and then needed migrations that rewrote text inside
	-- them. This is one of the deliberate exceptions: a form description is read
	-- whole and written whole, nothing queries inside it, and modelling it as
	-- tables would buy no query anybody runs.
	--
	-- Seeded NULL: the contents could not be extracted from the source system.
	form_schema    NVARCHAR(MAX) NULL,
	retired_at     DATETIME2(3)  NULL,
	created_at     DATETIME2(3)  NOT NULL CONSTRAINT df_device_type_created_at DEFAULT SYSUTCDATETIME()
);

CREATE TABLE device_io_port_name (
	port_name_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_device_io_port_name PRIMARY KEY,
	external_id  VARCHAR(64)   NOT NULL CONSTRAINT uq_device_io_port_name_external_id UNIQUE,
	config_realm VARCHAR(16)   NOT NULL
		CONSTRAINT df_device_io_port_name_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_device_io_port_name_realm CHECK (config_realm = 'INSTALLATION'),
	-- What kind of port this is. The `COLLATE` clause is load-bearing: this
	-- database's default collation is case-insensitive, so a plain list would
	-- accept 'input' and 'Input' as well, and the old system genuinely does carry
	-- this value in two different casings in two different tables. Comparing under
	-- a binary collation makes the single casing something the database enforces.
	-- The same three lines appear on every table below that holds a port type.
	port_type    VARCHAR(16)   NOT NULL CONSTRAINT ck_device_io_port_name_type
		CHECK (port_type COLLATE Latin1_General_100_BIN2 IN ('INPUT', 'OUTPUT', 'RELAY', 'AUDIO', 'TONE')),
	code         VARCHAR(64)   NOT NULL CONSTRAINT uq_device_io_port_name_code UNIQUE,
	name         NVARCHAR(200) NOT NULL,
	retired_at   DATETIME2(3)  NULL,
	created_at   DATETIME2(3)  NOT NULL CONSTRAINT df_device_io_port_name_created_at DEFAULT SYSUTCDATETIME()
);

-- What is physically wired to a port: a loop detector, a gate arm, a lamp, a
-- microphone. The old system has 19 such rows — and this is the one table in it
-- whose identifiers were genuinely unique, which is worth knowing if you ever
-- compare the two. It also spells the same thing two ways there ("FrontMic" and
-- "Front Mic"), and holds the port type in lower case where every other table
-- uses another casing; both are unified here.
--
-- Created to shape, deliberately UNSEEDED — the source of the day could give a
-- count but not the values, and this schema does not invent catalog rows. See the
-- header; V108__device_catalog_completion.sql seeds all 19.
CREATE TABLE io_device_kind (
	io_device_kind_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_io_device_kind PRIMARY KEY,
	external_id       VARCHAR(64)   NOT NULL CONSTRAINT uq_io_device_kind_external_id UNIQUE,
	config_realm      VARCHAR(16)   NOT NULL
		CONSTRAINT df_io_device_kind_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_io_device_kind_realm CHECK (config_realm = 'INSTALLATION'),
	port_type         VARCHAR(16)   NOT NULL CONSTRAINT ck_io_device_kind_type
		CHECK (port_type COLLATE Latin1_General_100_BIN2 IN ('INPUT', 'OUTPUT', 'RELAY', 'AUDIO', 'TONE')),
	code              VARCHAR(64)   NOT NULL CONSTRAINT uq_io_device_kind_code UNIQUE,
	name              NVARCHAR(200) NOT NULL,
	retired_at        DATETIME2(3)  NULL,
	created_at        DATETIME2(3)  NOT NULL CONSTRAINT df_io_device_kind_created_at DEFAULT SYSUTCDATETIME()
);
GO

-- --------------------------------------------------------------------------
-- The catalog rows. Generated by script, with computed identifiers, so they are
-- byte-identical every time they are regenerated. Knowingly short of four device
-- types and four audio port names — see the header; the missing rows are left
-- missing rather than guessed.
--
-- One small table of the old system's does not port at all: a list of attachment
-- types holding a single "Ethernet" row behind a dropdown nothing wrote to.
-- --------------------------------------------------------------------------
INSERT INTO device_type (external_id, code, name) VALUES ('0751b608-4796-523f-923f-9cdb2e19476f', 'AXIS_CAMERA', N'AXIS camera');
INSERT INTO device_type (external_id, code, name) VALUES ('616e3c6c-2358-57a5-a143-4871f47cc411', 'PTZ_CAMERA', N'PTZ camera');
INSERT INTO device_type (external_id, code, name) VALUES ('b72c99cd-1385-527a-86e2-3ccab520d4cf', 'PELCO_CAMERA', N'Pelco camera');
INSERT INTO device_type (external_id, code, name) VALUES ('2150bd0a-5db7-52e1-a6a9-ed2c0ea365a7', 'MILESIGHT_CAMERA', N'Milesight camera');
INSERT INTO device_type (external_id, code, name) VALUES ('d70b5538-9ebb-51d5-ba53-01ac3f15c32a', 'LPR_CAMERA', N'LPR camera');
INSERT INTO device_type (external_id, code, name) VALUES ('57191a8f-275b-5119-a791-3b670b20f24e', 'BARCODE_SCANNER', N'Barcode scanner');
INSERT INTO device_type (external_id, code, name) VALUES ('a46f9ca6-cdfc-5c61-ae60-c5f6f82ac205', 'RFID', N'RFID');
INSERT INTO device_type (external_id, code, name) VALUES ('7ccdd34c-cf01-502d-8431-0e7d4751d616', 'GATE_ARM', N'Gate arm');
INSERT INTO device_type (external_id, code, name) VALUES ('eaa8ac32-429b-5a3d-b728-ca36855fe7ba', 'SCALE', N'Scale');
INSERT INTO device_type (external_id, code, name) VALUES ('5cf0c163-caca-554c-b5bf-c9f01ac1f49b', 'PRINTER', N'Printer');
INSERT INTO device_type (external_id, code, name) VALUES ('016e5c65-84b2-50d3-b1a0-98e40a80f24c', 'PORTAL_SCAN', N'Portal scan');
INSERT INTO device_type (external_id, code, name) VALUES ('80930e83-b70f-5f6c-b551-78ae60c7760e', 'EDGE_DEVICE_DISPLAY', N'Edge device / display');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('64dd0ef5-5a8a-51a6-8506-af33fac9087d', 'INPUT', 'DI1', N'DI1');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('9b2c5df2-d218-59cc-a1b7-0541671b9e9d', 'INPUT', 'DI2', N'DI2');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('dbc02c1f-c02a-56e8-9f8b-f0a5b66c08b9', 'INPUT', 'DI3', N'DI3');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('178363a2-51f0-57e3-9855-469717e2e629', 'INPUT', 'DI4', N'DI4');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('360d34e8-7596-5d91-ad47-1828e83782cb', 'INPUT', 'DI5', N'DI5');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('2a4836b3-1212-5b6d-81d7-5abf1074870e', 'INPUT', 'DI6', N'DI6');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('7307851f-8b2f-5e13-ae8c-c816847d28a2', 'INPUT', 'DI7', N'DI7');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('fa4a13be-9df6-56be-aa2c-916ffc5f6831', 'INPUT', 'DI8', N'DI8');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('f69c2b9e-82f0-55ba-93ae-636436aaab4b', 'INPUT', 'DI9', N'DI9');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('efa5cb6d-c2f1-5ed4-8523-2c8636a80c31', 'INPUT', 'DI10', N'DI10');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('cf33fe0a-bd59-579c-a079-ac5383d94753', 'INPUT', 'CB', N'CB');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('0d62c5a1-bca2-543d-ae21-77b29167490b', 'OUTPUT', 'DO1', N'DO1');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('d6fc1864-3d65-5be1-bc8d-53f95bd7abec', 'OUTPUT', 'DO2', N'DO2');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('e27f5cd3-ab20-5e70-a645-2c2fa01bc998', 'OUTPUT', 'DO3', N'DO3');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('6aeee93c-8b09-5cd0-bd73-62524e19dca1', 'OUTPUT', 'DO4', N'DO4');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('34273c06-5082-5192-9280-ce2aeafc84f4', 'OUTPUT', 'DO5', N'DO5');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('b250c5d4-6d92-5781-a5f0-34466accaf62', 'OUTPUT', 'DO6', N'DO6');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('ad048514-56d0-57c6-95f8-cbc78f83edf6', 'OUTPUT', 'DO7', N'DO7');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('1fadd2c8-4b0f-558c-9fc0-5494b0b623a3', 'OUTPUT', 'DO8', N'DO8');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('a165f00d-a633-5f18-9686-e1494d36db57', 'OUTPUT', 'DO9', N'DO9');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('f9ae14bb-de03-569d-b185-6b4fe18feca4', 'OUTPUT', 'DO10', N'DO10');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('f367fece-c691-583e-87e0-46a1338f31bd', 'RELAY', 'R1', N'R1');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('915bb9d0-f314-5483-8c94-2c849623150a', 'RELAY', 'R2', N'R2');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('9617910e-3a29-53fb-8024-a9dc970713ca', 'RELAY', 'R3', N'R3');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('04ae1b89-ea16-50f3-ac15-29d44cd9eed6', 'RELAY', 'R4', N'R4');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('be62c3f2-9768-5d74-9d8b-333f5736cd42', 'AUDIO', 'FRONT_MIC', N'Front Mic');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('4191ad26-7662-521a-9c36-fd8c864a30a6', 'TONE', 'T1', N'T1');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('a65cb81f-9e7c-5929-a812-19db4e8d8dc4', 'TONE', 'T2', N'T2');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('f67df4e8-906c-5e30-8038-19fe2a27fe11', 'TONE', 'T3', N'T3');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('0f67b434-e360-558d-847c-b99b9fefa0c8', 'TONE', 'T4', N'T4');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('0ea052e6-5ae6-5a07-8131-c31872493ef7', 'TONE', 'T5', N'T5');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('b77bc5f7-1885-54a4-a577-5125701faa3a', 'TONE', 'T6', N'T6');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('6f657998-4a04-5aae-9e29-c3e2111dd58a', 'TONE', 'T7', N'T7');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('0001e841-749c-5ea5-8b39-115ee34e1ec4', 'TONE', 'CS1', N'CS1');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('ed8a8086-bb15-566f-930d-8a3467556cc4', 'TONE', 'CS2', N'CS2');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('935afbba-d4e6-5175-bfb8-769f8a5fcb07', 'TONE', 'CS3', N'CS3');
GO

-- --------------------------------------------------------------------------
-- device — the table V101__world_model.sql created minimally, brought up to the
-- full registry.
--
-- ⚠️ THE ORDER AND THE `GO` SEPARATORS ARE REQUIRED, NOT STYLE. Columns are added
-- nullable first, then filled in, then tightened to NOT NULL and given their
-- constraints — the standard way to add a mandatory column to a table that
-- already has rows. The `GO` lines split this into separate batches because SQL
-- Server compiles a batch as a unit: a statement cannot reference a column that
-- is added in the same batch, and the error it gives is "Invalid column name",
-- which reads like a typo rather than a batching problem.
-- --------------------------------------------------------------------------
ALTER TABLE device ADD
	site_external_id  VARCHAR(64)   NULL,
	device_type_id    BIGINT        NULL,
	device_mode       VARCHAR(16)   NULL,
	device_alias      NVARCHAR(200) NULL,
	device_model      NVARCHAR(200) NULL,
	firmware_version  VARCHAR(20)   NULL,
	description       NVARCHAR(500) NULL,
	resolution        VARCHAR(50)   NULL,
	ip_address        VARCHAR(45)   NULL,
	stream_type       VARCHAR(50)   NULL,
	port              INT           NULL,
	protocol          VARCHAR(10)   NOT NULL CONSTRAINT df_device_protocol DEFAULT 'http',
	manufacturer      NVARCHAR(200) NULL,
	device_url        VARCHAR(512)  NULL,
	device_portal_url VARCHAR(512)  NULL,
	assembly_name     VARCHAR(200)  NULL,
	class_name        VARCHAR(255)  NULL,
	data_capture_mode VARCHAR(25)   NULL,
	wait_time         INT           NULL;
GO

-- Fill in the two new mandatory columns on rows that already exist.
--
-- The site is found by walking the device's lane up through its area to its site.
--
-- The device type is matched from the old free-text column into the catalog:
-- LPR_CAMERA is the same word in both, and 'BARRIER' — which the earlier
-- migration explicitly labelled provisional — is what the catalog calls GATE_ARM.
--
-- ⚠️ Any OTHER value on an existing row finds no catalog row, stays null, and
-- fails the NOT NULL tightening a few statements below. That is deliberate. A
-- device whose type the catalog does not know is a question for a person to
-- answer, and a migration that guessed would bury it.
UPDATE device SET site_external_id = (
	SELECT s.external_id
	FROM lane l
		JOIN area a ON a.area_id = l.area_id
		JOIN site s ON s.site_id = a.site_id
	WHERE l.lane_id = device.lane_id)
WHERE site_external_id IS NULL;

UPDATE device SET device_type_id = (
	SELECT dt.device_type_id FROM device_type dt
	WHERE dt.code = CASE device.device_type WHEN 'BARRIER' THEN 'GATE_ARM' ELSE device.device_type END)
WHERE device_type_id IS NULL;
GO

ALTER TABLE device ALTER COLUMN site_external_id VARCHAR(64) NOT NULL;
GO
ALTER TABLE device ALTER COLUMN device_type_id BIGINT NOT NULL;
GO

ALTER TABLE device ADD CONSTRAINT fk_device_site
	FOREIGN KEY (site_external_id) REFERENCES site (external_id);
ALTER TABLE device ADD CONSTRAINT fk_device_type
	FOREIGN KEY (device_type_id) REFERENCES device_type (device_type_id);
ALTER TABLE device ADD CONSTRAINT ck_device_mode CHECK (
	device_mode IS NULL OR
	device_mode COLLATE Latin1_General_100_BIN2 IN ('IO', 'DATA_CAPTURE'));
GO

-- The provisional free-text type column goes away now that every row points at
-- the catalog. This is where the old system's habit of storing the same fact
-- three times — a code, a display name and a topic string — stops.
ALTER TABLE device DROP COLUMN device_type;
GO

-- The read path: site first, then lane. Reads arrive asking for a lane's devices
-- with the site already fixed by the caller's scope. The build check
-- `ScopeIndexRule` reads this file and fails when a table carrying
-- `site_external_id` has no index leading with it.
CREATE INDEX ix_device_scope ON device (site_external_id, lane_id);
GO

-- --------------------------------------------------------------------------
-- device_io_assignment — what each physical port on a device is wired to, and
-- how it behaves. One row per port: "on this controller, digital input 3 is the
-- exit loop, and it reads high when nothing is over it".
-- --------------------------------------------------------------------------
CREATE TABLE device_io_assignment (
	device_io_assignment_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_device_io_assignment PRIMARY KEY,
	device_id             BIGINT        NOT NULL CONSTRAINT fk_device_io_assignment_device
		REFERENCES device (device_id),
	site_external_id      VARCHAR(64)   NOT NULL CONSTRAINT fk_device_io_assignment_site
		REFERENCES site (external_id),
	port_type             VARCHAR(16)   NOT NULL CONSTRAINT ck_device_io_assignment_type
		CHECK (port_type COLLATE Latin1_General_100_BIN2 IN ('INPUT', 'OUTPUT', 'RELAY', 'AUDIO', 'TONE')),
	io_port               INT           NOT NULL,
	-- Points at the port-name catalog rather than copying its text, so renaming a
	-- port name does not leave stale copies behind.
	--
	-- Nullable only because the catalog itself is knowingly four rows short (see
	-- the header): a port whose name is one of the audio names that could not be
	-- extracted has an alias below and no catalog row until those rows land.
	port_name_id          BIGINT        NULL CONSTRAINT fk_device_io_assignment_port_name
		REFERENCES device_io_port_name (port_name_id),
	port_alias_name       NVARCHAR(200) NULL,
	-- Whether the signal on this port is inverted, and whether it idles high.
	-- Both were mandatory with no default in the old system, so an insert that
	-- forgot either simply failed. The defaults chosen here are the
	-- electrically-neutral readings — no inversion, idles low.
	is_reverse_state      BIT           NOT NULL CONSTRAINT df_device_io_assignment_reverse DEFAULT 0,
	is_initial_high       BIT           NOT NULL CONSTRAINT df_device_io_assignment_initial DEFAULT 0,
	is_data_capture       BIT           NOT NULL CONSTRAINT df_device_io_assignment_capture DEFAULT 0,
	is_gos_audio          BIT           NOT NULL CONSTRAINT df_device_io_assignment_gos DEFAULT 0,
	-- Whether a transition on this port is reported at all. The hardware-facing
	-- service reads these to decide which signal changes are worth forwarding —
	-- a port that flickers can otherwise flood the system.
	--
	-- Both default to reporting. That is the conservative direction on purpose:
	-- silence has to be configured deliberately, and can never be an accident of
	-- a row somebody inserted without thinking about it.
	produce_true_message  BIT           NOT NULL CONSTRAINT df_device_io_assignment_true DEFAULT 1,
	produce_false_message BIT           NOT NULL CONSTRAINT df_device_io_assignment_false DEFAULT 1,
	wait_time             INT           NULL,
	retired_at            DATETIME2(3)  NULL,
	created_at            DATETIME2(3)  NOT NULL CONSTRAINT df_device_io_assignment_created_at DEFAULT SYSUTCDATETIME()
);

-- One assignment per physical port: a device cannot have two rows both claiming
-- to be digital input 3.
CREATE UNIQUE INDEX ux_device_io_assignment ON device_io_assignment (device_id, port_type, io_port)
	WHERE retired_at IS NULL;

-- The read path: site first, then device. Reads arrive asking for one device's
-- port layout, with the site already fixed by the caller's scope.
CREATE INDEX ix_device_io_assignment_scope ON device_io_assignment (site_external_id, device_id);

-- --------------------------------------------------------------------------
-- ptz_preset — named viewpoints for a pan-tilt-zoom camera: "gate 3, looking at
-- the cab window". Storing them is ordinary configuration, which is why they live
-- here. MOVING a camera to one of them is not: that is a direct call to the
-- camera, and it belongs to the service that talks to hardware.
-- --------------------------------------------------------------------------
CREATE TABLE ptz_preset (
	ptz_preset_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_ptz_preset PRIMARY KEY,
	device_id        BIGINT        NOT NULL CONSTRAINT fk_ptz_preset_device REFERENCES device (device_id),
	site_external_id VARCHAR(64)   NOT NULL CONSTRAINT fk_ptz_preset_site REFERENCES site (external_id),
	name             NVARCHAR(200) NOT NULL,
	-- Numbers, at last. These were 255-character strings in the old system, so
	-- nothing stopped a preset holding text a camera could not act on.
	--
	-- Each is nullable so that a preset which only changes one axis — zoom in
	-- without turning — is expressible, rather than forcing a caller to invent
	-- values for the other two.
	pan              DECIMAL(9,3)  NULL,
	tilt             DECIMAL(9,3)  NULL,
	zoom             DECIMAL(9,3)  NULL,
	retired_at       DATETIME2(3)  NULL,
	created_at       DATETIME2(3)  NOT NULL CONSTRAINT df_ptz_preset_created_at DEFAULT SYSUTCDATETIME()
);

-- Two presets on one camera cannot share a name. The old system checked this in
-- application code by selecting first and inserting after, which two concurrent
-- requests can both pass; here the database decides.
CREATE UNIQUE INDEX ux_ptz_preset ON ptz_preset (device_id, name) WHERE retired_at IS NULL;

CREATE INDEX ix_ptz_preset_scope ON ptz_preset (site_external_id, device_id);

-- --------------------------------------------------------------------------
-- resource_configuration — arbitrary named settings hung on any one thing in the
-- world model: a site, an area, a lane or a single device. It is the escape hatch
-- for site-specific values that do not deserve a column of their own.
--
-- `scope_type` says which kind of thing the row is attached to, and
-- `resource_external_id` says which one.
--
-- ⚠️ There is deliberately NO foreign key on `resource_external_id`, and there
-- cannot be: the column points at a different table depending on the row beside
-- it, and a foreign key names one table. That is the price of having one table
-- serve four kinds of owner instead of four near-identical tables, and it is
-- stated here rather than left for someone to discover when a stale row does not
-- go away with the lane it described.
-- --------------------------------------------------------------------------
CREATE TABLE resource_configuration (
	resource_configuration_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_resource_configuration PRIMARY KEY,
	site_external_id     VARCHAR(64)    NOT NULL CONSTRAINT fk_resource_configuration_site
		REFERENCES site (external_id),
	scope_type           VARCHAR(16)    NOT NULL CONSTRAINT ck_resource_configuration_scope
		CHECK (scope_type COLLATE Latin1_General_100_BIN2 IN ('SITE', 'AREA', 'LANE', 'DEVICE')),
	resource_external_id VARCHAR(64)    NOT NULL,
	config_key           VARCHAR(200)   NOT NULL,
	-- Given a maximum length. The old system's equivalent had none, which is how a
	-- settings row quietly becomes somewhere people store payloads.
	config_value         NVARCHAR(2000) NULL,
	retired_at           DATETIME2(3)   NULL,
	created_at           DATETIME2(3)   NOT NULL CONSTRAINT df_resource_configuration_created_at DEFAULT SYSUTCDATETIME()
);

-- One value per key per thing. In the old system this was enforced by an index
-- that did not quite cover it, plus a de-duplication key that ignored the site
-- entirely — so the same key on two sites collided.
CREATE UNIQUE INDEX ux_resource_configuration
	ON resource_configuration (scope_type, resource_external_id, config_key)
	WHERE retired_at IS NULL;

CREATE INDEX ix_resource_configuration_scope
	ON resource_configuration (site_external_id, scope_type, resource_external_id);
GO

-- --------------------------------------------------------------------------
-- The published device view, republished.
--
-- Other services cannot read this schema's tables — they read views core
-- publishes and grants them access to. A published view is a contract, so this
-- redefinition answers EXACTLY the same columns as before; the services reading
-- it are unaffected and need no coordinated change. All that moves is where
-- `device_type` comes from: the free-text column dropped above, now the catalog's
-- code.
--
-- ALTER VIEW rather than DROP and CREATE, because dropping a view discards the
-- permissions granted on it and every reader would lose access until they were
-- granted again. Altering keeps them.
-- --------------------------------------------------------------------------
ALTER VIEW topology_device AS
SELECT
	d.device_id,
	d.external_id          AS device_external_id,
	dt.code                AS device_type,
	d.name                 AS device_name,
	d.address,
	l.lane_id,
	l.external_id          AS lane_external_id,
	s.site_id,
	s.external_id          AS site_external_id
FROM device d
	JOIN device_type dt ON dt.device_type_id = d.device_type_id
	JOIN lane l ON l.lane_id = d.lane_id
	JOIN area a ON a.area_id = l.area_id
	JOIN site s ON s.site_id = a.site_id
WHERE d.retired_at IS NULL
  AND dt.retired_at IS NULL
  AND l.retired_at IS NULL
  AND a.retired_at IS NULL
  AND s.retired_at IS NULL;
GO
