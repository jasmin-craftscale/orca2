# Device catalog completion — exact 1.x seed rows

**⚠️ DERIVED-FROM-1X, script-read from `Lynxis-Gate/common/migration/migration.go` seed functions, 9 Aug 2026 — every row verbatim.** Closes the gap the Phase 2 report §5.4 named: the device catalogs were seeded partial because the earlier sheet had only prose. This file is the zero-guess input for a small top-up migration in `orca-core`. Per translation rule 7: **re-mint pinned UUIDs and add a stable machine `code`** — 1.x's are all `uuid.NewString()` (random per install, referenced by nothing). Per translation rule 5: **unify the casing** (1.x mixes lowercase `input_type`, TitleCase `port_type`, and mixed-case topic names) — recommend UPPER_SNAKE for the machine code, display names as-is.

## `io_device_kind` — 19 rows (1.x `io_devices`, seeded EMPTY in Phase 2)

`(name, input_type, code)` — 1.x `input_type` is lowercase; unify to the chosen casing.

| name | input_type | code (1.x topic_name) |
|---|---|---|
| Call Button | input | CALL_BUTTON |
| Hook Switch | input | HOOK_SWITCH |
| Loop | input | LOOP |
| Loop A | input | LOOP_A |
| Loop B | input | LOOP_B |
| Loop C | input | LOOP_C |
| Light | output | LIGHT |
| Gate Arm | output | GATE_ARM |
| Print Job | output | PRINT_JOB |
| Red Lamp | output | RED_LAMP |
| Green Lamp | output | GREEN_LAMP |
| Orange Lamp | output | ORANGE_LAMP |
| Traffic Signal | relay | TRAFFIC_SIGNAL |
| Microphone | audio | MICROPHONE |
| Speaker | audio | SPEAKER |
| Front Mic | audio | FRONT_MIC |
| Front Speaker | audio | FRONT_SPEAKER |
| Rear Speaker | audio | REAR_SPEAKER |
| Tone Generator | tone | TONE_GENERATOR |

*(1.x display names `FrontMic`/`FrontSpeaker`/`RearSpeaker` had no space; normalized to "Front Mic" etc. to match the port-name catalog. Note the port-name catalog also has "Handset Speaker"/"Handset Mic" that have no io_device_kind — leave as-is.)*

## `device_io_port_name` — the 5 Audio rows Phase 2 was missing

Phase 2 seeded 36 of 40 (the Input/Output/Relay/Tone rows were determined; Audio was not). The full Audio set:

| port_type | io_port_name |
|---|---|
| Audio | Handset Speaker |
| Audio | Handset Mic |
| Audio | Front Speaker |
| Audio | Front Mic |
| Audio | Rear Speaker |

*(Input DI1–DI10+CB, Output DO1–DO10, Relay R1–R4, Tone T1–T7+CS1–CS3 = the 35 already seeded; +5 Audio = 40.)*

## `device_type` — the 4 display names Phase 2 left provisional

Phase 2 seeded 12 with 4 behind an "etc." and the 4 camera display names provisional. The full 16 distinct (1.x has 17 entries — Portal Scan is duplicated, collapses on the name key):

| device_type_name | code (1.x topic_name) |
|---|---|
| AXIS Camera | AXIS_CAMERA |
| AXIS PTZ Camera | AXIS_PTZ_CAMERA |
| Pelco PTZ Camera | PELCO_PTZ_CAMERA |
| Milesight PTZ Camera | MILESIGHT_PTZ_CAMERA |
| Barcode Scanner | BARCODE_SCANNER |
| Proximity Reader | PROXIMITY_READER |
| RFID | RFID |
| Capture ID | CAPTURE_ID |
| Gate Arm | GATE_ARM |
| Scale | SCALE |
| Edge Device/Display | EDGE_DEVICE_DISPLAY |
| LPR Camera | LPR_CAMERA |
| Pinhole Camera | PINHOLE_CAMERA |
| Portal Scan | PORTAL_SCAN |
| LPR Reader | LPR_READER |
| Printer | PRINTER |

*(1.x codes mixed case — `AXIS_Camera`, `Pelco_PTZ_Camera` — normalized to UPPER_SNAKE. 1.x's `EDGE_DEVICE/DISPLAY` had a literal `/` in the code — cleaned to `EDGE_DEVICE_DISPLAY`; the display name keeps the slash. The `device_configuration` JSON form-schema per type is UI metadata — carry only if the target models per-type config forms; otherwise leave it.)*

**This is a small, self-contained top-up.** It is a core migration + seed, no behaviour, no endpoints beyond what Phase 2 already exposes. Suggested as Phase 3 WP0 (a warm-up that closes owed hygiene) or a standalone task.
