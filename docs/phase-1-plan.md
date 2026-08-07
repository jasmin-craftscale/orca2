# ORCA Phase 1 — Implementation Plan: The Vertical Slice

**For the agent building Phase 1 · August 2026 · Companion report: `docs/phase-1-report.md`**

This plan is self-contained. You do not need the conversations that produced it.
Where it points at another document, read that document before building the thing
it describes.

## Read first, in this order

1. **This plan, in full.** It is your instruction set and your acceptance criteria.
2. **`docs/phase-0-report.md`** — what Phase 0 actually built, what it decided,
   and what it deliberately left open. You are building on it.
3. **`docs/ORCA_ARCHITECTURE.md`** — §B9 (the truck-through-a-lane sequence — the
   thing you are building), §B10 (the guarantees), §C1–§C3 (the three services you
   touch), §D2 (the frozen contracts you must reproduce exactly).
4. **`docs/PLATFORM_PRIMITIVES.md`** — the five primitives you will be consuming.
5. **`docs/ORCA_OPEN_QUESTIONS_REGISTER.md`** — what is deliberately unsettled.
   Consult it before concluding something is missing by accident.

---

## 1 · What this phase proves

**One truck through one gate, end to end, with every architectural guarantee
holding under attack:**

> A plate read arrives at `orca-edge` over the frozen camera contract → exactly
> one visit starts in `orca-runtime` → the process calls a (stubbed) Terminal
> Operating System → commands the barrier through the frozen device-host
> contract → the barrier confirms → the visit completes with its outbox fact in
> one transaction.

**On-site profile only.** The product owner ruled (7 Aug 2026) that the cloud
tier is per-customer and all cloud work is scoped later. `orca-portal`,
`orca-sync` and `orca-fleet` stay exactly the Phase 0 skeletons they are — you
do not touch them except that they must still build and boot.

**No business breadth.** No screens, no work items, no visual builder, no
partner API, no retention jobs. Phase 1 is one path, made unbreakable.

**The acceptance test that matters most:** two simultaneous plate reads for the
same truck produce **exactly one visit — 1,000 times out of 1,000.** It runs in
CI, not once by hand.

---

## 2 · Ground rules (each prevents a specific failure)

| Rule | Why |
|---|---|
| **Never invent a resolution to an unspecified question.** Leave it unbuilt, report it | A plausible guess written as working code is far harder to find than a gap. This rule caught four real contradictions in Phase 0 |
| **Frozen contracts are reproduced exactly** (§D2: LPR framed-XML-over-TCP, device-host REST) | The other side is fielded hardware and a certified vendor component. You do not "improve" them |
| **The hand-written BPMN uses plain BPMN 2.0 constructs only.** No Flowable-proprietary extensions | A separate developer is building the visual builder that will *compile to* this dialect. Anything exotic you use becomes something their compiler must emit |
| **Delegate bean names are an API.** Stable, generic, never renamed casually | They are the compiler's link targets. `${connectorCallDelegate}`, not `${acmeTosDelegate}` |
| **`platform/` stays domain-free.** The build check enforces it — if you fight the check, you are putting the class in the wrong module | Same rule as Phase 0, now with real domain code arriving for the first time |
| **Tests prove properties, not paths** | "The buffer stores an event" is not a test. "The link is severed for a minute under load and zero events are lost, in order" is |
| **Build checks land with the code they govern** | New traffic-growing tables need `@PersistentTable`/`@RetentionClass` the day they exist, not after |
| **Every dependency addition is recorded in the version catalog with why** | Phase 0's rule, unchanged. You will need at least one (resilience — see WP5) |
| **Commit once per work package**, message says what landed | Eight packages, eight commits |

---

## 3 · Rulings already taken — do not re-decide, do not reopen

| Ruling | Decision (product owner, 7 Aug 2026) |
|---|---|
| **Spike 1** | **Folded into this phase as WP0.** The admission property is proven first, against real Flowable + real SQL Server. **Halt condition: if WP0 cannot pass, Phase 1 stops and reports.** Do not proceed to WP4 on a failing WP0 |
| **Published views** | **Prefix inside the `core` schema**: `core.topology_lane`, `core.topology_device`. No eighth schema. §C1's `topology.lane` notation is naming style, not a schema. Core grants SELECT per view as schema owner |
| **Flowable engine tables** | **Under Flyway, now.** The engine does NOT self-migrate: extract Flowable 8.0.0's SQL Server DDL from the jars on the classpath and wrap it as versioned migrations in orca-runtime; set `flowable.database-schema-update: false`. Details in WP3 |
| **Identity** | Keycloak authenticates people; service-to-service calls carry the per-installation shared credential on `/internal/**` (ADR-011, built in Phase 0). Edge→runtime and runtime→edge calls use it |
| **Scope** | On-site, single customer. The tenancy question is closed (NEW-1b); the scope seam still matters — one customer, several sites, is an authorization problem the seam exists for |

