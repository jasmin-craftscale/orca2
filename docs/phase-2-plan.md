# ORCA Phase 2 — Implementation Plan: The World As Configured

**For the agent building Phase 2 · August 2026 · Companion report: `docs/phase-2-report.md`**

This plan is self-contained. Where it points at another document, read that document
before building the thing it describes.

## Read first, in this order

1. **This plan, in full.**
2. **`docs/core-config-schema-from-1x.md`** — the DERIVED-FROM-1X reference for every
   table in this phase: 1.x's real columns, seeds, defects, and the **translation
   rules (§0) that override 1.x shapes everywhere**. Its companion
   **`docs/entitlement-catalog-from-1x.md`** carries the complete entitlement tree
   (script-extracted, count-verified: GATE 3/30/174) — WP1's seed is written from
   that file, never from memory. Both are committed beside this plan.
3. **`docs/phase-1-report.md`** + **`docs/phase-1-hardening-report.md`** — what you are
   building on, and the ten build checks that will police every table you add.
4. **`docs/ORCA_ARCHITECTURE.md`** §C1 (orca-core — the service you are extending),
   §B5, §B8, §D3.
5. **`docs/ORCA_OPEN_QUESTIONS_REGISTER.md`** — what is deliberately unsettled.

---

## 1 · What this phase delivers

**orca-core's configuration world becomes real**: identity (users, roles, the
entitlement catalog and grants), teams and templates, the completed device registry,
and settings/workspace/audit — as migrations + seam repositories + contract-first
endpoints + property tests, translated from 1.x deliberately rather than copied.

**Not a port.** The reference sheet's §0 translation rules are the contract: unique
external ids everywhere, natural-key constraints, single-casing enums, no denormalized
copies, no credentials outside Keycloak, no secrets in settings, pinned seed identity.
Where 1.x is wrong, 2.0 diverges and the report records it; where the sheet marks
something deferred, it stays deferred.

**Scope guard:** on-site profile only; `orca-portal`, `orca-sync`, `orca-fleet` stay
untouched skeletons. The sheet's §6 lists everything deliberately absent from this
phase and where it went — do not helpfully pull any of it forward.

---

## 2 · Ground rules (unchanged lineage, phase-specific teeth)

| Rule | Why |
|---|---|
| **Never invent a resolution.** Unspecified → the register arbitrates; still unspecified → report the gap | The programme's most defended rule |
| **The sheet's translation rules override 1.x shapes** — and the conventions in core's existing `V101__world_model.sql` override both where they conflict | One convention source, already fielded |
| **Every table lands with its feature surface in the same WP**: migration + seam repository + endpoints + property tests together | A table nothing reads is drift waiting to happen |
| **Every migration passes the ten checks the day it lands** — growth declared, retention class named where traffic-growing, scope-leading index (`ScopeIndexRule` reads your SQL), envelope, contract-first | The checks are the reviewer that never tires |
| **Seeds are migrations only for product-owned reference data** (the entitlement catalog, port-name/device-type catalogs, known settings keys) — pinned UUIDs + stable codes per sheet rule 7. **No tenant data, no demo data, no default admin** | Rule 10; the demo seed stays a deliberate script |
| **Security-shaped items are surfaced, not settled**: device-credential storage (WP3), email-at-rest encryption (WP1), first-admin bootstrap (WP1) — each gets a PROPOSED section in the report, none gets invented code | The product owner decides these |
| Commit once per work package; report per the established shape; state why you stop if you stop | Same ritual, fifth time |

## 3 · Work packages, in order

### WP1 — Identity
`user` (profile + claim mapping only — sheet rule 9: no credentials, no login state),
`role`, role↔site scoping, the four-level entitlement catalog + its **GATE-tree seed**
(the full tree is `docs/entitlement-catalog-from-1x.md`; re-mint pinned UUIDs, add a
stable unique `code` per node; display names display-only), and
role↔entitlement grants (leaf-FK design; the 1.x `event_data_id` graft is surfaced in
the report, not copied). Contract-first CRUD for users/roles/entitlements per §C1's
interface table; `/me/permissions`-shaped resolution can wait for a console, but the
grant model it reads must be right now.
**Done when** the catalog seed is byte-stable across two clean migrations, every
uniqueness rule from sheet rules 2–3 is a real constraint proven by a property test,
and a role's resolved entitlements round-trip through the API.

