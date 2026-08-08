# ORCA Phase 2 — Report: The World As Configured

**Written by the session that built it · 8 August 2026 · Companion: `docs/phase-2-plan.md`**

Everything the plan asked for is built, one seam extension was needed to build
it honestly, and the sheet's three partial catalog lists are carried as seeded
gaps rather than filled. The reference sheet's translation rules were the
contract; where 1.x was wrong, 2.0 diverges below, named. Four commits, one per
work package, plus this report.

---

## 1 · Headline

**orca-core's configuration world is real and proven**: 28 new tables across
five migrations (V103–V107) plus the completed `device`, 35 contract-first
operations on 13 new generated interfaces, 41 property tests in five new
suites — all landing together per work package, none after. Every uniqueness rule from sheet rules
2–3 is a database constraint watched to refuse a duplicate; the GATE
entitlement tree (1/3/30/174) is seeded byte-stable with pinned UUIDv5
identity; the Phase 1 gate path still runs end to end against the migrated
schema, with the barrier's provisional `'BARRIER'` settled into the catalog's
`GATE_ARM` under an unchanged view contract.

**The one design this phase had to settle that the plan did not dictate**: the
scope seam deliberately has no unscoped read, and most of the configuration
world is installation-wide rather than site-scoped. The answer is a second
scope dimension — a constant `config_realm = 'INSTALLATION'` column on
installation-wide tables — so reading them is a declared act, DENY still
returns nothing, and no bypass exists. §5.1 argues it in full; it is the first
thing a data-model reviewer should read.

## 2 · What was built, package by package

### WP1 · Identity (V103 + V104, commit `f4ed91b`)

- `role`, `user_account`, `role_site`, the four-level entitlement catalog
  (`entitlement_application/module/sub_module/action_item`), `role_entitlement`.
- **Not ported, per the rules**: the credential cluster (rule 9 — the user row
  is profile + claim mapping; `keycloak_subject` is the mapping, filtered-unique
  among active users), `customer_id` everywhere (one installation, one customer
  — register NEW-1a/1b rulings; role names are unique per installation),
  `user_site_mappings` (dead in 1.x; scoping is role-based), the grant table's
  `event_data_id` graft (§5.6), the denormalized module/sub-module FKs on
  grants (leaf FK only), `is_ldap_user`, `user_login_type`, `is_override_user`.
- **The seed is generated, not transcribed**: `deploy/tools/gen-entitlement-seed.py`
  parses `docs/entitlement-catalog-from-1x.md`, asserts the sheet's counts
  (3/30/174) before emitting a row, mints external ids as UUIDv5 in
  `uuid5(NAMESPACE_URL, 'orca:2.0:entitlement-catalog')` keyed by code —
  deterministic, so regeneration is byte-identical — and derives stable codes
  from tree position (`GATE.ADMIN.ROLE_MANAGEMENT.ADD_ROLE`). Names are
  display-only; codes are the contract; `licence_route` is kept verbatim and
  deliberately non-unique (licence filtering by route is coarser than the
  catalog — a 1.x fact carried for the licensing phase, not smoothed).
- Contract: `/api/v1/users` (GET·POST·PATCH), `/api/v1/roles`
  (GET·POST·PATCH — grants and site scope are declarative sets on the role),
  `/api/v1/entitlements` (the tree). `/me/permissions` waits for a console,
  as the plan allows; the grant model it will read is in place.

### WP2 · Teams & templates (V105, commit `b16fa93`)

- `team` (explicit `site_external_id` — 1.x had none), `team_member` (the
  unique pair 1.x checked racily), `shift_template`, `break_template`,
  `break_timing`.
- `group_handling_methods` (2 seeded rows forever) became a CHECKed column;
  `time_zones` (4 US rows) became a validated IANA string; `break_timing`
  ports the 1.x *intent* (NOT NULL, positive minutes), not the malformed-tag
  accident (rule 4).
- **The duration defect is closed from both ends**: duration is computed and
  never stored (1.x stored it in a TIME column), and `is_overnight` is
  CHECK-tied to the times so the flag can never contradict the clock; `end <=
  start` crosses midnight, equal times are the 24-hour shift. Property tests
  cover the day shift, the night shift, the 24-hour shift, and the
  patch-one-end recomputation.
