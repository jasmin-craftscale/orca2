# ORCA Phase 3 — Implementation Plan: Work Items & the Clerk Workflow

**For the agent building Phase 3 · August 2026 · Companion report: `docs/phase-3-report.md`**

Self-contained. Where it points at another document, read that document before
building the thing it describes.

## Read first, in this order

1. **This plan, in full.**
2. **`docs/work-items-schema-from-1x.md`** — the DERIVED-FROM-1X reference. **Read its
   §0 first: the three behaviour inversions.** This phase is where 2.0 most
   deliberately diverges from 1.x — the data is 1.x's, the behaviour is the
   architecture's.
3. **`docs/ORCA_ARCHITECTURE.md`** — §B9 ("an exception becomes a work item" — the
   sequence you are building), §C2 (orca-runtime, the work-item lifecycle diagram and
   the modules), §A2/§A3 (the SLA-as-engine-timer design).
4. **`docs/phase-1-report.md` §7.1 and `docs/BPMN_EXECUTION_PROFILE.md`** — the
   service-task boundary-timer finding, so you understand precisely why §4 below is a
   *wait-state* timer and is buildable (and where the builder-developer's open
   question stops).
5. **`docs/phase-2-report.md`** — the config world you build on; the routing rules you
   complete here were deferred from its WP2.
6. **`docs/core-config-schema-from-1x.md` §0** — the translation rules, which all still apply.
7. **`docs/ORCA_OPEN_QUESTIONS_REGISTER.md`** — what is deliberately unsettled.

---

## 1 · What this phase delivers

**The gate's `MANUAL` branch becomes real work.** A process step that automation
cannot finish creates a work item **in the same transaction**; the item is routed to
eligible teams; an operator claims it with a guarded update; completing it **advances
the process in one transaction**; and a time target on the step is **a real engine
timer** that raises a breach when it passes. Plus operator presence, and the routing
rules and screen *identity* the work item needs (the screen *renderer* is the
frontend, deferred).

**The three inversions (sheet §0) are the acceptance criteria**, not stylistic notes:
completion advances the process atomically; SLA is an engine timer, not a UI sum;
out-of-order submits are refused. Where 1.x's status is a dead stub (§2 of the sheet),
2.0 does not inherit it.

**Scope guard:** runtime's `workitem` module + core's routing rules/screen identity +
presence. Not the screen renderer, not the notify hub, not escalation *policy*, not
portal/sync/fleet. The sheet's §7 lists what stays out and where it went.

---

## 2 · Ground rules (unchanged lineage)

| Rule | Why |
|---|---|
| **Never invent a resolution.** Unspecified → the register; still unspecified → report the gap | The programme's most defended rule |
| **1.x is the reference for DATA; the architecture governs BEHAVIOUR.** Where the sheet's §0 says 2.0 inverts 1.x, build the architecture's behaviour and prove it — do not port the 1.x flow | This phase's whole point |
| **Every table lands with its feature surface in the same WP**: migration + seam repository + endpoints + property tests | A table nothing reads is drift |
| **Every migration passes the (now ten) checks the day it lands** — growth, retention class, scope-leading index, envelope, contract-first, and the enum-CHECK `COLLATE …BIN2` shape Phase 2 established | The reviewer that never tires |
| **Tests prove properties, not paths** — and the load-bearing ones here are concurrency and atomicity (two operators racing to claim; a fault between create-item and advance-process rolls back both; the SLA timer actually fires) | "The item is created" is not a test |
| Seeds only for product-owned reference data, pinned; commit once per WP; report per the established shape; state why you stop if you stop | Same ritual, sixth time |

## 3 · Work packages, in order

### WP0 — Device catalog completion (core) · warm-up, closes owed hygiene
Seed the catalog rows Phase 2 left partial, from `docs/device-catalog-completion-from-1x.md`
(exact rows): `io_device_kind`'s 19 rows (was empty), `device_io_port_name`'s 5 Audio
rows (36→40), `device_type`'s remaining display names — pinned UUIDs + codes, casing
unified per rule 5. A small core migration + seed; no behaviour.
**Done when** the catalogs seed byte-stable across two clean migrations and the counts
are complete (19 / 40 / 16).

### WP1 — The work-item lifecycle (runtime) · the core of the phase
`runtime.work_item` + `runtime.work_item_audit` (sheet §1, §3; traffic-growing, so
growth + retention class + scope-leading index). The lifecycle is §C2's, and the
inversions are the point:
- **Creation in the engine transaction.** A process reaching a manual-input step
  creates the work item in the *same* transaction that advances the engine to the
  wait — not a separate HTTP call. Model a manual-input **wait state** in a BPMN
  process (extend `gate-visit` or a dedicated fixture) so the engine genuinely parks.
- **The guarded claim** (`take`) — a conditional UPDATE, `RowsAffected=0` ⇒ a typed
  conflict; the loser is told, never silently no-op'd. `takeover` (supervisor),
  `park` (state the requeue-vs-parked choice), `assign` (pre-assign, stays QUEUED).
- **Completion advances the process, atomically.** `complete` completes the item AND
  resumes the parked execution in **one transaction**; `completion_duration_sec` is
  server-computed, never trusted from the request. **An out-of-order submit — for a
  step the engine is not waiting on — is refused** (inversion 3).
- Only the four writer-backed statuses (QUEUED, IN_PROGRESS, COMPLETED, FAILED via
  lane reset). Do not add the dead escalation statuses here.