### WP2 — Teams & templates
`team` (explicit site scope, `handling_method` as a constrained column — Push/Prompt),
team members (unique pair), shift templates (one timezone reference, IANA string
validated against the JVM tz database — no timezone table), break templates + timings
(the sheet's malformed-tag intent, not the 1.x accident). **Routing rules stay
deferred** (sheet §2) — say so in the report rather than building a dangling screen
reference.
**Done when** template CRUD works end to end and the overnight/duration semantics have
property tests (1.x stored a duration in a TIME column; yours must not).

### WP3 — Device registry completion
Extend `core.device` to the sheet's full translated column set (one FK to the type
catalog — the three 1.x denormalized copies die here); `device_io_assignment` with the
unified port-type enum and edge-suppression flags; the three catalogs seeded with
pinned identity (port names, io-device kinds, device types — one clean code for the
`/`-bearing 1.x name); PTZ presets with numeric pan/tilt/zoom; `resource_configuration`
designed properly (typed scope, per-scope unique, scope-leading index).
**⚠️ No credential columns** — the sheet's WP3 note: propose the storage design in the
report; the frozen config poll that will need it is a later phase's feature.
**Done when** a device with a full port layout round-trips through the API, the
catalogs seed stably, and `topology.device` still serves what runtime/edge already read
(the Phase 1 gate path must stay green — it is your regression test).

### WP4 — Settings, workspace, audit
The settings registry per §C1's own words — **known keys, validated writes, history
appended, secrets rejected** (the sheet lists exactly which 1.x keys are secrets and
must be refused); the grid catalog + per-user grid preferences + saved filters (unique
constraints per sheet rule 3; the JSON-vs-relational choice made and recorded); site
colors + languages (normalized); a minimal typed `audit_event` (traffic-growing —
growth + retention class + scope-leading index, or the checks will refuse it).
**Done when** a secret-shaped key is refused with a typed error, settings history
appends, and the audit trail records a config mutation end to end.

## 4 · Verification — run these, record real results

| # | Item |
|---|---|
| 1 | `git clean -xdf -e deploy/.env -e .idea && ./gradlew build` — green, every prior test unchanged |
| 2 | `./gradlew check integrationTest --rerun-tasks` — all suites, real SQL Server |
| 3 | **The Phase 1 demo still runs end to end** (`docs/phase-1-demo.md`) against the migrated schema — the gate path is this phase's regression canary |
| 4 | Catalog seeds: two clean-database migration runs produce identical rows including UUIDs |
| 5 | Every new table: deliberate duplicate-insert attempts prove each unique constraint (external id + natural key) |
| 6 | Break each of: a new table with no growth declaration · a scoped table whose index leads wrong · a controller off-contract — each fails the build, then revert |
| 7 | `verify-isolation.sh` — still green; new tables changed nothing about confinement |
| 8 | A secret-shaped settings key (`SMTP_PASSWORD`) is refused with the typed error |
| 9 | All six services still boot (the Phase-1-hardening lesson: suites can pass while a service cannot start) |

## 5 · When you are blocked

| Situation | Do |
|---|---|
| The sheet and 1.x disagree with the architecture | Architecture wins; record the conflict |
| A 1.x column's purpose is unrecoverable | Leave it out, name it in the report — a gap beats a guess |
| A design choice is security-shaped | PROPOSED section in the report; do not implement |
| Ports 8081–8086 / 1433 / 8080 taken | The `.env` overrides, as every phase before |
| The local PostgreSQL on 5455 | Never ORCA's, not even for tests (ADR-003) |

## 6 · The report — `docs/phase-2-report.md`

The established shape: built per WP · the §4 table with real results · every decision
this plan did not dictate · the three PROPOSED security-shaped designs · everything
found wrong in the sheet, the architecture, or 1.x's shapes — reported, never silently
corrected · what a data-model reviewer should look at first.

---

*A gap reported is worth more than a gap filled with a guess. Start with WP1.*
