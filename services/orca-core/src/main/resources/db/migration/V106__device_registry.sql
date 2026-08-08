-- orca-core · WP3 — the device registry completed.
--
-- Translated from docs/core-config-schema-from-1x.md §3. The headlines:
--
--   * The three catalogs land with pinned identity (rule 7): UUIDv5 in the
--     namespace uuid5(NAMESPACE_URL, 'orca:2.0:device-catalog'), name
--     '<kind>:<code>' — deterministic, byte-stable, regenerable.
--     ⚠️ THE SHEET'S ROW LISTS ARE PARTIAL, AND THE GAPS ARE SEEDED AS GAPS,
--     NOT FILLED (the programme's most defended rule):
--       - device_type: 12 of 16 rows are namable from the sheet; the other 4
--         hide behind an "etc." and are NOT invented. Camera-type display
--         names are provisional (display-only by rule 7).
--       - device_io_port_name: 36 of 40 — the sheet enumerates every group
--         except four of the five audio names ("Front Mic" is the one it
--         names, via the naming-drift note).
--       - io_device_kind: created UNSEEDED. The sheet carries a count (19)
--         and example names but not the (name, input_type) pairs, and this
--         phase's seeds are written from extractions, never from memory. A
--         re-extraction like the entitlement catalog's file fills it.
--     All three gaps are recorded in docs/phase-2-report.md.
--
--   * core.device grows to the sheet's translated column set. ONE FK to the
--     type catalog — 1.x's three denormalized copies (device_type,
--     device_type_name, topic_device_type) die here, and V101's provisional
--     free-VARCHAR vocabulary is settled by the catalog: the demo's
--     provisional 'BARRIER' becomes the 1.x catalog's GATE_ARM.
--   * device gains the scope column (site_external_id, FK-backed, backfilled
--     from lane→area→site, scope-leading index) — the fielded pattern, now on
--     the registry itself.
--   * NOT ported, deliberately: encrypted_username/encrypted_password
--     (security-shaped; the storage design is PROPOSED in the report and the
--     product owner decides — until then the columns do not exist);
--     device_host (the fielded 2.0 design already puts the device host per
--     LANE — two sources for one address is how they disagree; architecture
--     wins); topic_name / device_type_name / topic_device_type (computed and
--     denormalized; 2.0 has no Kafka topics).
--   * device_io_assignment: the unified port-type enum (rule 5 — TitleCase in
--     one 1.x table, lowercase in another; here ONE casing with a binary-
--     collated CHECK), a catalog FK instead of the copied name string,
--     defaults for the two flags 1.x left NOT NULL with no default, and the
--     edge-suppression flags kept because edge reads them. is_output_port is
--     derivable from port_type and does not port (rule 6).
--   * ptz_preset: pan/tilt/zoom numeric — varchar(255) in 1.x.
--   * resource_configuration: typed scope, per-scope unique, sized value,
--     scope-leading index. 1.x's two seeded rows — the login-lockout policy
--     hardcoded against site_id = 1 — do NOT port: lockout policy is
--     Keycloak's (rule 9).

-- --------------------------------------------------------------------------
-- The catalogs (installation-realm)
-- --------------------------------------------------------------------------
CREATE TABLE device_type (
	device_type_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_device_type PRIMARY KEY,
	external_id    VARCHAR(64)   NOT NULL CONSTRAINT uq_device_type_external_id UNIQUE,
	config_realm   VARCHAR(16)   NOT NULL
		CONSTRAINT df_device_type_realm DEFAULT 'INSTALLATION'
		CONSTRAINT ck_device_type_realm CHECK (config_realm = 'INSTALLATION'),
	code           VARCHAR(64)   NOT NULL CONSTRAINT uq_device_type_code UNIQUE,
	name           NVARCHAR(200) NOT NULL,
	-- The per-type JSON form-schema that drives the admin UI (1.x
	-- device_configuration). KEPT AS JSON deliberately (rule 11): it is a
	-- document describing a form, read whole and written whole, and modelling
	-- it relationally would buy no query anybody runs. Content was not
	-- extractable from the sheet, so seeded NULL; recorded.
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
	-- One casing for the port-type enum, everywhere it appears (rule 5).
	port_type    VARCHAR(16)   NOT NULL CONSTRAINT ck_device_io_port_name_type
		CHECK (port_type COLLATE Latin1_General_100_BIN2 IN ('INPUT', 'OUTPUT', 'RELAY', 'AUDIO', 'TONE')),
	code         VARCHAR(64)   NOT NULL CONSTRAINT uq_device_io_port_name_code UNIQUE,
	name         NVARCHAR(200) NOT NULL,
	retired_at   DATETIME2(3)  NULL,
	created_at   DATETIME2(3)  NOT NULL CONSTRAINT df_device_io_port_name_created_at DEFAULT SYSUTCDATETIME()
);

