-- orca-core · Phase 3 WP0 — the device-catalog completion V106 owed.
--
-- V106 seeded the catalogs AS GAPS, deliberately (phase-2 report §5.4): the
-- sheet of the day had only elliptical prose, and the report's own
-- recommendation was "give the device catalogs the same treatment the
-- entitlement catalog got — a script-extracted file — and seed the remainder in
-- a later migration". docs/device-catalog-completion-from-1x.md is that file
-- (script-read from 1.x's migration.go, every row verbatim), and this is that
-- later migration. After it: io_device_kind 19 rows (was 0),
-- device_io_port_name 40 (was 36), device_type 16 (was 12).
--
-- GENERATED, NOT TRANSCRIBED — deploy/tools/gen-device-catalog-completion-seed.py
-- parses the sheet, asserts its counts (19 / 5 / 16) before emitting a row, and
-- mints external ids in V106's own namespace
-- (uuid5(uuid5(NAMESPACE_URL, 'orca:2.0:device-catalog'), '<table>:<code>')),
-- so regeneration is byte-identical.
--
-- Three rows are UPDATED, not inserted: V106's PTZ_CAMERA / PELCO_CAMERA /
-- MILESIGHT_CAMERA were provisional guesses at codes the sheet could not then
-- name; the 1.x-exact codes are AXIS_PTZ_CAMERA / PELCO_PTZ_CAMERA /
-- MILESIGHT_PTZ_CAMERA. The code is the contract (rule 7), so the external id
-- follows the corrected code — each row gets the UUID that code would always
-- have minted. Nothing references the provisional codes: grepped across
-- services, deploy and build-checks before this shipped (one integration test
-- did, updated with this migration), and no deployment exists (the register:
-- there is no release pipeline yet). Display names move to the 1.x-verbatim
-- casing everywhere — display-only by rule 7, so a correction, not a break.
-- device_type: correct the three provisional PTZ codes to the 1.x-exact codes.
-- The external id FOLLOWS the code (identity is the code, translation rule 7),
-- so each corrected row gets the UUID its code would always have minted.
UPDATE device_type SET code = 'AXIS_PTZ_CAMERA', external_id = '06d4788b-de8e-5d8c-a7b8-6389bd99841e', name = N'AXIS PTZ Camera' WHERE code = 'PTZ_CAMERA';
UPDATE device_type SET code = 'PELCO_PTZ_CAMERA', external_id = '826f1091-32ae-5a1f-b48c-60694f8c6b2f', name = N'Pelco PTZ Camera' WHERE code = 'PELCO_CAMERA';
-- device_type: correct the three provisional PTZ codes to the 1.x-exact codes.
-- The external id FOLLOWS the code (identity is the code, translation rule 7),
-- so each corrected row gets the UUID its code would always have minted.
UPDATE device_type SET code = 'AXIS_PTZ_CAMERA', external_id = '06d4788b-de8e-5d8c-a7b8-6389bd99841e', name = N'AXIS PTZ Camera' WHERE code = 'PTZ_CAMERA';
UPDATE device_type SET code = 'PELCO_PTZ_CAMERA', external_id = '826f1091-32ae-5a1f-b48c-60694f8c6b2f', name = N'Pelco PTZ Camera' WHERE code = 'PELCO_CAMERA';
UPDATE device_type SET code = 'MILESIGHT_PTZ_CAMERA', external_id = 'd2501f59-5006-5db3-8653-427878ee2729', name = N'Milesight PTZ Camera' WHERE code = 'MILESIGHT_CAMERA';

-- device_type: move the already-correct rows' display names to the 1.x-verbatim casing
-- (display-only by rule 7; V106 carried them lowercase-provisional).
UPDATE device_type SET name = N'AXIS Camera' WHERE code = 'AXIS_CAMERA';
UPDATE device_type SET name = N'Barcode Scanner' WHERE code = 'BARCODE_SCANNER';
UPDATE device_type SET name = N'RFID' WHERE code = 'RFID';
UPDATE device_type SET name = N'Gate Arm' WHERE code = 'GATE_ARM';
UPDATE device_type SET name = N'Scale' WHERE code = 'SCALE';
UPDATE device_type SET name = N'Edge Device/Display' WHERE code = 'EDGE_DEVICE_DISPLAY';
UPDATE device_type SET name = N'LPR Camera' WHERE code = 'LPR_CAMERA';
UPDATE device_type SET name = N'Portal Scan' WHERE code = 'PORTAL_SCAN';
UPDATE device_type SET name = N'Printer' WHERE code = 'PRINTER';

-- device_type: the four rows the V106 sheet could not name (16 total).
INSERT INTO device_type (external_id, code, name) VALUES ('5b4f2f95-7279-5b79-a240-b1f94068b797', 'PROXIMITY_READER', N'Proximity Reader');
INSERT INTO device_type (external_id, code, name) VALUES ('a80334dc-a098-5c37-b4fa-c60bd7476be3', 'CAPTURE_ID', N'Capture ID');
INSERT INTO device_type (external_id, code, name) VALUES ('7f85b592-1742-5287-b149-d26979f7d84d', 'PINHOLE_CAMERA', N'Pinhole Camera');
INSERT INTO device_type (external_id, code, name) VALUES ('e8836362-ff8f-59d6-ad8b-a9aba6639eeb', 'LPR_READER', N'LPR Reader');

-- device_io_port_name: the four Audio rows Phase 2 could not extract (40 total;
-- FRONT_MIC was already seeded by V106 via the sheet's naming-drift note).
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('acebd90e-b35e-582e-b577-14fda723b6b8', 'AUDIO', 'HANDSET_SPEAKER', N'Handset Speaker');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('40a3add0-3ddc-5371-8ad4-fb88adfc99c9', 'AUDIO', 'HANDSET_MIC', N'Handset Mic');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('1fdb5fce-c9d0-55cc-9149-ef9c65f316c3', 'AUDIO', 'FRONT_SPEAKER', N'Front Speaker');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('d988cc03-9d86-5634-8a84-6ffa831f05b8', 'AUDIO', 'REAR_SPEAKER', N'Rear Speaker');

-- io_device_kind: all 19 rows (created to shape in V106, seeded empty).
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('a51bc591-fac8-5e8b-9e10-01af7d2e9b8c', 'INPUT', 'CALL_BUTTON', N'Call Button');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('63499b8c-c9ce-53d5-9e0c-f0ded12804b0', 'INPUT', 'HOOK_SWITCH', N'Hook Switch');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('1a186b4b-4405-55ac-ade5-4b960f715fa9', 'INPUT', 'LOOP', N'Loop');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('573fe387-5001-54c1-968f-05f3188a0758', 'INPUT', 'LOOP_A', N'Loop A');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('df22e8d8-3ac3-59a6-8972-9d101951891d', 'INPUT', 'LOOP_B', N'Loop B');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('b6175183-4083-5386-8e31-493f1420f661', 'INPUT', 'LOOP_C', N'Loop C');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('8825a889-3ce9-5d31-b387-b92b49ddb853', 'OUTPUT', 'LIGHT', N'Light');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('ce5d281d-0f3f-553c-ac58-15392d954d25', 'OUTPUT', 'GATE_ARM', N'Gate Arm');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('61f22d23-b34c-594d-a38d-1b252a208c9a', 'OUTPUT', 'PRINT_JOB', N'Print Job');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('7db8f749-1a2e-5ea1-b87a-76366848fccc', 'OUTPUT', 'RED_LAMP', N'Red Lamp');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('fe4a1e1b-0d78-5467-911c-547177b84752', 'OUTPUT', 'GREEN_LAMP', N'Green Lamp');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('24f43a2c-696e-5265-a2bb-ce6b17e783ec', 'OUTPUT', 'ORANGE_LAMP', N'Orange Lamp');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('bff6216a-1e19-5d9d-b2d8-171159dc7743', 'RELAY', 'TRAFFIC_SIGNAL', N'Traffic Signal');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('48749050-a358-5ab6-b337-b6221e1830a5', 'AUDIO', 'MICROPHONE', N'Microphone');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('23043e11-c4d7-5d8f-b624-e9f502355559', 'AUDIO', 'SPEAKER', N'Speaker');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('92f02bfd-4ed1-5afa-ae29-3ee957756c88', 'AUDIO', 'FRONT_MIC', N'Front Mic');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('34765a02-48ea-54c1-88da-d2120f11f7d0', 'AUDIO', 'FRONT_SPEAKER', N'Front Speaker');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('06a5effc-b6a6-503f-b12b-7f6c80739037', 'AUDIO', 'REAR_SPEAKER', N'Rear Speaker');
INSERT INTO io_device_kind (external_id, port_type, code, name) VALUES ('d33b1ece-d5fe-53c0-86b1-326ca2eae238', 'TONE', 'TONE_GENERATOR', N'Tone Generator');