**Builder-developer coordination (standing rule for WP4):** a separate developer
is building the visual builder on top of the BPMN format. Your BPMN file is
written *as if it were their compiler's output*, and WP4's deliverable includes
the **BPMN execution profile document** — the written conventions their compiler
targets. That document is durable: it lives at `docs/BPMN_EXECUTION_PROFILE.md`,
not in any phase file.

---

## 4 · What Phase 0 gives you (use it; do not rebuild it)

| You need | It exists as |
|---|---|
| Transactional fact publishing | `OutboxWriter.write(orderingKey, eventType, payload)` — refuses to run outside a transaction. `OutboxRelay.deliverPending(batch)` claims skip-locked, ordered per key |
| One-instance-at-a-time work | `LeaseManager.acquire/renew/release` + `FencedWrite.execute(lease, work)` — the zombie's write is refused and rolled back |
| Exactly-once effects | `IdempotencyStore.begin(key, operation, holder)` → sealed `Fresh / InProgress / Completed`; the second caller gets the recorded outcome |
| Scoped reads | `ScopeSeam.select(ScopedSelect, mapper)` — deny-by-default. **Reads only — see WP2** |
| Startup ordering | `RequiredViewsGate` — set `orca.required-views` and a service refuses to start naming what is missing |
| One response shape | `ApiResponse` envelope, `ErrorCode`, the exception handler that never leaks internals |
| Anonymous-work identity | `SystemContext.runAs(new SystemIdentity(service, task), …)` — the build check forces it on every `@Scheduled` method |
| Service-to-service auth | Send `X-Orca-Internal-Auth` (the shared credential) + `X-Orca-Service` (your name, attribution only) on `/internal/**` calls |
| Contract-first codegen | Extend the service's `openapi/orca-<x>.yaml`, rebuild, implement the regenerated interface. Generated code is never committed |
| Per-service DB identity | Each service's login reaches its own schema only — proven 36/36. Your new tables inherit this for free by being unqualified in that service's migrations |

**Build checks that WILL fire on your code — they are correct, do not fight them:**
services cannot touch `JdbcTemplate`/`EntityManager`/`DataSource` directly (go
through the seam — and see WP2, which extends it); every `@RestController` method
returns the envelope; every `@Scheduled` method enters `SystemContext`; every JPA
entity / `@PersistentTable` declares growth, and traffic-growing tables name a
retention class.

---

## 5 · Work packages, in order

### WP0 — The admission proof (Spike 1, absorbed)

**The one thing this phase can fail on, tested first, in isolation.**

Build in `services/orca-runtime/src/integrationTest/` (throwaway-quality inside,
real infrastructure): real Flowable 8.0.0 + real SQL Server (Testcontainers), a
minimal one-task process, and an admission operation shaped as it will ship:

- `runtime.lane_session` row per lane; admission takes an `UPDLOCK` on it.
- `runtime.execution` insert guarded by a **filtered unique index**: at most one
  active root visit per lane (`WHERE status = 'ACTIVE' AND parent_execution_id IS NULL`).
- Correlate-or-start: try to start; on the duplicate-key loser path, correlate to
  the existing visit. Engine start (`runtimeService.startProcessInstanceByKey`)
  happens in the **same transaction** as the insert.