-- 1.x io_devices: 19 rows, the estate's only genuinely-unique uuid, lowercase
-- input_type (unified here), and naming drift (FrontMic vs Front Mic). Created
-- to the target shape, UNSEEDED — see the header.
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
-- The catalog seeds — generated, pinned, byte-stable. attachment_types (one
-- near-dead "Ethernet" row behind a read-only dropdown) does not port at all;
-- surfaced in the report per the sheet's own suggestion.
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
-- device — the registry completed. Columns first (nullable), then backfill,
-- then the constraints; GO separates the batches because a batch cannot
-- reference a column it also adds.
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

-- Backfill the scope column from the hierarchy, and the catalog FK from the
-- provisional V101 vocabulary: LPR_CAMERA is the same word in the catalog;
-- 'BARRIER' was named provisional in V101 and the 1.x catalog's word for the
-- barrier is GATE_ARM. Any OTHER value on an existing row has no catalog
-- home and fails the NOT NULL below — deliberately: a device of a type the
-- catalog does not know is a question for a person, not a guess.
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

-- The three denormalized 1.x copies die here; V101's provisional free VARCHAR
-- is the local one, and the catalog now carries the vocabulary.
ALTER TABLE device DROP COLUMN device_type;
GO

-- The scope-leading read path (ScopeIndexRule reads this file).
CREATE INDEX ix_device_scope ON device (site_external_id, lane_id);
GO

-- --------------------------------------------------------------------------
-- device_io_assignment — the port layout
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
	-- FK to the catalog instead of 1.x's copied name string (rule 6). Nullable
	-- because the catalog itself is knowingly 4 rows short (see header): a port
	-- whose 1.x name is one of the unextracted audio names has an alias and no
	-- catalog row until the re-extraction lands.
	port_name_id          BIGINT        NULL CONSTRAINT fk_device_io_assignment_port_name
		REFERENCES device_io_port_name (port_name_id),
	port_alias_name       NVARCHAR(200) NULL,
	-- NOT NULL with no default in 1.x — an insert that forgot either simply
	-- failed. The defaults are the electrically-neutral readings.
	is_reverse_state      BIT           NOT NULL CONSTRAINT df_device_io_assignment_reverse DEFAULT 0,
	is_initial_high       BIT           NOT NULL CONSTRAINT df_device_io_assignment_initial DEFAULT 0,
	is_data_capture       BIT           NOT NULL CONSTRAINT df_device_io_assignment_capture DEFAULT 0,
	is_gos_audio          BIT           NOT NULL CONSTRAINT df_device_io_assignment_gos DEFAULT 0,
	-- Edge-suppression semantics — edge reads these. Defaulting to PRODUCE is
	-- the conservative direction: silence is configured, never accidental.
	produce_true_message  BIT           NOT NULL CONSTRAINT df_device_io_assignment_true DEFAULT 1,
	produce_false_message BIT           NOT NULL CONSTRAINT df_device_io_assignment_false DEFAULT 1,
	wait_time             INT           NULL,
	retired_at            DATETIME2(3)  NULL,
	created_at            DATETIME2(3)  NOT NULL CONSTRAINT df_device_io_assignment_created_at DEFAULT SYSUTCDATETIME()
);

