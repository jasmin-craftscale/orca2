# ORCA Phase 3 — Report: Work Items & the Clerk Workflow

**Written by the session that built it · 9 August 2026 · Companion: `docs/phase-3-plan.md`**

Everything the plan asked for is built, the three behaviour inversions hold as
property tests rather than intentions, and **the phase's load-bearing technical
claim is proven by firing it: a boundary timer on the manual-input wait state
fires, survives a restart firing exactly once, and never fires falsely** — the
wait-state answer to the Phase 1 §7.1 service-task finding, and the evidence the
builder-developer's timer question was waiting for. Five commits, one per work
package, plus this report.

---

## 1 · Headline

**The gate's `MANUAL` branch is real work.** A process step automation cannot
finish parks the engine at a genuine wait state and creates the work item **in
the same transaction**; the item routes to eligible teams through core's new
screen identity and routing rules; an operator claims it with a guarded
conditional update; completing it **advances the parked process in one
transaction**, and a submit for a step the engine is not waiting on is refused
whole. The time target on the step is **a real engine timer** that fires on
breach, records it, and disturbs nothing.

The numbers: two new runtime tables (`work_item`, `work_item_audit`) plus
`user_activity`, two new core tables (`screen`, `team_routing`) plus five new
published views, one BPMN wait state with a non-interrupting SLA boundary timer,
three new compiler link targets (`workItemSla`, `workItemSlaBreachDelegate`, and
the bare `userTask` convention), 16 new contract operations across the two
services, and **five new integration suites (24 tests) on top of the updated
Phase 1 suites — 220 integration tests in 30 suites, all green under
`check integrationTest --rerun-tasks`**. The catalog debt Phase 2 recorded is
paid: 19 / 40 / 16, byte-stable.

## 2 · What was built, package by package

### WP0 · Device catalog completion (core V108, commit `a1f0e1e`)