- Team routing rules stay **deferred** with screens, as the sheet directs.

### WP3 · Device registry (V106, commit `b5ff04a`)

- `core.device` extended to the sheet's translated set; **one FK** to the
  seeded `device_type` catalog replaces 1.x's three denormalized type copies
  and settles V101's provisional vocabulary (`'BARRIER'` → `GATE_ARM`, mapped
  in the migration's backfill; a legacy value with no catalog home fails the
  migration on purpose — a question for a person, not a guess).
- `device_io_assignment` (unified UPPERCASE port-type enum, catalog FK instead
  of the copied name string, defaults for the two undefaulted flags, the
  edge-suppression flags kept), `ptz_preset` (numeric pan/tilt/zoom),
  `resource_configuration` (typed scope, per-scope unique, sized value,
  scope-leading index; the 1.x login-lockout seed rows do not port — Keycloak
  owns lockout, rule 9).
- **No credential columns exist** — §6.A is the proposed design.
- `topology_device` re-published with **identical columns**; only
  `device_type`'s source moved to the catalog code. Runtime and edge read
  exactly what they read before (verified: nothing in either service's Java
  branches on the value; the demo proves the path).
- **The catalog gaps, seeded as gaps** (§5.4): 12 of 16 device types, 36 of
  40 port names, `io_device_kind` created to shape but unseeded.

### WP4 · Settings, workspace, audit (V107, commit `ba27c0c`)

- Settings exactly as §C1 words them: a seeded registry of known keys
  (`LOG_LEVEL`, the two work-item SLA defaults, four PROVISIONAL retention
  windows), typed validated writes, history appended with attribution, and
  **secrets rejected before the registry is consulted** — the suffix family
  (`_PASSWORD`, `_SECRET`, `_PRIVATE_KEY`, `_KEY`, `_TOKEN`, `_CREDENTIAL`)
  generalises the sheet's exclusion list so the next secret-shaped key needs
  no list edit.
