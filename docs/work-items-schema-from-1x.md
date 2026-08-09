# Work items & the clerk workflow — extracted from ORCA 1.x

**⚠️ Provenance: derived from the fielded 1.x code, 9 Aug 2026. This is the reference for the DATA; the architecture (§C2, §B9) governs the BEHAVIOR — and it deliberately inverts 1.x in three places.** Sources: `Lynxis-Gate/common/entity/{workitem_entity,workitem_audit_entity,user_entity,user_activity_entity,screens_entity,group_entity}.go`; the seed/index/behaviour evidence in `common/migration/migration.go` and the work-item, workitem-tracker, shared-apis, screen-builder and admin services. Input for `docs/phase-3-plan.md`. Once a table's migration exists, that migration is the truth and this sheet is history.

**The Phase 2 translation rules (`docs/core-config-schema-from-1x.md` §0) all still apply** — unique ids day one, natural-key constraints, single-casing enums, no denormalized copies, no credentials, pinned seeds, and the conventions in core/runtime's existing migrations override everything. This sheet adds the work-item specifics and the behaviour inversions on top.

---

## 0 · The three inversions — read these before any table

1. **Completion advances the process, in one transaction.** 1.x fires two independent one-way HTTP calls (create at the `MANUAL_INPUT` node, complete at the `MANUAL_INPUT_TERMINATOR`); `/workitem/complete` only flips status and notifies — it never calls back into the engine, and the client advances the engine via node-jumps. **2.0 (§B9 "an exception becomes a work item"): the work item is created in the same transaction as the process step that raised it, and completion completes the item AND advances the process in a single transaction.** There is no state where the console believes an item is done and the engine does not. This is the property the phase must prove.
2. **SLA is a real engine timer, not a UI calculation.** 1.x has **no** server-side timer: `iteration` is never incremented, the watcher (`MonitorWorkItem`) is commented out *and does not exist*, and the thresholds are computed in the operator UI from timestamps. **2.0: the time target is a timer on the compiled process; a breach fires as an engine event, is recorded, and is surfaced.** See §4 for why this is buildable now (the node is a wait state) despite the Phase 1 service-task-timer finding.
3. **Out-of-order submits are rejected.** Implied by inversion 1: because completion is guarded and atomic against the engine's expectation, a submit for a step the engine is not waiting on is refused, not silently applied. 1.x cannot express this (the two halves are decoupled).

