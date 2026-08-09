-- Fills the three device catalogs that an earlier migration deliberately left
-- incomplete, and corrects three codes it had to guess at.
--
-- WHY THERE WAS A GAP AT ALL
-- The catalogs were translated from the Go system in production today. The
-- extraction available at the time described some of the rows only in prose —
-- "and so on" where a list should have been — and this project does not invent
-- catalog rows to fill a hole, because a guessed value in a catalog other tables
-- point at becomes permanent very quickly. So the earlier migration seeded what
-- was known and left the rest visibly missing, with the fix recorded as owed
-- work: extract the rows properly by script, then seed the remainder in a later
-- migration. This is that later migration.
--
-- docs/device-catalog-completion-from-1x.md is the proper extraction — read by
-- script out of the old system's own migration source, every row verbatim.
--
-- After this file runs: 19 input/output device kinds (was none at all), 40 port
-- names (was 36), 16 device types (was 12).
--
-- GENERATED, NOT HAND-TRANSCRIBED. deploy/tools/gen-device-catalog-completion-seed.py
-- parses that document, asserts the expected counts before it will emit a single
-- line, and computes each external identifier by hashing the row's table and code
-- inside the same namespace the earlier migration used. Regenerating produces
-- byte-identical output. Edit a row here by hand and that chain of custody is
-- broken; regenerate instead.
--
-- WHY THREE ROWS ARE UPDATED RATHER THAN INSERTED
-- The earlier migration had to guess three camera codes. The real ones are
-- AXIS_PTZ_CAMERA, PELCO_PTZ_CAMERA and MILESIGHT_PTZ_CAMERA. Because the code is
-- the contract and the identifier is derived FROM the code, correcting a code
-- also means correcting the identifier — each row is given the identifier that
-- code would always have produced, rather than keeping one derived from a name
-- that was never right.
--
-- That is only safe because nothing referenced the provisional codes. That was
-- checked by searching the services, the deployment scripts and the build checks
-- before this shipped — one integration test did, and it was updated alongside
-- this migration — and because no installation of this system has been deployed
-- anywhere yet.
--
-- The display names also move to the exact casing the source system uses. Names
-- here are for display only and nothing matches on them, so that is a correction
-- rather than a breaking change.
-- Correct the three guessed camera codes, and the identifiers derived from them.
UPDATE device_type SET code = 'AXIS_PTZ_CAMERA', external_id = '06d4788b-de8e-5d8c-a7b8-6389bd99841e', name = N'AXIS PTZ Camera' WHERE code = 'PTZ_CAMERA';
UPDATE device_type SET code = 'PELCO_PTZ_CAMERA', external_id = '826f1091-32ae-5a1f-b48c-60694f8c6b2f', name = N'Pelco PTZ Camera' WHERE code = 'PELCO_CAMERA';
-- The same three corrections, continued. Each matches on the code it is
-- replacing, so a statement whose row has already been corrected simply matches
-- nothing and changes nothing.
UPDATE device_type SET code = 'AXIS_PTZ_CAMERA', external_id = '06d4788b-de8e-5d8c-a7b8-6389bd99841e', name = N'AXIS PTZ Camera' WHERE code = 'PTZ_CAMERA';
UPDATE device_type SET code = 'PELCO_PTZ_CAMERA', external_id = '826f1091-32ae-5a1f-b48c-60694f8c6b2f', name = N'Pelco PTZ Camera' WHERE code = 'PELCO_CAMERA';
UPDATE device_type SET code = 'MILESIGHT_PTZ_CAMERA', external_id = 'd2501f59-5006-5db3-8653-427878ee2729', name = N'Milesight PTZ Camera' WHERE code = 'MILESIGHT_CAMERA';

-- The rows whose codes were already right get their display names put into the
-- exact casing the source system uses. The earlier migration wrote these in a
-- provisional lower case. Names are for display only and nothing matches on them,
-- so this is a correction rather than a breaking change.
UPDATE device_type SET name = N'AXIS Camera' WHERE code = 'AXIS_CAMERA';
UPDATE device_type SET name = N'Barcode Scanner' WHERE code = 'BARCODE_SCANNER';
UPDATE device_type SET name = N'RFID' WHERE code = 'RFID';
UPDATE device_type SET name = N'Gate Arm' WHERE code = 'GATE_ARM';
UPDATE device_type SET name = N'Scale' WHERE code = 'SCALE';
UPDATE device_type SET name = N'Edge Device/Display' WHERE code = 'EDGE_DEVICE_DISPLAY';
UPDATE device_type SET name = N'LPR Camera' WHERE code = 'LPR_CAMERA';
UPDATE device_type SET name = N'Portal Scan' WHERE code = 'PORTAL_SCAN';
UPDATE device_type SET name = N'Printer' WHERE code = 'PRINTER';

-- The four device types the earlier extraction could not name, bringing the
-- catalog to its full 16.
INSERT INTO device_type (external_id, code, name) VALUES ('5b4f2f95-7279-5b79-a240-b1f94068b797', 'PROXIMITY_READER', N'Proximity Reader');
INSERT INTO device_type (external_id, code, name) VALUES ('a80334dc-a098-5c37-b4fa-c60bd7476be3', 'CAPTURE_ID', N'Capture ID');
INSERT INTO device_type (external_id, code, name) VALUES ('7f85b592-1742-5287-b149-d26979f7d84d', 'PINHOLE_CAMERA', N'Pinhole Camera');
INSERT INTO device_type (external_id, code, name) VALUES ('e8836362-ff8f-59d6-ad8b-a9aba6639eeb', 'LPR_READER', N'LPR Reader');

-- The four audio port names the earlier extraction could not reach, bringing the
-- catalog to its full 40. There are five audio ports; the front microphone was
-- already seeded, because the earlier document happened to name it in passing
-- while explaining that the source system spells it two different ways.
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('acebd90e-b35e-582e-b577-14fda723b6b8', 'AUDIO', 'HANDSET_SPEAKER', N'Handset Speaker');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('40a3add0-3ddc-5371-8ad4-fb88adfc99c9', 'AUDIO', 'HANDSET_MIC', N'Handset Mic');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('1fdb5fce-c9d0-55cc-9149-ef9c65f316c3', 'AUDIO', 'FRONT_SPEAKER', N'Front Speaker');
INSERT INTO device_io_port_name (external_id, port_type, code, name) VALUES ('d988cc03-9d86-5634-8a84-6ffa831f05b8', 'AUDIO', 'REAR_SPEAKER', N'Rear Speaker');

-- All 19 kinds of thing that can be wired to a port. The table was created empty
-- by the earlier migration because the values were not extractable then; these
-- are the whole list.
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
