# Core configuration schema — extracted from ORCA 1.x

**⚠️ Provenance: derived from the fielded 1.x code, not from a design document.**
Sources: `Lynxis-Gate/common/entity/*.go` (the GORM entities that ARE the 1.x schema — there is no CREATE TABLE anywhere; `AutoMigrate` over ~150 entities builds it), `common/migration/migration.go` (8,863 lines: the entity list, all raw-SQL indexes, and every seed row), plus repository call sites where semantics needed verifying. Extracted 8 Aug 2026 by the orchestrator session for `docs/phase-2-plan.md`. **This is input material for writing migrations, not a maintained document** — once a table's migration exists, the migration is the truth and this sheet is history.

**How to read it:** each table lists 1.x's real columns, keys, seeds and defects. The **translation rules** below override anything a 1.x shape implies. Where a 1.x defect is named, it is named so it is *not* ported. `[STD]` = 1.x's standard column set: `is_active bool default true`, `is_deleted bool default false`, `created_by varchar(200) not null`, `created_on not null`, `modified_by varchar(200)`, `modified_on` — which 2.0 **replaces** with its own conventions (see rule 1).

---

## 0 · Translation rules — these override 1.x shapes everywhere

1. **Conventions come from the migrations that already exist**, not from 1.x: follow `services/orca-core/src/main/resources/db/migration/V101__world_model.sql` for identifier style, external-id columns, audit columns and deletion semantics. Extend the established pattern; never import `is_active`/`is_deleted` pairs.
2. **Every external identifier is UNIQUE from day one.** 1.x has essentially no unique constraints (exactly one `unique` tag across all four clusters); a 2026 retrofit migration adds a handful and **silently degrades to non-unique when duplicates exist**. Every `*_uuid`-equivalent in 2.0 gets a real UNIQUE constraint, uniform width.
3. **Natural keys get unique constraints too.** 1.x enforces `(role, action-item)`, `(team, member)`, `(user, grid)`, `(scope, key)`, `(device, perspective-name)` etc. only by racy SELECT-then-INSERT. In 2.0 these are database constraints.
4. **Transcribe intent, not tags.** Several 1.x columns don't match their annotations because the GORM tag is malformed (`type=time`, bare `notnull`, missing `column:`) — flagged per table below. The 1.x *database* column is sometimes nullable where the code intended NOT NULL.
5. **Enums become constrained values with one casing.** 1.x has the same conceptual enum in TitleCase and lowercase in different tables, and one comparison that only works because SQL Server collation is case-insensitive. Every enum below is listed with its observed values; pick one casing, add a CHECK.
6. **No denormalized copies.** 1.x persists computed topic strings, duplicate type names, and derivable FK chains (each flagged below). None of them port — 2.0 has no Kafka topics and reads through views.
7. **Seeds get pinned UUIDs and stable codes.** 1.x seeds most lookups with `uuid.NewString()` — random per install, referenced by nothing; identity lives in display names, which the frontend and licence gate then match on (a load-bearing-strings contract). 2.0 seeds pin every UUID and add a stable machine `code`; display names become display-only. (New-clients-only means even 1.x's 189 hardcoded entitlement UUIDs are free to re-mint.)
8. **Secrets never enter the settings table.** 1.x stores Keycloak client secrets, SMTP passwords and VAPID keys encrypted in `system_config_details`. The 2.0 architecture is explicit (§C1): settings are validated against a registry of known keys and **secrets are rejected**. Secret-shaped 1.x keys are listed below so they can be *excluded* deliberately and routed to configuration/keystore.
9. **Keycloak owns credentials.** 1.x `user_details` carries `credential_user_id`, `credential_password` (encrypted), `session_store` (encrypted), `maximum_attempts`, login-attempt state. In 2.0 none of these port — authentication is Keycloak's (§B6); the user row is profile + claim mapping.
10. **No default tenant seeding.** 1.x silently inserts a "Pentagon"/"North California" customer+site if none exist, then other seeds assume `site_id = 1`. 2.0's demo seed is a deliberate script; migrations create no tenant.
11. **JSON blobs are a decision, not a default.** 1.x keeps grid definitions, user column prefs, saved filters and device form-schemas as unsized JSON strings, and has migrations that string-rewrite inside them. Where a WP keeps JSON, it records why; user-owned preference data leans relational.

---

## 1 · Cluster: IDENTITY *(Phase 2 · WP1)*

### `user_details` → 2.0 user
Real columns: PK `user_id` + `user_uuid varchar(36)` (**not unique** — plain index only); `keycloak_user_uuid varchar(36)`; `is_ldap_user` (only ever written `false` — dead, don't port); names (`first_name`/`middle_name`/`last_name` varchar(100), `display_name` varchar(200)); `email varchar(500)` **encrypted** + `email_hash` (blind index for search); `profile_image_url`; `language_name varchar(200)`; privacy/terms acceptance timestamps; `user_login_type varchar(50)`; `is_override_user`; `role_id` NOT NULL FK (⇒ **exactly one role per user** is a 1.x invariant); `customer_id` NOT NULL FK. Plus the credential cluster that does **not** port (rule 9).
- 1.x defect: `maximum_attempts` tag malformed (works by accident). `last_successful_login_time` field/column name mismatch.
- **Not seeded — 1.x ships no admin user.** How the first admin exists in 2.0 is an installer/bootstrap question: surface it in the WP1 report, don't invent it.
- Note: `user_status` (6 seeded presence states) and `user_activity` (row per presence transition, unbounded growth) are **runtime's** per §C2 (operator presence) — excluded from core scope; they arrive with the work-item phase.

### `role_details` → role
`role_id` PK, `role_uuid` (no unique), `role_name varchar(255)` (**no unique**), `description varchar(3000)`, `is_override`, `customer_id` FK. No indexes at all. 2.0: unique external id + unique `(customer, role_name)`.

### `role_site_mappings` → role↔site scoping (the real tenant-scoping table)
`role_id` + `site_id`, no FK constraints declared, no unique on the pair, no index — despite being on the auth hot path in 1.x. 2.0: proper FKs, unique pair, scope-leading index.
- **`user_site_mappings` is a DEAD table in 1.x** (zero references anywhere). Do not port. Site scoping is role-based.

### Entitlement catalog: `applications` → `application_modules` → `application_sub_modules` → `application_sub_module_action_items`
Four-level hierarchy; PK/uuid prefixes inconsistent per level in 1.x (`application_module_id` vs `module_uuid` etc. — normalize); action items carry `routes varchar(200)` and a **denormalized** `application_module_id` (derivable — drop).
- **SEED (load-bearing):** GATE app (3 modules / 30 sub-modules / 174 action items) + PWA app (2 / 3 / 13) = **5 / 33 / 187 rows**, all with hardcoded stable UUIDs in 1.x. One commented-out sub-module ("Reporting Dashboards") — dead, excluded. **The complete tree, script-extracted and count-verified, is `docs/entitlement-catalog-from-1x.md` in this repository** — the WP1 seed is written from that file, never from memory.
- **The contract is currently display strings:** the backend licence gate matches on `routes` (values **repeat** across sub-modules — `View`, `ExportExcel`, `DeleteRecord` recur, so licence filtering is coarser than the catalog implies); the Gate frontend gates on module/sub-module/action-item **names**. 2.0: seed with pinned UUIDs + stable unique `code` per node; treat names as display-only. PWA-app rows belong to the portal (deferred) — seed the GATE tree only, keep the PWA tree in this sheet for that later phase.
- Licence-side twin `license_module_entitlement_mappings` (with `resource_limit`, e.g. lane caps keyed on `routes == "AddLane"`) is the **licensing cluster — deferred** with U4; noted so nobody wonders where resource limits went.

### `role_entitlement_mappings` → role↔entitlement grants
FKs to role + all four catalog levels (module/sub-module denormalized — keep only the leaf FK unless the report argues otherwise), plus `event_data_id` — **row-level data scope grafted onto the grant table** (points at reference data; a design smell — surface how 2.0 wants per-data-scope grants, don't silently copy). No unique on `(role, item)` — duplicates possible in 1.x. Hot-path covering index exists in 1.x (`(role_id, is_active)` INCLUDE the three level ids) — 2.0 equivalent index required, scope-leading per `ScopeIndexRule`.
- `override_user_details` (licence break-glass user, encrypted fields, a column literally named `user_id` holding an encrypted login string) → **licensing cluster, deferred**.

---

## 2 · Cluster: TEAMS & TEMPLATES *(Phase 2 · WP2)*

1.x calls teams "groups": `group_details`, members `user_group_mappings`, routing `group_configuration_mappings`.

### `group_details` → team
`group_id` PK (1.x tag missing autoIncrement — accident, port as identity), `group_uuid`, `group_name varchar(255)` (no unique), `group_description varchar(3000)`, FK `customer_id`, nullable FKs `shift_template_id`/`break_template_id`, `group_handling_method_id` NOT NULL. **No `site_id`** — team↔site is only implied transitively. 2.0: decide and state the team's scope column explicitly (site-scoped per the architecture's world model).

### `group_handling_methods` → an enum, not a table
Exactly 2 seeded rows forever: `Push`, `Prompt` (random UUIDs). 2.0: a constrained `handling_method` column on team. (§C1 names Push/Prompt explicitly.)

### `user_group_mappings` → team member
`(group_id, user_id)` — two single-column indexes, **no unique pair** in 1.x. 2.0: unique pair, FKs.

### `group_configuration_mappings` → team routing rules — **DEFERRED, deliberately**
One row = team × screen (`manual_input_id`) × lane, plus derivable `site_id`/`area_id` (drop) and a nullable `priority` the app patches with `COALESCE(priority, -1)` at query time. **Screens are design artifacts (deferred cluster), so routing rules land with the work-items/screens phase**, where `topology.team_routing` becomes real. Recorded here so the deferral is visible.

### `shift_templates`
`shift_name varchar(100)`, `start_time`/`end_time` TIME, `is_overnight`, `duration` (1.x stores a *duration* in a TIME column — fix the type), nullable `site_id`. **1.x defect: two Go fields map to the same `time_zone_id` column.** 2.0: one timezone reference — and see the timezone note below.

### `break_templates` + `break_timings`
Template: name varchar(100), description varchar(500), nullable site. Timing: `break_start_time` and `duration_minutes` — **both nullable in the real 1.x schema only because the GORM tags are malformed** (`type=time`, bare `notnull`); the intent was NOT NULL TIME / NOT NULL int. Port the intent (rule 4).

### `time_zones` → don't port the table
1.x seeds **4 US zones only** and backfills IANA names by migration. 2.0: store a validated IANA zone id (`varchar`, validated against the JVM's tz database) on the site/template rows; no zone table. Surface in the report if anything argues otherwise.

---

## 3 · Cluster: DEVICE REGISTRY *(Phase 2 · WP3)*

2.0's `core.device` exists minimally (V101). This cluster completes it.

### `devices` — the full 1.x column set (V101 has a subset)
Real 1.x columns beyond what 2.0 already has: `device_mode` (**enum `IO`|`DATA_CAPTURE`** — 1.x compares it with `LOWER(?)` in two joins, which only works on case-insensitive collation; fix per rule 5), `device_host varchar(200)`, `device_alias`, `device_type varchar(100)` + `device_type_name` + `topic_device_type` (**three denormalized copies of catalog data — port ONE FK to the type catalog**), `device_model`, `firmware_version varchar(20)`, `description varchar(500)`, `resolution`, `ip_address varchar(45)`, `stream_type`, `port int`, `protocol varchar(10) default 'http'`, `manufacturer`, `device_url`, `device_portal_url`, `assembly_name varchar(200)` + `class_name varchar(255)` (**the .NET plugin loading params — load-bearing for the frozen config poll**), `data_capture_mode varchar(25)`, `wait_time int` (suppression window input), computed `topic_name` (don't port).
- **`encrypted_username`/`encrypted_password` do NOT port in this WP.** Device-credential storage is security-shaped (the frozen config poll must eventually serve credentials): the WP3 report proposes a design; the product owner decides. Until then the columns don't exist — a gap named beats a mechanism invented.
- 1.x uuid unique arrived only via the degradable 2026 retrofit — rule 2 applies.

### `device_io_assignments` — port layout
Per-device rows: `port_type` (**enum `Input|Output|Relay|Audio|Tone`** — TitleCase here, lowercase in `io_devices.input_type`; unify), `io_port int`, `io_port_name varchar(200)` (DI1…DO3…R2…"Front Mic"), `port_alias_name`, flags `is_reverse_state`/`is_initial_high` (**NOT NULL with no default in 1.x** — give defaults), `is_data_capture`, `is_gos_audio`, `produce_true_message`/`produce_false_message` (edge suppression semantics — keep, edge reads these), `wait_time`, `is_output_port` (**derivable from port_type — drop**), plus computed topic columns and `device_code`/`topic_port_name` (don't port). 1.x covering index `(device_id, is_active)` INCLUDE (…) — 2.0 equivalent required.

### Catalogs (all seed-only in 1.x; pin UUIDs + codes per rule 7)
- **`device_io_port_names`** — 40 rows: Input DI1–DI10+CB · Output DO1–DO10 · Relay R1–R4 · Audio (5 named) · Tone T1–T7+CS1–CS3.
- **`io_devices`** — 19 rows (Call Button/`CALL_BUTTON`, Loop A/B/C, Gate Arm/`GATE_ARM`, lamps, Traffic Signal, mics/speakers, Tone Generator…): lowercase `input_type` (unify with `port_type`), and 1.x's only genuinely-unique uuid. Naming drift: `FrontMic` here vs `Front Mic` in port names — unify.
- **`device_types`** — 16 distinct rows (one duplicated in the 1.x seed slice — a copy-paste bug, don't replicate): AXIS/PTZ/Pelco/Milesight cameras, LPR_CAMERA, BARCODE_SCANNER, RFID, GATE_ARM, SCALE, PRINTER, PORTAL_SCAN, `EDGE_DEVICE/DISPLAY` (**contains a `/`** — 1.x put it in topic names; give 2.0 a clean code) etc. Carries `device_configuration` — **a JSON form-schema driving the admin UI per type**. Keep as JSON deliberately (rule 11) or model it; record the choice.
- **`attachment_types`** — 1 seeded row ("Ethernet"), read-only dropdown. Near-dead: fold into a constrained column or drop; surface in report.

### `perspective_details` → PTZ presets
`(device_id, perspective_name)` app-level-unique only; **pan/tilt/zoom stored as varchar(255)** — make them numeric. (PTZ *execution* is a direct camera call — register NEW-5 — but preset *storage* is plain config and lands here.)

### `resource_configurations` → scoped custom variables
1.x: polymorphic `(resource_type ∈ customer|site|area|lane|device, resource_id)` with **no FK, half-useful index (resource_id only), unsized value** — and its two seeded rows are the **login-lockout policy** hardcoded against `site_id = 1` with an idempotency key that ignores the site. 2.0 keeps the feature (§C1's `/resource-configurations/{scope}/{id}`): design it properly (typed scope enum + per-scope unique `(type, id, key)` + scope-leading index) and route login-lockout policy to Keycloak (rule 9), not here.
- `device_states` is **edge's** (`edge.device_state` exists) — excluded from core.

---

## 4 · Cluster: SETTINGS · WORKSPACE · AUDIT *(Phase 2 · WP4)*

### `system_configs` + `system_config_details` → the settings registry
1.x: parent per service name + child key/value rows, `config_value` **encrypted, unsized**, no `is_active` (quartet deviation), **403 seeded keys across 21 services, all empty values**, additive-only idempotency, dotted keys as fake namespaces (`PRINT_TICKET.POLL_INTERVAL_SEC` — don't copy).
2.0 (§C1 is explicit): **a registry of known keys, validated writes, history appended, secrets rejected.** Translation: settings are per-key rows with type/validation metadata; the 1.x per-service grouping mostly dissolves (2.0 has six services, not 21). **Excluded by rule 8** (route to env/keystore, never the DB): all `*_CLIENT_SECRET`, `CLUSTER_PASSWORD`, `SMTP_PASSWORD`, `AZURE_*_KEY`, `VAPID_PRIVATE_KEY`. Genuine settings worth carrying forward as seeded known keys: log levels, HTTP/pool tunables where still meaningful, work-item SLA defaults (`EXPECTED_PROCESSING_TIME_SEC`, `MAX_PROCESSING_TIME_SEC` — runtime reads these later), retention windows.

### `grid_config_details` → grid catalog
38 seeded grids (random UUIDs, idempotency by display name in a column misleadingly called `table_name`, JSON `default_grid_values` with a canonical misspelling `is_dragable`, and a seed that **overwrites on re-run except one special-cased grid**). 2.0: pin identity (code + uuid), name the name column honestly, and decide the JSON question per rule 11. Only seed grids whose features exist — most of the 38 belong to later phases; seed the ones core's own consoles need and record the deferral.

### `user_preferences_grids` + `saved_user_filters` → per-user workspace
1.x: JSON blobs, no unique `(user_id, grid)` / `(user, grid, filter_name)`, no enforcement of one default filter, and a migration that string-rewrites JSON keys (the cost of the blob design, in the wild). 2.0: unique constraints per rule 3; lean relational for prefs per rule 11 (decide and record). PWA twins → portal, deferred.

### `colors` + `languages` → site branding & localization
1.x seeds 8 colors (mixed 6- and 8-digit hex — normalize) and 1 language ("english", empty path) **per site**, uuid width drift (varchar(100)). Small, site-scoped, port cleanly.

### `audit_history` → audit events
1.x: one row per admin mutation (`action_by`, `action_for`, `action` unsized, `action_type varchar(50)`, timestamp), runtime-written, unbounded — so in 2.0 it's **traffic-growing: needs growth + retention class** and a scope-leading index; the checks will insist. §C1 lists Audit History as a feature — a minimal, well-typed audit event table lands here.
- `reporting_dashboard`: near-dead in 1.x (its entitlement sub-module is commented out of the seed) — don't port; noted.

---

## 5 · Encrypted-column registry (1.x) — for the record
1.x keeps the list in one place (`agent-service/.../key_rotation_dto.go`): 26 columns / 16 tables, including `user_details.{email,credential_password,session_store}`, `devices.{encrypted_username,encrypted_password}`, `system_config_details.config_value`, licence keys, site/customer PII — even two **encrypted integers** in alert settings (not a design to copy). In this Phase-2 scope, after rules 8/9 and the WP3 credential deferral, **the only encryption candidate left is user email PII** (with its blind-index search pattern, `email_hash`). Whether 2.0 encrypts it at rest is security-shaped: propose in the WP1 report, product owner decides. Everything else encrypted in 1.x either doesn't port or is deferred.

## 6 · What is deliberately NOT in Phase 2 (and where it went)
- Workflow/screen **design tables** + `manual_inputs` + `display_kiosks` → builder-developer engagement.
- **Team routing rules** → work-items/screens phase (needs screens).
- **Custom entities** (declared model + DDL executor) → its own feature.
- **Licensing cluster** (licence details/keys/modules/mappings, override users, resource limits) → U4/fleet work.
- **PWA/portal identity universe** (pwa_users, drivers, carriers, blind-index hash arrays, `driver_lane_mappings` with its four-level double-keyed denormalization) → portal, cloud scope.
- `user_status`/`user_activity` (operator presence) → runtime, work-items phase.
- `device_states` → already edge's. `notifications` → runtime's notify module. `shared_file_tracker` → sync scope.