**The test:** two threads, same plate, same instant, 1,000 iterations → exactly
1,000 visits, zero doubles, zero deadlock-victim leaks (retry on 1205 is part of
the design, not the test's tolerance). Also: kill the transaction between insert
and engine start → neither exists.

**Done when** the test passes 1,000/1,000 repeatably. **If it cannot be made to
pass, STOP the phase and report** — that is the halt condition the product owner
signed. Record the measured admission latency in the report (recorded, not
thresholded — register item, measurement section).

### WP1 — Core's minimal world model and the first published views

- Flyway `V101+` in orca-core: `site`, `area`, `lane`, `device` — internal key +
  external id (§B8), lane's `device_host_url`, out-of-service flag, device type.
  Nothing the slice does not read.
- **The first published views**: `core.topology_lane`, `core.topology_device`
  (per the prefix ruling) — created in core's migrations, with
  `GRANT SELECT ON core.topology_lane TO orca_runtime, orca_edge` (core owns its
  schema; it can grant on its own views — this is ADR-009 working as designed).
- Runtime's and edge's `orca.required-views` lists go from empty to
  `core.topology_lane,core.topology_device` — the readiness gate finally guards
  something real. Verify item 4c of the Phase 0 report still holds: start
  runtime before core has migrated → refuses, names the views.
- Demo seed (one site, one area, one lane, one camera, one barrier) — `local`
  profile only, repeatable migration or startup seeder, your call, recorded.

**Done when** all six services still boot; runtime and edge refuse to start
before core has migrated and start after it has.

### WP2 — The seam learns to write

Phase 0's seam exposes `select`/`count` only, and the build check blocks all
direct JDBC in services — so today a service physically cannot INSERT. That was
correct then; it is your first platform change now.

- Extend `platform/scope` with a scoped write surface (insert/update through the
  seam, same identifier allow-listing, same deny-by-default: a write whose scope
  dimension value is not permitted by the current `Scope` is refused).
- **Every entry point establishes scope explicitly.** Request paths derive it
  from configuration/claims; background work (`SystemContext`) sets the
  installation's site scope deliberately — there is no silent bypass for system
  work. State the mechanism in the seam's Javadoc.
- Coordination/engine tables (lease, idempotency, outbox, Flowable's own) stay
  outside the seam as they are — they are platform-internal and §C2 documents
  why they carry no tenant dimension.
- Property tests in the same style as Phase 0's: an unpermitted write is
  refused; a permitted one lands; the build check still fails a service using
  JdbcTemplate directly (re-prove item-8 style: break it, watch it fail, revert).

**Done when** a service can persist domain rows through the seam and the checks
still hold.

### WP3 — Flowable's schema under Flyway

The ruling: the engine does not self-migrate. Concretely:

- Extract the SQL Server DDL from the Flowable 8.0.0 jars on the classpath
  (`org/flowable/**/db/create/flowable.mssql.create.*.sql` across the engine,
  common, identity, eventregistry artifacts). Do not hand-write it and do not
  download it — the classpath jars are the version truth.
- Wrap as versioned migrations in orca-runtime (e.g. `V110__flowable_common.sql`,
  `V111__flowable_engine.sql`, …), unqualified names as with every migration.
- `flowable.database-schema-update: false` — the engine validates nothing and
  creates nothing; a schema mismatch surfaces as an engine startup error, which
  is the desired loud failure.
- **Verification, not faith:** a scratch test that boots the engine with
  `database-schema-update: true` against an empty scratch schema, then diffs
  `sys.tables`/`sys.columns` against the Flyway-created schema — assert
  identical. This is what proves the extraction was complete.
- Record in the migration header: Flowable version, extraction source paths, and
  the rule that a Flowable upgrade means extracting that version's delta scripts
  as new migrations (expand-only discipline, §B7).

**Done when** runtime boots with engine self-migration off, and the diff test
proves the Flyway schema is byte-equivalent to what the engine would have built.

### WP4 — The process, the profile, and the delegates

- `services/orca-runtime/src/main/resources/processes/gate-visit.bpmn20.xml`:
  start → service task **connector call** → exclusive gateway on outcome →
  service task **device command** → end; error boundary + timer on each service
  task, failure branch to a terminal "manual handling required" end state (the
  clerk workflow that would receive it is Phase 2 — ending the process there is
  correct for the slice).
- Delegates as beans in `runtime.execution`: `ConnectorCallDelegate`,
  `DeviceCommandDelegate` — generic, parameterised by process variables carrying
  **correlation keys only** (visit id, lane external id, connector name). All
  business data lives in platform tables keyed by execution id (§C2).
- The engine stays behind the interface introduced in WP0 — nothing outside
  `execution` imports Flowable types (add the ArchUnit rule for exactly that, in
  the same commit).
- **`docs/BPMN_EXECUTION_PROFILE.md`** — the durable deliverable: supported BPMN
  constructs, delegate binding convention (`flowable:delegateExpression` with
  stable bean names), error/timeout/compensation conventions, the
  variables-are-correlation-keys-only rule, and the statement that classpath
  auto-deploy is Phase 1 scaffolding (the product path is the publish pipeline →
  `/internal/deployments/v1`, arriving with builder integration).
- The gate-visit file is written as if it were the future compiler's output and
  is named in the profile as the **conformance harness's first fixture**.

**Done when** the process deploys from classpath at startup, runs end to end
against stub delegates in a test, and the profile document exists for the
builder developer to review.

### WP5 — Edge ingest: the camera, the buffer, the lease

- Migrations: `edge.event_buffer` (per-lane FIFO: monotonic sequence, `event_uuid`
  dedup key, payload, `PENDING/DISPATCHED/ACKED/DEAD`, attempts — traffic-growing,
  so `@PersistentTable` + `@RetentionClass`), minimal `edge.device_state`.
- **LPR listener**: TCP server speaking §D2's frozen contract — framed XML in,
  acknowledgement back to the camera, capture persisted to the buffer *before*
  the ack is sent (the ack is a durability receipt, not a courtesy). Netty or
  Spring Integration — the §3-permitted choice is yours; record why.
  - **Fixture caveat:** if real capture samples from the 1.x estate are not
    provided to you, implement from the documented contract and **flag the
    fixture gap in the report** — do not invent sample data and call the
    contract verified.
- **Per-lane owner election**: a scheduled loop (`SystemContext`, enforced)
  acquiring `edge.ingest:lane:<lane_id>` via `LeaseManager`; the listener binds
  only lanes whose lease this instance holds; buffer writes wrapped in
  `FencedWrite`.
- **Delivery pump**: batched POST to runtime `/internal/events/v1`
  (shared-credential headers), at-least-once, ordered per lane; runtime's ack
  advances `DISPATCHED→ACKED`; attempts counted; `DEAD` after a bounded number,
  visible via a diagnostics endpoint (`/internal/buffer/stats`, per §C3).

**Done when** the property tests pass: sever the pump for a minute under
sustained ingest → zero loss, per-lane order preserved on drain; two edge
instances → exactly one owns a lane, handover on expiry, the zombie's late
buffer write refused.

### WP6 — Runtime admission and the events endpoint

- Contract-first: add `/internal/events/v1` to `orca-runtime.yaml` (batch of
  device events), regenerate, implement.
- Dedup by `event_uuid` via `IdempotencyStore` (operation `device-event`) — a
  redelivered batch has one effect.
- Wire admission (WP0's operation, now production-placed in
  `runtime.execution`): correlate-or-start keyed on the lane, the winning path
  starts `gate-visit`, the losing path attaches the event to the running visit.
- Lane resolution reads `core.topology_lane` through the seam.

**Done when** the WP0 thousand-truck test passes *through the HTTP endpoint*
(two concurrent batched deliveries of the same event, and two distinct
simultaneous events for one lane), not only through the service call.

### WP7 — Connector out, command out, completion, demo

- **Connector call** (`runtime.integration`): `RestClient` with a deadline; a
  circuit breaker + bulkhead — the resilience dependency is a catalog addition
  with its why recorded (Resilience4j is the boring, defensible choice). One
  connector configuration row pointing at the TOS stub; response-status →
  process-branch routing.
- **Device command**: contract-first `/internal/commands/v1` on orca-edge —
  `command_id` = node-execution id (the idempotency key), action, params,
  `deadline_ms`. Edge: `IdempotencyStore.begin` → expiry check (**expired
  commands are discarded, never delivered** — stale actuation is dangerous) →
  device-host stub call on the frozen REST contract → outcome recorded in
  `edge.command_log` (`EXECUTED/FAILED/UNKNOWN` + `IN_PROGRESS` wire status).
  Replay returns the recorded outcome. `UNKNOWN` routes the process to the
  verify-device-state branch — never a blind retry (§B10).
- **Completion**: the end of the happy path writes visit completion + 
  `OutboxWriter.write("lane:<id>", "visit.completed", …)` in one transaction;
  the relay gets its first `@Scheduled` invocation. No consumer is registered
  yet (nothing on-site consumes it in Phase 1) — say so in the report rather
  than inventing one.
- **Stubs**: `deploy/` gains TOS and device-host stub containers (WireMock or
  equivalent) with mappings under `deploy/stubs/`; `docker compose up` brings
  the whole demo environment.
- **The demo script**: `docs/phase-1-demo.md` — from `docker compose up` +
  bootstrap + three services to "the barrier confirmed", copy-paste commands,
  including how to send a plate read (a tiny script that speaks the camera
  framing at the listener).

**Done when** the demo script executes end to end on a clean machine and every
§6 verification item passes.

---

## 6 · Verification — run these, do not assume them

Every item is a command you execute and whose output you keep. **Item 12 is the
easiest to skip and the most important** (Phase 0's item 8 caught a check that
was silently never firing — twice).

| # | Run | Expected |
|---|---|---|
| 1 | `git clean -xdf && ./gradlew build` | Green from a clean tree — including every Phase 0 test and check, unchanged |
| 2 | `./gradlew integrationTest` | All Phase 0 property tests + all new ones pass against real SQL Server |
| 3 | **The thousand-truck test** (WP0, and again via HTTP in WP6) | 1,000/1,000 exactly one visit; latency recorded in the report |
| 4 | Kill runtime mid-visit (after admission, before completion), restart | The visit resumes from the step it reached and completes; timer state came from the database |
| 5 | Sever edge→runtime for ≥60 s under sustained ingest, restore | Zero events lost, per-lane order preserved on drain |
| 6 | Two edge instances, one lane | Exactly one owns it; kill the owner; the other takes over on expiry; the zombie's late write is refused |
| 7 | Replay every command type with the same `command_id` | The recorded outcome, byte-for-byte; never a bare duplicate |
| 8 | Deliver a command with `deadline_ms` already elapsed | Discarded; the device-host stub records **zero** calls |
| 9 | Redeliver the same event batch to `/internal/events/v1` | One effect; second delivery acknowledged as duplicate |
| 10 | Start runtime before core has migrated | Refuses to start, names `core.topology_lane` / `core.topology_device` |
| 11 | Boot with `flowable.database-schema-update: false` + schema-diff test | Engine starts clean; Flyway-created schema identical to engine-created scratch schema |
| 12 | **Deliberately violate the new/extended checks**: a service using JdbcTemplate; a Flowable import outside `execution`; a new traffic-growing table with no retention class | Each **fails the build**, message naming the violation; then revert |
| 13 | The full demo script, from `docker compose up` on a clean state | Plate read in → barrier confirmed → visit completed with its outbox row; the run transcript goes in the report |
| 14 | `deploy/bootstrap/verify-isolation.sh` | Still 36/36 — the new tables changed nothing about credential confinement |

If any item cannot be run, say so explicitly in the report and name it. **An
unrun command is not a passing one.**

---

## 7 · When you are blocked

| Situation | What to do |
|---|---|
| WP0 cannot be made to pass | **Stop the phase.** Write the report with the evidence. This is the signed halt condition, not a judgement call |
| The LPR contract is ambiguous and no fixture exists | Implement the documented reading, flag the ambiguity + fixture gap in the report. Do not invent sample data |
| A Flowable 8 behaviour contradicts this plan | Report it with the observed behaviour. Do not work around the engine silently |
| Something needs the builder developer's agreement (dialect, profile) | Write your proposal into `docs/BPMN_EXECUTION_PROFILE.md` marked PROPOSED, and list it in the report for their review. Do not block on them |
| The architecture is silent | Register first; if absent there, report the gap. Same as Phase 0 |
| The dev machine's ports 8081–8086 / 1433 / 8080 are taken (the 1.x stack runs there) | Use the `.env` port overrides and `ORCA_DB_URL` / offset `--server.port`s, as Phase 0's verification did. Committed defaults stay the brief's ports |
| A local PostgreSQL exists on this machine (port 5455, other projects') | **Never use it for ORCA — not even for tests.** SQL Server is the only database (ADR-003), and the primitives' SQL is deliberately dialect-specific. Testcontainers and the compose stack cover every database need with full create/recreate rights |

## 8 · Your report — `docs/phase-1-report.md`

Same shape as Phase 0's, beside this plan:

- What you built, package by package (WP0–WP7).
- **The §6 table with the real result of each item**, including anything unrun.
- Measured admission latency (recorded, not thresholded).
- What you could not build, and why. Gaps named, not filled.
- **Every decision this plan did not dictate**, with reasoning — the review surface.
- Anything in the architecture or this plan that proved wrong or contradictory.
  Report it; do not silently correct it.

**A gap reported is worth more than a gap filled with a guess.**

Start with WP0.