**Property tests (the acceptance):** two operators claim the same item concurrently →
exactly one wins, 1,000×; a fault injected between create-item and advance-engine rolls
back **both** (no state where the item exists but the process did not park, or vice
versa); complete-and-advance is atomic under a fault; an out-of-order submit is refused.
**Done when** those hold and a work item flows QUEUED→IN_PROGRESS→COMPLETED with the
process advancing on completion.

### WP2 — Routing & the screen identity (core + runtime)
The routing rules deferred from Phase 2 WP2, built now because work items need them:
- **Core:** the screen *identity* (`manual_input` equivalent — external id, name, the
  three SLA thresholds, a node reference; **identity only, not the renderer**) and the
  team routing rules (team × screen × lane × priority; unique on the tuple — 1.x had
  none; scope-leading index). Published as the view runtime reads (`topology.screen`,
  `topology.team_routing` per §C1).
- **Runtime:** eligible-team evaluation at work-item creation (join the routing rules
  to the item's screen + lane → eligible teams + handling method; **priority not used
  at creation**); priority ordering at grid read (`COALESCE(priority,-1)`, set-before-
  unset, then FIFO). Push vs Prompt: Push → an idle eligible operator; Prompt →
  eligible teams broadcast. Make the two orderings consistent (1.x's were not).

**Done when** a work item routes to the right teams by its screen+lane, the guarded
claim respects eligibility, and priority ordering is a property test.

### WP3 — SLA as a real engine timer (runtime) · the inversion that matters most
The time target on the manual-input step is a **boundary timer on the wait-state node**
(sheet §4 — this is why it fires where the Phase 1 service-task timer did not). On
breach: an engine event, recorded on the item, and surfaced (a query/endpoint —
**not** the notify hub, which is later). Thresholds from the screen identity (WP2),
falling back to the two global settings; there is no global `below_expected`.
- **Ship the narrow version** (§A2, register #5): breach *detection + recording +
  visibility*. A configurable escalation *policy* is a separate feature — do not imply
  it. Whether a breach uses one of the dead `ESCALATE_*` statuses or a new field is a
  design choice to state.

**Property tests:** a work item left past its threshold **raises a breach — the timer
fires** (this is the claim; prove it, because a timer that cannot fire is worse than
none); the timer is engine state, so it **survives a restart** (park the item, restart,
assert the breach still fires once — the §B10 "a timer survives a restart" property,
now for SLA). **Done when** those hold.

### WP4 — Operator presence (runtime)
`user_status` (pinned-seed lookup or constrained column — one canonical casing, the
`COLLATE …BIN2` CHECK) + `user_activity` (traffic-growing; the open row is the current
status, no status column on the user). Idle tracking that WP2's Push path consumes:
operators whose current status is idle/working are assignable; DND/offline are not.
**Done when** presence transitions round-trip, and Push assignment selects an idle
eligible operator (property test).

## 4 · Verification — run these, record real results

| # | Item |
|---|---|
| 1 | `git clean -xdf -e deploy/.env -e .idea && ./gradlew build` — green, every prior test unchanged |
| 2 | `./gradlew check integrationTest --rerun-tasks` — all suites, real SQL Server + Flowable |
| 3 | **The Phase 1 demo still runs end to end** — the gate path is the standing regression canary |
| 4 | **The exception→work-item→resolution loop end to end**: a process reaches a manual step → work item QUEUED → claimed → completed → **the process advances** — demonstrated live, not only in tests |
| 5 | The concurrency/atomicity property tests (WP1): one-winner claim ×1000; create-and-park atomic under fault; complete-and-advance atomic under fault; out-of-order submit refused |
| 6 | **The SLA timer fires** on a breached item, and fires once across a restart (WP3) |
| 7 | Break each of: a new traffic-growing table with no retention class · a routing table whose index leads wrong · an off-contract controller — each fails the build, then revert |
| 8 | `docker compose run --rm verify-isolation` — still green; new runtime/core tables changed nothing about confinement |
| 9 | All six services boot (the hardening lesson — suites can pass while a service cannot start) |
| 10 | Catalog top-up (WP0) byte-stable across two clean migrations; counts complete |

## 5 · When you are blocked

| Situation | Do |
|---|---|
| A 1.x behaviour contradicts the architecture | The architecture wins — that is §0; build it and record the divergence |
| The wait-state timer will not fire either | **Stop and report with the evidence** — this would be a finding as significant as Phase 1's §7.1, and it changes the SLA design. Do not work around it silently |
| Escalation policy / notification routing seems needed | Out of scope (sheet §7) — the narrow breach-detection version only; name the rest in the report |
| A design choice is security- or scope-shaped | PROPOSED section in the report; do not implement |
| Ports / local Postgres / dirty tree | As every phase — `.env` overrides, never the 5455 Postgres, the `-e` clean exclusions |

## 6 · The report — `docs/phase-3-report.md`

The established shape: built per WP · the §4 table with real results · **each of the
three inversions, with the property test that proves it** · every decision the plan did
not dictate · anything found wrong in the sheet, the architecture, or 1.x's shapes —
reported, never silently corrected · what a reviewer should look at first. And, because
this phase probes the engine: **whatever you learn about wait-state timers goes in the
report for the builder-developer**, whose service-task question is the neighbour of this
one.

---

*A gap reported is worth more than a gap filled with a guess. Start with WP0, then WP1.*