- Rule 11 decided both ways, deliberately: grid column preferences are
  **relational** (1.x's blobs earned a migration that string-rewrites JSON);
  saved filters stay **JSON with a schema version** (an expression tree is a
  document; the version column is what spares the blind rewrite). Unique
  (user, grid, name); at most one default by filtered index.
- `site_color` (normalized `#RRGGBBAA`, binary-collated CHECK) and
  `site_language`, created **empty** — 1.x seeded 8 colors and "english" per
  site, which is exactly the tenant seeding rule 10 forbids.
- `audit_event` — **core's first traffic-growing table**, answered the way
  V101's own Javadoc promised the check would ask: `TRAFFIC_GROWING` +
  retention class `audit` (PROVISIONAL) + scope-leading index. Typed columns;
  newest-first reads bounded by a seam extension (§5.2). Audit writes cover
  the settings and branding mutations this WP owns; §5.5 records the
  instrumentation follow-up for WP1–WP3 paths.

## 3 · The platform changes this phase needed

Two, both small, both with property tests, neither silent:

1. **`ScopedSelect.orderByDescending`** (+ `JdbcScopeSeam` appending a fixed
   `DESC` keyword). Without a direction, "the latest N of a growing table" is
   an unbounded read wearing a bounded one's clothes. Same lineage as H5's
   `increment`; proven in `ScopeSeamPropertiesIT` (the scope predicate still
   applies to a descending read; TOP+DESC returns the latest row).
2. **`PlatformDatabase.migratedSchema` now drops views before tables.** Phase
   2's core suites are the first to clean-migrate a schema holding published
   views twice in one build; a surviving `topology_lane` made Flyway refuse
   the second run with "non-empty schema but no history table".

## 4 · Verification — the plan's table, with real results

| # | Item | Result |
|---|---|---|
| 1 | `git clean -xdf -e deploy/.env -e .idea && ./gradlew build` | ✅ Green. Run with the phase-1 exclusions (§6 of that report — a bare `-xdf` destroys `deploy/.env`). Gradle restored unchanged task outputs from its build cache; item 2's `--rerun-tasks` is the forced pass |
| 2 | `./gradlew check integrationTest --rerun-tasks` | ✅ `BUILD SUCCESSFUL in 4m 49s` — every suite forced, real SQL Server via Testcontainers, real Flowable: **180 integration tests in 23 suites** (138 before this phase; the five new suites add 42, including the seam's descending-order property), plus the unit tests and the ten build checks |
| 3 | The Phase 1 demo end to end against the migrated schema | ✅ Run live, on the +10000 offset against the compose stack. Core's startup applied V103–V107 onto the EXISTING phase-1 demo database (`Successfully applied 5 migrations … now at version v107`) — which exercised the V106 backfill on real rows: the demo barrier's provisional `'BARRIER'` became `GATE_ARM` in place. Then one truck: plate `T-PHASE2-01` ACKed → visit `vis-fac59e52…` **COMPLETED** → `RAISE_GATE` **EXECUTED** at the device-host stub → `visit.completed` in `runtime.outbox`. `core.topology_device` serves `LPR_CAMERA` and `GATE_ARM` through the unchanged column contract |
| 4 | Catalog seeds byte-stable across two clean migrations | ✅ `CatalogSeedPropertiesIT.theSeedIsByteStableAcrossCleanMigrations` migrates two clean databases and compares full row sets **including UUIDs**: 174 entitlement leaves, 12 device types, 36 port names — identical |
| 5 | Deliberate duplicate inserts prove each unique constraint | ✅ Executed in the suites, external id + natural key per table: `IdentityPropertiesIT` (external ids, active role name, grant pair, scoping pair, keycloak subject), `TeamsTemplatesPropertiesIT` (site+team name, member pair, timing pair), `DeviceRegistryPropertiesIT` (port natural key, preset name, resource key), `SettingsWorkspaceAuditPropertiesIT` (filter name, one-default, color code) |
| 6 | Break growth declaration · wrong-leading scope index · off-contract controller — each fails the build | ✅ All three, watched to fail and reverted: **(a)** `@RetentionClass` stripped from `audit_event` → `RetentionClassRule` failed (*"declares table 'audit_event' as TRAFFIC_GROWING but names no @RetentionClass"*); **(b)** `ix_audit_event_scope` re-led with `occurred_at` → `ScopeIndexRule` failed (*"can only be SCANNED — and a scan under UPDLOCK locks every row at the site"*); **(c)** a `RogueController` with an unauthored route → `ContractInterfaceRule` **and** `ErrorEnvelopeRule` both failed |
| 7 | `verify-isolation.sh` still green | ✅ `PASS — 36 checks. Each login owns its own schema and reaches no other.` Run after V103–V107 were live in the compose database, so the 28 new tables sat behind the confinement while it was proven |
| 8 | `SMTP_PASSWORD` refused with the typed error | ✅ `SettingsWorkspaceAuditPropertiesIT.aSecretShapedKeyIsRefused`: `SETTING_SECRET_REJECTED`, plus the whole excluded family (`*_CLIENT_SECRET`, `VAPID_PRIVATE_KEY`, `AZURE_*_KEY`, `CLUSTER_PASSWORD`, `*_TOKEN`), and zero rows ever reach the table |
| 9 | All six services boot | ✅ All six booted against the compose stack with the `local` profile (offset ports 18081–18086), all answering `/actuator/health` 200 — the phase-1-hardening lesson (suites can pass while a service cannot start) checked deliberately, with core's new `CoreConfiguration` bean graph and the three gate-path services exercised by the live demo above |

## 5 · Decisions the plan did not dictate

In rough order of consequence.

### 5.1 · The second scope dimension: `config_realm`

The seam has no unscoped read — deliberately, and Phase 0 wrote that down.
But users, roles, the entitlement catalog, templates, settings and the grid
catalog belong to the *installation*, not to a site; stamping them with a
site would be false data, and adding an unscoped read to the seam would be
the bypass the seam exists to remove. The design: installation-wide tables
carry `config_realm VARCHAR(16) NOT NULL DEFAULT 'INSTALLATION'` (CHECKed to
that one value), and reads declare the `config_realm` dimension exactly as
site reads declare `site_external_id`. `Scope` was already dimension-opaque —
this is the second dimension the type was built to permit, not a new
mechanism. What it preserves: DENY returns nothing on every path, a scope
naming only a site cannot read installation configuration (proven in
`IdentityPropertiesIT`), and writes must name the realm. What it costs: a
constant column per table, and one more concept. The alternative of keying
everything on the primary site failed on a hard fact: catalog *seeds* run in
migrations before any site exists.

### 5.2 · Core scopes from configuration, one site, like the fielded services

`orca.installation.site-external-id` arrives in core's configuration
(default `SITE-DEMO`, as edge and runtime already default). The request
boundary — the controller — establishes scope from it; nothing is taken from
the request (phase-1 decision 15's reasoning applied to the public surface).
Consequence: on the on-site profile `role_site` is nearly degenerate (one
permitted site), and that is fine — the plan asked for the grant *model* to
be right now, and multi-site scope widens in exactly one place
(`CoreScopes.installation`) when the hosted tier arrives.

### 5.3 · Scope columns are FK-backed inside core — except on the audit trail

`role_site`, `team`, `team_member`, `device`, `device_io_assignment`,
`ptz_preset`, `resource_configuration`, `site_color`, `site_language`
reference `site (external_id)` (unique since V101), so the denormalized scope
value cannot name a site that does not exist. `audit_event` deliberately does
not: its scope value is the *configured* installation site, and an audit
write must not fail because configuration rows are mid-bootstrap.

### 5.4 · The sheet's partial catalogs are seeded as gaps

The entitlement catalog had a dedicated, script-extracted, count-verified
file; the device catalogs have only the sheet's elliptical prose. So:
`device_type` seeds the 12 rows the sheet names (4 hide behind an "etc.";
the four camera entries carry provisional display names — display-only by
rule 7, so cheap to correct); `device_io_port_name` seeds the 36 fully
determined rows (four of the five audio names are unextracted — "Front Mic"
is known only via the sheet's naming-drift note); `io_device_kind` is created
to the target shape and seeded **empty** — the sheet has its count (19) and
example names but not the (name, input_type) pairs. **Recommendation: give
the device catalogs the same treatment the entitlement catalog got — a
script-extracted file — and seed the remainder in a later migration.**
`device_io_assignment.port_name_id` is nullable for exactly this reason.

### 5.5 · Audit coverage is the WP4 surfaces, this phase

`AuditTrail` lands with the audit table and is wired into the settings and
branding mutations built beside it; the WP1–WP3 mutation paths (users, roles,
teams, templates, devices, resource configuration) are **not yet
instrumented**. The plan's done-when asks that "the audit trail records a
config mutation end to end", which it does; retrofitting every earlier
mutation path is recorded here as deliberate follow-up rather than assumed
done. The actor resolution is settled once, in `AuditTrail`: the calling
user's external id when the token maps to one, the raw subject when it does
not, the system identity on user-less paths. The audit detail never carries
a setting's value — a setting can be sensitive without being a secret.

### 5.6 · Ported-shape decisions, per table

1. **Grants replace declaratively, by retire-and-reinsert.** Every
   set-valued surface (grants, role sites, members, timings, IO layouts,
   presets, preferences, colors, languages) retires the active rows and
   inserts the new set, per §D3; the filtered unique indexes are what admit a
   fresh grant after a revoked one. Cost: retired rows accumulate with admin
   activity (bounded by it, and they *are* the change history).
2. **`role_entitlement.event_data_id` did not port.** Row-level data scope
   grafted onto a grant table is a design smell the sheet flags; how 2.0
   wants per-data-scope grants is a real design question for the work-items
   phase — surfaced, not settled here.
3. **External ids are server-minted** (`usr-`/`rol-`/`team-`/`shf-`/`brk-`/
   `dev-`/`flt-` + UUID), never client-supplied, never reused (§B8).
4. **`user_account.email` is plain `NVARCHAR(320)`, non-unique.** 1.x's 500
   was ciphertext width; uniqueness lives in Keycloak, which owns the login
   identity; encryption at rest is §6.B. `language_name` became
   `language_code`.
5. **Retirement guards**: a role held by active users, and a template
   referenced by active teams, refuse retirement (`ROLE_IN_USE`,
   `TEMPLATE_IN_USE`) — not in any specification, but retiring a row other
   active rows depend on is a dangling reference by construction.
6. **Templates are installation-realm; 1.x's nullable `site_id` did not
   port.** A nullable scope column is invisible to every scoped seam read —
   a trap, not a feature. Site-specific template *usage* arrives through the
   site-scoped team that references it.
7. **`device_host` (per-device) did not port.** The fielded 2.0 design puts
   the device host per **lane** (`lane.device_host_url`, the frozen §D2
   contract's shape); a second copy per device is two sources for one address.
   Sheet-vs-architecture conflict → architecture wins, recorded per plan §5.
8. **`is_output_port`, every `topic_*` column, `device_type_name`,
   `attachment_types`** did not port (derivable, computed, denormalized, or
   near-dead — each flagged by the sheet itself).
9. **Suppression flags default to produce.** 1.x left `produce_*` NOT NULL
   with no default; defaulting to 1 means silence is configured, never
   accidental — the conservative direction for a gate.
10. **`resource_configuration` validates AREA/LANE through `topology_lane`**
    (core reading its own published view — the one table set with the scope
    column). Honest limitation: an area with no lanes is invisible to the
    view and cannot carry variables until areas grow their own scope column.
11. **Enum casing is UPPERCASE everywhere, and the CHECKs carry
    `COLLATE Latin1_General_100_BIN2`** — see §7.1 for why the collation is
    load-bearing.
12. **Surface additions beyond §C1's interface table**, each deliberate:
    PATCH on the two template catalogs (the done-when says CRUD; §C1 lists
    GET·POST), GET on the three device catalogs, GET on
    `/resource-configurations/{scope}/{id}` (a PUT-only surface cannot
    round-trip), and GET `/api/v1/audit-events` (a write-only audit table is
    readable only with a database login — the operator blindness H3 removed
    elsewhere). PATCH `/sites/{id}` exists only as the branding/localisation
    slice; site CRUD is otherwise absent, said rather than implied.
13. **`CallerIdentity` is an interface**, production-implemented from the
    JWT, lambda-implemented in suites — the same seam-for-testability shape
    as edge's ports.
14. **Empty string clears a template reference** in team PATCH; absent means
    unchanged. Recorded because tri-state-over-JSON has no pretty answer.
15. **Settings write path**: unknown key and secret-shaped key are different
    422s (`SETTING_UNKNOWN` vs `SETTING_SECRET_REJECTED`), because "add it to
    the registry" is the correct response to one and must never become the
    response to the other.

## 6 · The three PROPOSED security-shaped designs — for the product owner, none implemented

### 6.A · Device-credential storage (WP3)

The frozen device-host contract (§D2) eventually requires the configuration
poll to serve device credentials in the clear on the site-internal network.
The columns deliberately do not exist yet. **Proposed**: a separate
`device_credential` table (1:1 with device, never selected by any published
view, never serialized by any API model) holding `username` plus a
`secret_ref` into an installation-local keystore (file-based, e.g. PKCS#12 or
OS keystore, provisioned by the installer beside the shared credential);
the poll resolves refs at serve time; every serve is logged (the §D2
mitigation). Rejected alternatives: encrypted columns with an in-database key
registry (1.x's shape — the key ends up beside the data), and a networked
secret store (a new runtime dependency on the gate path, against §A1).
**Decision needed**: whether the keystore indirection is warranted on a
single-tenant appliance, or encrypted-at-rest columns with a
config-provisioned key are acceptable.

### 6.B · Email at rest (WP1)

After rules 8/9 and 6.A's deferral, user email is the **only** encryption
candidate left in Phase 2's scope (sheet §5). Today it is a plain column.
**Proposed**: if the product requires PII encryption at the column level,
AES-GCM with a key from the installation keystore plus the 1.x blind-index
pattern (`email_hash`) for lookup — as an expand-only migration; until then,
rely on the appliance's disk encryption posture, which is an installation
requirement rather than a schema feature. **Decision needed**: the
requirement itself — column-level encryption on a single-tenant appliance is
cost without a stated threat model, and stating the threat model is the
product owner's call.

### 6.C · First-admin bootstrap (WP1)

1.x ships no admin user; nothing in this schema creates one (no default
admin — plan rule). **Proposed**: an installer-side deliberate act, in the
`deploy/bootstrap` lineage — a script run once at installation that creates
the first admin's Keycloak account (or binds an existing directory account)
and inserts the matching `user_account` row with the first role; runnable
only with database and realm-admin credentials no service holds. Rejected
alternatives: first-authenticated-user-becomes-admin (a race and a gift to
whoever authenticates first), a migration-seeded default admin (a default
credential at every site), a break-glass local account (credentials outside
Keycloak, against rule 9). **Decision needed**: whether first-admin creation
is installer tooling (as proposed) or console-guided setup, and what the
directory-integration story is (register U3 owns the realm design).

## 7 · Found wrong, or self-contradictory — reported, not silently corrected

1. **⚠️ CHECK constraints inherit the database's case-insensitive collation —
   the sheet's rule-5 trap reaches further than the sheet says.** A bare
   `CHECK (handling_method IN ('PUSH','PROMPT'))` **accepts `'push'`** on this
   database, which would have quietly recreated the 1.x two-casings accident
   one layer down. Found by the property test (written to watch the casing,
   as it happens, before the constraint could hold it); every enum CHECK in
   V105–V107 now carries `COLLATE Latin1_General_100_BIN2`. Anyone adding a
   CHECKed enum to any schema should copy that shape.
2. **The 1.x catalog genuinely contains two sub-modules named "Event Data"
   under GATE·Admin** (uuids `27c4…` and `de3d…`, near-identical action
   sets). Disambiguated deterministically (`EVENT_DATA`, `EVENT_DATA_2`) and
   asserted in the seed suite; whether one is 1.x dead weight is a question
   for whoever owns the 1.x estate (register NEW-2 — nobody, currently).
3. **§C1's `/users` row says "site mappings" while the sheet declares
   `user_site_mappings` dead and the plan builds role↔site scoping.** The
   plan governs; read §C1's phrase as "site visibility via the role", but the
   prose should be corrected when §C1 is next amended.
4. **§C1's `PATCH /devices/{id}` description includes "credentials" among
   updatable device fields** — this phase's contract deliberately has no such
   field (§6.A). Same amendment list.
5. **The WP3 catalog extraction is not extraction-grade** (§5.4) — counts
   and examples, not rows. Not a defect in the sheet's honesty (it says what
   it is), but the WP1 treatment (a dedicated generated file) is what a
   zero-guess seed actually requires, and WP3 shows the difference.
6. **`PlatformDatabase` could not re-migrate a schema with views** (§3.2) —
   latent since V102 shipped views, surfaced by the first suite set to
   migrate `core` twice in one JVM.
7. **`ImportedSetGuard`'s floors were generous enough to absorb this phase**
   (40+ classes, 15+ migrations, 6 contracts) — nothing needed raising, which
   also means the floors are now well below reality (core alone added ~45
   classes). Worth ratcheting when the checks are next touched, so the guard
   keeps guarding.

## 8 · What a data-model reviewer should look at first

1. **`V103` and §5.1** — the `config_realm` dimension. It is the one
   structural idea in this phase with no precedent in the corpus; if it is
   wrong, it is wrong in eight tables.
2. **`V106`'s backfill block** — the `'BARRIER'→GATE_ARM` mapping and the
   deliberate failure mode for unmapped legacy values.
3. **Retire-and-reinsert set replacement** (§5.6.1) — the churn is by design;
   a reviewer preferring in-place diffs should argue with §D3 first.
4. **`AuditTrail.currentActor()`** — the attribution ladder (user → raw
   subject → system identity) and the unattributed fallback that only a
   context-less test can reach.
5. **The seam extension** (`orderByDescending`) — small, but it touches the
   most load-bearing invariant in the repository; its property test is in
   `ScopeSeamPropertiesIT`.

---

*A gap reported is worth more than a gap filled with a guess — this phase
ships four of them on purpose: four device types, four audio port names,
nineteen IO device kinds, and thirty grid definitions.*