**And do not inherit the stubs:** `ESCALATE_TO_LANE`, `ESCALATE_TO_CUSTOMER`, `RE_QUEUED` are declared in 1.x and **never written by any code** (§2 table). 2.0 implements escalation deliberately (inversion 2's timer produces it) or omits these — it does not carry dead statuses as "takeable states nothing produces."

---

## 1 · `work_item` (runtime) — the unit of human work

1.x `work_items` columns worth carrying (translated): the visit/execution linkage (`workflow_execution_id` → 2.0's `execution`), `lane_id`, the screen-node reference (`mipn_id` + `mipn_type` discriminator `WORKFLOW`/`SUBFLOW`), `status`, `queued_on`/`started_on`/`completed_on`, `completion_duration_sec` (server-recomputed as `completed − started`, never trusted from the request), `user_id` (nullable = unassigned), the captured/corrected data pair (`event_data`/`corrected_event_data` — **plaintext in 1.x**; in 2.0 these are payloads subject to the content-addressed store and retention model, not inline text). **Drop:** `iteration` (never incremented — the dead SLA remnant), `group_id` (never wired as an FK, set to 0 — legacy), and the denormalized `customer/site/area` ids where derivable from the lane/execution.

- **Traffic-growing** — needs a growth declaration + retention class + scope-leading index (the checks will insist).
- **Queue membership is `status` + `queued_on` + assignee — there is no queue table** (1.x's second representation, an in-memory array in a tracker service, is not a system of record and does not port). §C2 says the same.
- **Statuses with a writer only** (§2): `QUEUED`, `IN_PROGRESS`, `COMPLETED`, `FAILED` (lane reset). Model exactly these; the work-item lifecycle diagram is §C2's.
- **The claim is a guarded conditional UPDATE** — 1.x: `UPDATE … SET status='IN_PROGRESS', user_id=…, started_on=… WHERE work_item_uuid=? AND status IN ('QUEUED', <takeable>)`, `RowsAffected=0` ⇒ conflict. This is the *same primitive* as the lease and idempotency claims already in `platform/`. Pre-checks exist in 1.x but are racy — **only the conditional UPDATE is the guard** (§C2 says this outright).
- **Actions:** `take` (QUEUED→IN_PROGRESS, guarded), `takeover` (supervisor steal — guarded `WHERE status='IN_PROGRESS'`, reassigns, resets `started_on`, stays IN_PROGRESS), `park` (1.x = re-queue: `status→QUEUED`, clears `user_id`/`started_on` — **there is no distinct PARKED status**; 2.0 may keep park-as-requeue or introduce a real parked state — a design choice to state, not inherit), `assign` (pre-assign: sets `user_id` only, status stays QUEUED — the assignee still must `take`). `TAKE`/`TAKEOVER`/`PARK`/`COMPLETE`/`ESCALATE` are 1.x audit `action_type`s, not statuses.

## 2 · Status → has-a-writer (the load-bearing table)

| Status | Writer in 1.x? | 2.0 |
|---|---|---|
| `QUEUED` | yes (create; park-requeue) | yes |
| `IN_PROGRESS` | yes (take; takeover) | yes |
| `COMPLETED` | yes (complete — and in 2.0 this advances the process, one tx) | yes |
| `FAILED` | yes (lane reset fails the execution, its nodes AND its work items together; screen-failure path) | yes |
| `ESCALATE_TO_LANE` | **NO — read-only, no writer** | implement via the SLA timer (inversion 2) or omit — decide, don't inherit |
| `ESCALATE_TO_CUSTOMER` | **NO — dead** | same |
| `RE_QUEUED` | **NO — dead** | same |

## 3 · `work_item_audit` (runtime) — the trail

1.x `workitem_audits`: child of the audit trail, `action_type` ∈ {TAKE, TAKE_OVER, PARK, COMPLETE, ESCALATE}, `processing_duration_sec` (feeds operator-productivity reports), `elapsed_seconds`. Deviation: 1.x has only `is_deleted`+`created_on` (no `is_active`, no `modified_*`). 2.0: a clean typed audit row per action; traffic-growing (growth + retention + scope-leading index). §C2 lists `work_item_audit` as the trail.

## 4 · SLA as a real timer — buildable now, and here is why

The Phase 1 report (§7.1) found a boundary timer on a **service task** cannot fire: the async executor moves the whole activity — timer job included — into one transaction that creates and deletes the job together, so no other thread ever sees it. **A work-item node is not a service task — it is a wait state** (the process genuinely parks until the operator submits). A boundary timer on a wait state (a user/receive task) is created when the activity is entered and lives until the operator acts or the timer fires — exactly the case Flowable timers are for.

So Phase 3 models the manual-input step as a **BPMN wait state with a boundary timer** carrying the time target, and **proves the timer fires** (a work item left past its threshold raises a breach as an engine event, recorded and surfaced). This is the §A2/§A3 design — "a time target on a clerk task is a timer on the process itself." It also de-risks the builder-developer's open boundary-timer question from the wait-state side, without settling their service-task question (still theirs).

The thresholds themselves (1.x): three per-screen columns on the screen identity — `below_expected` / `expected` / `max` processing seconds, nullable = fall back to two global settings (`EXPECTED_PROCESSING_TIME_SEC`, `MAX_PROCESSING_TIME_SEC` — there is no global `below_expected`). In 2.0 these live on the screen identity (§C1's `topology.screen` carries them for work-item screens) and drive the timer.

## 5 · Routing — team eligibility, and the screen identity it needs

**The team routing rules were deferred from Phase 2 (WP2) because they reference a screen — they come home here.** 1.x `group_configuration_mappings` = one row per (team × screen × lane × priority); it references a **screen identity** (`manual_inputs`) and a lane. Two-stage evaluation:

1. **At creation — which teams are eligible.** Join the routing rules to the screen the work item is on and its lane; return the eligible teams with each team's handling method (Push/Prompt). **Priority is not used here.**
2. **At grid display — ordering.** `COALESCE(priority, -1)`, rows with a set priority before unset, lower number = more urgent, then oldest-queued first (FIFO tiebreak).

**Handling method:** `Push` = assign to an idle operator in an eligible team (direct notification); `Prompt` = broadcast to all eligible teams, any operator pulls. (1.x's Push path iterates a Go map non-deterministically and ignores priority — a split-brain with the grid's SQL ordering; 2.0 should make the two consistent.)

**The screen identity** (`manual_input` in 1.x): a design/screen artifact — `manual_input_uuid`, name, the three SLA thresholds, a node reference. **This phase builds the screen *identity and routing target*, NOT the screen renderer** (the renderer is the frontend/builder, deferred). An admin/seed can create a screen-identity row; work-item routing points at it; what the operator sees is later. Keep the identity minimal and say so.

## 6 · Operator presence (runtime)

1.x `user_status` (6 seeded states — `Idle`, `Working`, `DND`, `Break`, `Offline`, `Active`) + `user_activity` (one row per transition; the open row, `end_time IS NULL` newest `start_time`, is the current status — there is **no** status column on the user). Idle tracking for Push routing: operators whose open activity is `IDLE`/`WORKING` are assignable; `DND`/`Offline` are not.

⚠️ **The casing landmine, again:** 1.x seeds `user_status` TitleCase but every code path compares UPPERCASE — works only on a case-insensitive collation. This is the *same* trap Phase 2's §7.1 caught with enum CHECKs. 2.0: one canonical casing (recommend UPPER to match), a `CHECK` with `COLLATE …BIN2`, and presence as a small typed model. `user_status` becomes a constrained column or a pinned-seed lookup; `user_activity` is traffic-growing.

## 7 · What this phase deliberately does NOT build

- **The screen renderer** — frontend/builder. This phase builds screen *identity* + routing only.
- **The full escalation *policy*** (who is told, which channel, per-type configuration) — §A2/register #5: breach *detection + recording + visibility* is cheap and in scope (the timer produces it); a configurable routing/notification policy is a separate feature, not implied by the word "escalation." Ship the narrow version.
- **Notifications / the WebSocket hub** as a full module — a later phase (runtime `notify`). This phase may record a breach and surface it via a query/endpoint; the live-push hub is Phase 5.
- **Anything portal/sync/fleet** — cloud scope.