-- A device has one assignment per (type, number) — the natural key.
CREATE UNIQUE INDEX ux_device_io_assignment ON device_io_assignment (device_id, port_type, io_port)
	WHERE retired_at IS NULL;

-- Scope-leading, device second: the 2.0 equivalent of 1.x's covering index
-- (device_id, is_active) INCLUDE (…) — reads arrive per device, under the site.
CREATE INDEX ix_device_io_assignment_scope ON device_io_assignment (site_external_id, device_id);

-- --------------------------------------------------------------------------
-- ptz_preset (1.x perspective_details) — storage is plain config; EXECUTION is
-- a direct camera call and lives with edge (register NEW-5, narrowed 7 Aug).
-- --------------------------------------------------------------------------
CREATE TABLE ptz_preset (
	ptz_preset_id    BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_ptz_preset PRIMARY KEY,
	device_id        BIGINT        NOT NULL CONSTRAINT fk_ptz_preset_device REFERENCES device (device_id),
	site_external_id VARCHAR(64)   NOT NULL CONSTRAINT fk_ptz_preset_site REFERENCES site (external_id),
	name             NVARCHAR(200) NOT NULL,
	-- Numeric, at last — varchar(255) in 1.x. Nullable: the 1.x strings were
	-- unconstrained and a preset that only zooms is expressible.
	pan              DECIMAL(9,3)  NULL,
	tilt             DECIMAL(9,3)  NULL,
	zoom             DECIMAL(9,3)  NULL,
	retired_at       DATETIME2(3)  NULL,
	created_at       DATETIME2(3)  NOT NULL CONSTRAINT df_ptz_preset_created_at DEFAULT SYSUTCDATETIME()
);

-- (device, name) was app-level-unique only in 1.x; here it is the database's.
CREATE UNIQUE INDEX ux_ptz_preset ON ptz_preset (device_id, name) WHERE retired_at IS NULL;

CREATE INDEX ix_ptz_preset_scope ON ptz_preset (site_external_id, device_id);

-- --------------------------------------------------------------------------
-- resource_configuration — §C1's custom variables, designed properly: typed
-- scope, per-scope unique, sized value, scope-leading index. Polymorphic
-- reference by design, so no FK on resource_external_id — the price of one
-- table for four scopes, stated rather than hidden.
-- --------------------------------------------------------------------------
CREATE TABLE resource_configuration (
	resource_configuration_id BIGINT IDENTITY(1,1) NOT NULL CONSTRAINT pk_resource_configuration PRIMARY KEY,
	site_external_id     VARCHAR(64)    NOT NULL CONSTRAINT fk_resource_configuration_site
		REFERENCES site (external_id),
	scope_type           VARCHAR(16)    NOT NULL CONSTRAINT ck_resource_configuration_scope
		CHECK (scope_type COLLATE Latin1_General_100_BIN2 IN ('SITE', 'AREA', 'LANE', 'DEVICE')),
	resource_external_id VARCHAR(64)    NOT NULL,
	config_key           VARCHAR(200)   NOT NULL,
	-- Sized. 1.x's value was unsized, which is how a settings row becomes a
	-- payload store.
	config_value         NVARCHAR(2000) NULL,
	retired_at           DATETIME2(3)   NULL,
	created_at           DATETIME2(3)   NOT NULL CONSTRAINT df_resource_configuration_created_at DEFAULT SYSUTCDATETIME()
);

-- The per-scope natural key 1.x enforced with a half-useful index and an
-- idempotency key that ignored the site.
CREATE UNIQUE INDEX ux_resource_configuration
	ON resource_configuration (scope_type, resource_external_id, config_key)
	WHERE retired_at IS NULL;

CREATE INDEX ix_resource_configuration_scope
	ON resource_configuration (site_external_id, scope_type, resource_external_id);
GO

-- --------------------------------------------------------------------------
-- topology_device, republished. SAME COLUMNS — the view is a contract (ADR-009)
-- and runtime/edge keep reading exactly what they read; only device_type's
-- source moves from the dead free-VARCHAR to the catalog's code. Grants
-- survive ALTER VIEW.
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