- `io_device_kind` seeded all 19 rows (was empty); `device_io_port_name`
  completed to 40 (four Audio inserts — see §6.1 for the sheet's off-by-one);
  `device_type` completed to 16, with the three provisional PTZ codes corrected
  in place to the 1.x-exact ones (`PTZ_CAMERA` → `AXIS_PTZ_CAMERA` etc.). The
  code is the identity (translation rule 7), so each corrected row's external id
  follows its code — the UUID that code would always have minted in V106's own
  namespace.
- **Generated, not transcribed**: `deploy/tools/gen-device-catalog-completion-seed.py`
  parses the completion sheet, asserts its counts before emitting a row, and
  mints UUIDv5 in the same namespace V106 used — regeneration is byte-identical.
- Display names moved to the 1.x-verbatim casing everywhere (display-only by
  rule 7). One integration test referenced a provisional code and was updated
  with the migration.

### WP1 · The work-item lifecycle (runtime V115, commit `1ae8832`)

- **The wait state.** `gate-visit`'s failure branch no longer *ends* at
  "manual handling required" — it parks at a bare `userTask` (`manualInput`),
  and the new end event (`manualHandlingResolved`) is reached only after a
  person resolves the item. The visit stays `ACTIVE` while a person works,
  because the truck is still physically standing at the gate; `MANUAL` now marks
  a visit closed *after* manual handling.
- **Creation is platform behaviour, in the engine's transaction.**
  `WorkItemCreationListener` (an engine event listener, registered once at
  startup — the same argument as visit completion: a compiler-emitted binding a
  designer could omit would park processes with no item in any queue) creates
  the item while the engine parks, `isFailOnException = true`, so the two halves
  commit or roll back together.
- `work_item` + `work_item_audit`: traffic-growing, retention-classed
  (`work_item` PROVISIONAL / `audit`), scope-leading indexes, exactly the four
  writer-backed statuses. The dead 1.x escalation statuses are absent (sheet §2).
- **Every transition is a conditional UPDATE guarded by rows-affected** — take,
  takeover (reassigns, resets the clock, audits the previous holder), park
  (re-queue: assignee and clock cleared — the stated design choice, no `PARKED`
  status, matching §C2's lifecycle diagram), assign (reserves the claim, stays
  `QUEUED`), complete. Losers get a typed 409 carrying the item's current state.
- **Complete-and-advance, one transaction**: the guarded item update, the
  engine's task completion (through `execution.api.ManualStepPort` — the module
  wall's second seam, opposite `workitem.api.WorkItemIntake`), and everything
  the process then runs synchronously — for gate-visit, the visit's own closing
  write — share one commit. `completion_duration_sec` is server-computed.
- **Lane reset** (`POST /api/v1/lanes/{id}/reset`): §C2's one-transaction abort —
  lane lock first (serialising with admission), terminate the instance, fail the
  open items, close the visit `FAILED` (a new `execution` status V115 adds).
  This is the writer that makes `work_item.FAILED` a status with a writer.
- Contract-first routes for the whole §C2 work-item surface built this phase.

### WP2 · Routing & the screen identity (core V109 + runtime, commit `566fdbc`)

- **Core**: `screen` — the identity ONLY (name, the `(process_definition_key,
  node_reference)` binding, three nullable SLA thresholds) — and `team_routing`
  (team × screen × lane × priority), with the two constraints 1.x never had:
  **one active screen per node per site** and **the unique routing tuple**.
  Admin surfaces: `/api/v1/screens`, `/api/v1/teams/{id}/routing-rules` as a
  declarative set (retire-and-insert, the seam's shape). Five published views:
  `topology_screen`, `topology_team_routing` (denormalised to node + lane
  vocabulary so runtime's evaluation is one read), `topology_team_member`,
  `topology_operator` (installation-realm; subject → user), and WP3's
  `topology_setting`.
- **Runtime**: creation resolves the screen identity inside the creating
  transaction; the claim respects eligibility (member of a routed team; the
  pre-assignee overrides; **unrouted work is claimable by anyone** — an item
  nobody may touch is worse than one everybody may); refusals are typed
  `WORK_ITEM_NOT_ELIGIBLE` (403), deliberately distinct from a conflict.
- **Priority ordering, one ordering for every consumer**: set before unset,
  lower more urgent, oldest-queued tiebreak — sorted in the service (§5.4), used
  by the grid and by nothing else inconsistent; the Push path's selection is
  deterministic by construction (WP4), which closes 1.x's split-brain between
  the grid's SQL and the push path's Go-map iteration.
- `OperatorIdentity` now resolves the token subject through `topology_operator`,
  retiring WP1's stated subject-as-actor slice shape exactly as promised: one
  bean changed, no caller did.

### WP3 · The SLA timer (core V110 + runtime, commit `e040a65`)

- **A non-interrupting boundary timer on the wait state** — buildable exactly
  where §7.1's service-task timer was not, because the wait state genuinely
  parks: the timer job is committed and visible to the async executor.
  `cancelActivity="false"`: a breach marks the item; it must not kill the
  operator's work.
- The duration is resolved **at arming time** by the `workItemSla` bean (a new
  compiler link target): the screen's `max_sec`, else the
  `MAX_PROCESSING_TIME_SEC` setting through core's new `topology_setting` view
  (the read V107's seed row promised), else a ten-year sentinel (§5.6). The
  node reference travels as a string literal in the expression — the compiler
  knows what it attached the timer to.
- On breach, `workItemSlaBreachDelegate` records `sla_breached_at` (**a column,
  not a status** — the stated §2 design choice; the dead `ESCALATE_*` statuses
  stay dead) plus an `SLA_BREACH` audit row, on every open item of the instance
  **whose own threshold has genuinely elapsed** — precise even if a process one
  day carries several manual steps. Detection + recording + visibility only
  (register #5's narrow version); breach state is surfaced on the work-item
  detail and list payloads (`slaBreachedAt`). No escalation policy is implied.
- **The breach branch exposed a real Phase 1 defect** (§6.2): the completion
  listener closed the visit on *any* end event. Reworked to close only on
  `PROCESS_COMPLETED`, with the end-event id stashed from `ACTIVITY_COMPLETED`
  in the same command — measured, not assumed (§7).

### WP4 · Operator presence (runtime V116, commit `ea62311`)

- `user_activity`: 1.x's model kept because it is right — one row per
  transition, **the open row IS the current status**, no status column on any
  user to drift — with the two things 1.x never had: one canonical casing
  behind a binary-collated CHECK (the sheet's casing landmine, closed the
  Phase 2 way), and **at most one open row per operator** as a filtered unique
  index. Transitions close-and-open in one transaction; racing transitions
  serialise on the index, the loser retries on top of the winner.
- Assignable = `IDLE` or `WORKING`, exactly as the sheet names them; `DND`,
  `BREAK`, `OFFLINE`, `ACTIVE` are carried as statuses but are not assignable
  (§5.8). An operator with no row at all is `OFFLINE`.
- **Push completes WP2's evaluation, in the creating transaction**: PUSH rules
  in deterministic order (priority set-first, team id tiebreak), the first team
  with an assignable member **pre-assigns** it — `IDLE` before `WORKING`,
  longest in state first, user id as the total order. The item stays `QUEUED`;
  the assignee still takes it (sheet §1). Nobody assignable → unassigned and
  visible. PROMPT never assigns — broadcast is the notify hub's, later.
- Presence surface: `GET·PUT /api/v1/me/presence`, `/api/v1/operators/idle`,
  `/api/v1/operators/activity`.

## 3 · The three inversions, each with the test that proves it

| # | Inversion (sheet §0) | The proof |
|---|---|---|
| 1 | **Creation and completion are each ONE transaction with the engine.** 1.x fired two independent one-way HTTP calls and the client advanced the engine by node-jumps | `WorkItemLifecycleIT.theItemAndTheWaitStateAppearTogether` (the item's `task_id` is the engine's own live task; visit still `ACTIVE`); `createAndParkAreAtomicUnderAFault` (a poisoned `work_item` table rolls back item AND wait state together — the job dead-letters with *neither* half applied, and the healed retry produces *both*); `completionAdvancesTheProcessAtomically` (no polling: the visit is `MANUAL`, the task gone and the instance ended **before `complete()` returns**); `completeAndAdvanceAreAtomicUnderAFault` (a poisoned audit table unwinds the completion whole — the item stays held, **the engine still parks**, and the same call succeeds after the poison lifts) |
| 2 | **SLA is a real engine timer.** 1.x had no server-side timer at all — thresholds were computed in the operator UI, the watcher didn't exist | `WorkItemSlaIT.theTimerFiresAndRecordsTheBreach` (a committed `ACT_RU_TIMER_JOB` row while parked — the §7.1 precondition a service task can never meet — then the breach recorded while the item stays `QUEUED`, claimable, the visit `ACTIVE`); `theTimerSurvivesARestartAndFiresOnce` (armed by one instance, fired by a **different** instance after a kill, exactly one breach + one audit row — §B10's "a timer survives a restart", now for SLA); `completionInsideTheThresholdKillsTheTimer` (no false breach, the job dies with the task) |
| 3 | **An out-of-order submit is refused.** 1.x's decoupled halves could not even express the check | `WorkItemLifecycleIT.anOutOfOrderSubmitIsRefusedAndNothingMoves` (an item whose task the engine never held: typed `WORK_ITEM_OUT_OF_ORDER` 409, the item update **rolled back with the refusal**, no audit row; and the second face — a completed item refuses a second submit as `WORK_ITEM_CONFLICT`) |

Plus the claim race at admission's own scale:
`twoOperatorsRacingTheClaimProduceExactlyOneWinner` — 1,000 iterations, two
simultaneous claims each, exactly 1,000 winners and 1,000 `TAKE` audit rows.
The conditional UPDATE is the only guard, as §C2 directs.

## 4 · Verification — the plan's table, with real results

| # | Item | Result |
|---|---|---|
| 1 | `git clean -xdf -e deploy/.env -e .idea && ./gradlew build` | ✅ Green from a clean tree (`deploy/.env` survived the clean — the exclusions matter). Gradle's build cache restored 30 of 68 task outputs; item 2's `--rerun-tasks` is the forced pass, as in Phase 2 |
| 2 | `./gradlew check integrationTest --rerun-tasks` | ✅ `BUILD SUCCESSFUL in 6m 1s`, all 68 tasks executed — every suite forced, real SQL Server via Testcontainers, real Flowable 8: **220 integration tests in 30 suites** (180 in 23 after Phase 2; the five new suites add 24 — `WorkItemLifecycleIT` 8, `WorkItemPresenceIT` 5, `WorkItemRoutingIT` 4, `RoutingScreensPropertiesIT` 4, `WorkItemSlaIT` 3 — and the reworked Phase 1 suites the rest), plus the unit tests and the ten build checks |
| 3 | The Phase 1 demo end to end | ✅ Run live on the +10000 offset against the compose stack. Core's startup applied V108–V110 onto the existing demo database (`Successfully applied 3 migrations … now at version v110`); runtime applied V115–V116 (`now at version v116`). One truck: plate `T-PHASE3-01` ACKed over the camera wire format → visit `vis-d1aedec5-9465…` **COMPLETED** → `RAISE_GATE` **EXECUTED** for `DEV-DEMO-BARRIER` in `edge.command_log` → `visit.completed` keyed `lane:LANE-DEMO-01` in `runtime.outbox`. The happy path is untouched by the phase |
| 4 | The exception→work-item→resolution loop, live | ✅ Demonstrated end to end (§4.1): the connector route remapped away from 200 (the phase-1 demo's own unrouted-branch technique), so the stub's answer became a token no branch matches → the process **parked** at `manualInput` → work item `wi-fd0a84fc…` `QUEUED` with screen `scr-demo-manual` resolved and the captured discriminator (`{"connectorOutcome":"HTTP_200"}`) on it, the visit `ACTIVE` → claimed over HTTP with a real Keycloak token resolved to `usr-demo-clerk` through `topology_operator` → completed with `correctedEventData` → **the visit read `MANUAL` immediately after the complete call returned**, and the audit trail read `TAKE`, `COMPLETE` |
| 5 | The WP1 concurrency/atomicity property tests | ✅ All in item 2's run and §3's table: one-winner ×1,000; create-and-park atomic under fault; complete-and-advance atomic under fault; out-of-order refused |
| 6 | The SLA timer fires; fires once across a restart | ✅ `WorkItemSlaIT`, §3 row 2 — fired, restart-survived (armed by an instance that died, fired by its successor), exactly once. **Also fired live in the demo**: truck `T-PHASE3-03` parked at 04:16:25, `sla_breached_at = 04:17:25.248` — the seeded 60-second threshold to the second — with the item still `QUEUED`, the visit still `ACTIVE`, an `SLA_BREACH` audit row by `system:sla-timer`, and the breached item then taken and completed normally, breach preserved on the record |
| 7 | Break the checks: growth/retention · wrong-leading index · off-contract controller | ✅ All three, watched to fail and reverted (§4.2) |
| 8 | `docker compose run --rm verify-isolation` | ✅ `PASS — 36 checks. Each login owns its own schema and reaches no other.` Run with V108–V110/V115–V116 live in the compose database |
| 9 | All six services boot | ✅ All six, against the compose stack with the `local` profile on offset ports 18081–18086, each answering `/actuator/health` `{"status":"UP"}` — the hardening lesson checked deliberately; the three gate-path services additionally exercised live by items 3–4 |
| 10 | WP0 catalogs byte-stable, counts complete | ✅ `CatalogSeedPropertiesIT.theSeedIsByteStableAcrossCleanMigrations` migrates two clean databases and compares full row sets including UUIDs: 174 / 16 / 40 / 19 identical; `theDeviceCatalogsAreComplete` pins the counts and the corrected codes |

### 4.1 · The live loop, as run

The full transcript is reproducible from `docs/phase-1-demo.md` §3's offset
incantation plus the following (all against the compose stack):

1. `docker compose run --rm demo-seed` — now also seeds the clerk world: role,
   `usr-demo-clerk`, `team-demo-clerks` (PROMPT), screen `scr-demo-manual`
   (`gate-visit:manualInput`, expected 30 s / max 60 s), and the routing rule to
   `LANE-DEMO-01` at priority 1.
2. A token from the local realm (client credentials, the dev convenience
   client — its 5-minute lifetime needed one refresh mid-demo), and the one
   visible step that links its subject to the platform user —
   `UPDATE core.user_account SET keycloak_subject = '<sub>' WHERE external_id =
   'usr-demo-clerk'` — which is the operator directory doing its job, not a
   workaround: the local realm deliberately has no human users.
3. `UPDATE runtime.connector_route SET http_status = 418 …` (the phase-1 demo's
   own unrouted-branch technique), then `./gradlew sendPlate
   -Pplate=T-PHASE3-02`: the stub's 200 becomes the token `HTTP_200`, no branch
   matches, the default flow parks the visit at the wait state.
4. `GET /api/v1/work-items?status=QUEUED` → the item, `screenExternalId =
   scr-demo-manual`, `eventData = {"connectorOutcome":"HTTP_200"}`;
   `POST …/take` → `IN_PROGRESS`, assignee `usr-demo-clerk`;
   `POST …/complete` → `COMPLETED` with `completionDurationSec` server-computed
   (0 — the clerk was quick) — and the visit row already `MANUAL` when the
   response returned. `GET …/audit` → `TAKE`, `COMPLETE`. Route restored to 200
   afterwards.

### 4.2 · The break-each-check exercise

- **(a)** `@RetentionClass` stripped from `PresenceTables.UserActivity` →
  `RetentionClassRule` failed: *"declares table 'user_activity' as
  TRAFFIC_GROWING but names no @RetentionClass"*. Reverted.
- **(b)** both of `work_item`'s indexes re-led with their non-scope columns in
  V115 → `ScopeIndexRule` failed naming the table, the file, and the trap:
  *"declares a scope column but no index leads with one. Its primary key is
  (work_item_id) and its indexes lead with [status, execution_id] … can only be
  SCANNED — and a scan under UPDLOCK locks every row at the site"*. Reverted.
- **(c)** A `RogueController` in `workitem.api` with an unauthored route →
  `ContractInterfaceRule` **and** `ErrorEnvelopeRule` both failed. Reverted.

## 5 · Decisions the plan did not dictate

In rough order of consequence.

### 5.1 · A visit in manual handling stays ACTIVE — and therefore holds its lane

Phase 1 ended manual-handling visits (`MANUAL`, lane freed). Phase 3 parks them:
the visit stays `ACTIVE` at the wait state, and the filtered unique index keeps
the lane held until the item completes or the lane is reset. This is §B9's
design and it is also physically true — the truck is still standing at the gate —
but it is a real operational change: **an unstaffed queue now blocks a lane**
where Phase 1 quietly waved the visit into a terminal state. Lane reset is the
operator's relief valve, and the SLA breach is the alarm. `MANUAL` now means
"closed after a person resolved it". Stated here because nothing in the plan
said which way the lane should behave while a person works.

### 5.2 · Cross-module linkage values are stored, not joined

`work_item` carries `visit_external_id` and `lane_external_id` beside the
surrogate keys. Not a rule-6 violation by intent: the module wall keeps
`workitem` out of `execution`'s tables, so the published-vocabulary values
arrive once, across the `api` seam, in the creating transaction — the same
precedent as `lane_session` carrying `lane_external_id`. The alternative (a
readmodel projection for every grid row's lane id) buys nothing at this stage.

### 5.3 · Lane reset takes the lane lock, and does not touch out-of-service

Reset serialises on the same `lane_session` row as admission — a reset racing an
arriving truck must not abort a visit mid-attachment. §C2's "set the lane
status" is read as the lane *session* (bound plate cleared); the lane's
`is_out_of_service` flag is core's configuration and is not written by runtime.
`node_execution` rows are not failed because the table does not exist in any
phase yet — recorded, not guessed at.

### 5.4 · Priority ordering is sorted in the service, not in SQL

The priority lives on core's routing rules; the item is runtime's; the seam has
deliberately no cross-schema join and its `orderBy` accepts identifiers only.
The open queue is operationally bounded (trucks physically standing at a site's
gates), so the read is capped (2,000), sorted, and trimmed — and the ordering
semantics live in one tested place. A site with more than 2,000 *open* items has
a different problem. Terminal-status listings stay SQL-ordered FIFO.

### 5.5 · Eligibility semantics: unrouted work is claimable by anyone

An item with no screen identity, or a screen with no routing rules, is visible
in every grid and claimable by every operator. The alternative — unclaimable
work — is invisible human work, the worst failure the module can have. A
pre-assignment (a supervisor's deliberate act) overrides team eligibility.
Takeover is deliberately not eligibility-checked: it is the supervisor action,
and gating it waits for the entitlement wiring on the console, not for this
module. The eligibility check is authorization, not the race guard — only the
conditional UPDATE prevents a double claim.

### 5.6 · The unconfigured-threshold sentinel

A BPMN boundary event is static; an arming expression returning null fails the
task's entry — which would break the manual step precisely when a screen is not
yet configured. So `workItemSla` answers a ten-year sentinel for a step with no
threshold anywhere: one engine-table row, deleted with the task. The
alternative (emit the timer conditionally at compile time) would freeze
threshold configuration into the published process. The profile §8b records it
for the compiler.

### 5.7 · Thresholds are snapshots at arming time

A threshold changed after an item parked applies to the next item, not the
parked one — the timer's nature, stated. The breach delegate re-derives
per-item thresholds at firing time (so a multi-manual-step process cannot
mis-mark a sibling), with a 5-second tolerance for the skew between the
engine's arming clock and the database's `queued_at`.

### 5.8 · The assignable set is exactly IDLE and WORKING

The sheet names idle/working as assignable and DND/offline as not; `BREAK` and
`ACTIVE` it carries without a ruling. Both are kept as statuses (operators use
them in 1.x) and excluded from assignability — a break interrupted by a push
assignment is not a break. `ACTIVE`'s meaning in 1.x is unclear (§6.4); it is
carried, not interpreted.

### 5.9 · Work-item payloads are inline for now

`event_data` / `corrected_event_data` are inline `NVARCHAR(MAX)`: §C2's
content-addressed `payload_blob` store is not built in any phase yet and
ADR-017's thresholds are unset (register #27). The captured context is branch
discriminators only (profile §6), so nothing large can arrive today; when the
store lands, these columns join it.

### 5.10 · `mipn_type` does not port yet

1.x's WORKFLOW/SUBFLOW discriminator would be a column with one writable value —
subflows are unsettled in the profile (§2). The node reference is
`(process_definition_key, node_reference)`, which is what the engine parks on.

### 5.11 · The audit action vocabulary reuses V107's

Core's `audit_event` CHECK allows `CREATED/UPDATED/RETIRED/REINSTATED/REPLACED`;
the routing admin surfaces write within it. The runtime work-item audit has its
own action list (TAKE/TAKE_OVER/PARK/ASSIGN/COMPLETE/FAIL/SLA_BREACH) — operator
actions, not config mutations, deliberately a different vocabulary.

## 6 · Found wrong, or self-contradictory — reported, not silently corrected

### 6.1 · The completion sheet's "5 Audio rows missing" is off by one

`docs/device-catalog-completion-from-1x.md` heads its port-name section "the 5
Audio rows Phase 2 was missing", but V106 had already seeded `FRONT_MIC` via the
sheet's own naming-drift note — the phase-2 report says so ("four of the five
audio names are unextracted"). V108 inserts four. The sheet's *list* of five is
correct; its heading is not. Not corrected in the sheet (it is provenance
history); recorded here.

### 6.2 · Phase 1's completion listener closed the visit on ANY end event

A latent defect, exposed the moment the process gained a non-interrupting
branch: the SLA breach path concludes at its own end event *while the task
still waits*, and the WP7 listener would have closed the visit `MANUAL` with
the truck still standing at the gate. Reworked (§7 has the engine detail). The
old design was correct for Phase 1's process shapes; it was the *assumption* —
"every end event ends the process" — that did not survive, and nothing in the
corpus had written it down as an assumption.

### 6.3 · `FlowableAdoptionIT`'s rewind pattern claimed a namespace

The adoption fixture deleted Flyway history `WHERE version LIKE '11%'` — which
silently swallowed V115 the day it existed, orphaning the work-item tables from
their history and failing three tests two suites away. Narrowed — and a
standing cost surfaced: **runtime migrations after V114 sort after the engine's
five, so the adoption fixture's rewind must drop every ≥V115 table too** (paid
twice already, for V115 and V116; the fixture now says so in place). The
committed adoption *script* checks `version = '110'` exactly and is unaffected.

### 6.4 · Smaller notes

- The architecture's §C2 work-item route list spells the actions this phase
  built; `/screens/submit`, `/lanes/{id}/take-next`, the grids and exports
  remain unbuilt surface — deliberately (the renderer and console are elsewhere;
  a route is authored when the behaviour exists).
- 1.x's `ACTIVE` presence status has no discoverable semantics in the extraction
  (six seeded states, two assignable, three named unavailable — `ACTIVE` is
  carried verbatim and unused by any 2.0 path).
- `GateVisitProcessIT`'s three failure-branch tests asserted the Phase 1
  terminal end state; they now assert parking — a behaviour change consumed by
  this phase's own plan, not a silent edit (the diff is in WP1's commit).

## 7 · What was learned about wait-state timers — for the builder-developer

This section exists because your open question (§8 of the profile) is the
neighbour of what this phase measured.

1. **A boundary timer on a `userTask` fires.** The mechanics, observed: entering
   the task commits a row in `ACT_RU_TIMER_JOB` in the same transaction that
   parks the process; the async executor acquires and fires it later, in its own
   transaction. `WorkItemSlaIT` asserts the committed job row directly — the
   visibility that §7.1's service task can never have, because there the job is
   created and deleted inside one transaction.
2. **The timer is engine state in the database, nowhere else.** The instance
   that armed it was killed; a different instance fired it. Restart-safety costs
   nothing extra — it is what "the engine's state is rows" already means. Pair
   it with an idempotent recording write (`sla_breached_at IS NULL` in the
   predicate) and "fires once" survives job retries too.
3. **Non-interrupting is the correct default for SLA.** `cancelActivity="false"`
   leaves the operator's task untouched; the breach branch runs beside it. An
   interrupting timer would delete a task an operator may be mid-way through.
4. **A non-interrupting branch breaks any "end event = process over" logic.**
   The breach branch ends at its own end event while the process still waits.
   Anything keyed on end events alone — Phase 1's completion listener was —
   must key on `PROCESS_COMPLETED` instead. Measured in the rework:
   `PROCESS_COMPLETED`'s entity does **not** carry the final end event's id, so
   the listener stashes the id from `ACTIVITY_COMPLETED` (same command, same
   transaction) and consumes it on `PROCESS_COMPLETED`; `PROCESS_CANCELLED`
   clears the stash.
5. **Arming expressions run at activity entry, and null kills the entry.** Hence
   the sentinel (§5.6). If the compiler ever emits timers conditionally instead,
   it trades that row for frozen threshold configuration — a worse trade while
   thresholds are runtime configuration.
6. **The executor's default timer-acquire interval is 10 s** — irrelevant at
   production thresholds, but a test (or a demo) with a single-digit-second
   threshold must lower `default-timer-job-acquire-wait-time` or the firing
   reads as flaky.
7. **What this does NOT settle**: your service-task question. Whether device
   commands become `triggerable` wait states — which would let the engine own
   the command deadline — remains yours with the product owner (profile §8).
   This phase's evidence says only: *if* a step is a wait state, its timers are
   real, restart-safe protection.

## 8 · What a reviewer should look at first

1. **`WorkItemSlaIT`** — the phase's load-bearing claim, proven by firing it.
   If you read one suite, read this one.
2. **`WorkItemLifecycleIT.createAndParkAreAtomicUnderAFault` and
   `completeAndAdvanceAreAtomicUnderAFault`** — the two fault injections that
   make inversion 1 a property rather than a diagram.
3. **`VisitCompletionListener`** — the Phase 1 defect and its rework (§6.2);
   the one place this phase *changed* shipped Phase 1 behaviour.
4. **§5.1** — the lane-holding semantics of a parked visit. It is the
   architecture's design, but it is the decision with operational teeth.
5. **`docs/BPMN_EXECUTION_PROFILE.md` §8a/§8b** — the new compiler contract:
   the bare `userTask`, the three bean names, the literal node reference, the
   sentinel.
6. **V115's header** — the migration numbering constraint (work-item tables sit
   after Flowable's V110–V114, so every future runtime migration lands ≥V115 and
   the adoption fixture must know it).

## 9 · What is deliberately absent, per the plan's scope guard

The screen *renderer* and component tree (builder-developer); the notify hub
and any breach *notification* (Phase 5 — the breach is queryable, not pushed);
a configurable escalation *policy* (register #5's second half — nothing here
implies it); `/screens/submit`, take-by-lane, the operator grids and exports
(console scope); portal/sync/fleet (cloud scope, register NEW-1b). Escalation's
dead 1.x statuses were not inherited anywhere.
