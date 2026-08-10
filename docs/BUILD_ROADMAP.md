# The build roadmap — what we are building, and where you fit

**Read this after `docs/DEVELOPER_ONBOARDING.md` and before you start on a stream.**

The architecture document describes the destination. This one describes the
journey: what is finished, what is being built now, what depends on what, and what
is deliberately not started. It is deliberately honest about the gaps — a roadmap
that overstates progress is worse than none, because people build on things that
are not there.

---

## 1 · What we are building

A gate-automation platform for logistics facilities. **Seven services on Java 25 and
Spring Boot 4, one SQL Server database with a schema per service, and a workflow
engine embedded inside the runtime.** It ships to new clients only — there is no
migration from the system it replaces.

| Service | Owns | State |
|---|---|---|
| `orca-core` | The world as configured — sites, lanes, devices, users, permissions, teams, settings, and eventually process and screen design | Built, except design |
| `orca-runtime` | The world as it happens — the engine, visits, work items, connectors, the partner API | Built, two modules empty |
| `orca-edge` | Every hardware contract, the durable capture buffer, the command log | Built |
| `orca-portal` | Carriers, drivers, tickets — the only internet-facing service | Skeleton |
| `orca-sync` | Replication between a site and a hosted tier | Skeleton |
| `orca-fleet` | Licence issuance and signing — cloud only | Skeleton |
| `orca-media` | Video and intercom | Inherited, not rebuilt |

The full design is `docs/ORCA_ARCHITECTURE.md`. Read its §B10 first if you read
nothing else — it states what the architecture guarantees and how each guarantee is
verified, which is the acceptance criteria the build is held to.

## 2 · How it gets built

The same five steps every time. They have produced four phases that all held up
under independent verification, so follow them rather than improvising.

1. **Extract the reference.** If the work touches anything the old system does
   today, produce a `docs/*-from-1x.md` sheet first — real columns, real behaviour,
   file-and-line evidence, and the defects deliberately not carried forward. **A
   plan without its reference sheet invites guessing.**
2. **Write the plan.** Self-contained, because its reader may have none of your
   context. What it delivers and what it explicitly does not; work packages in
   dependency order, each with its own "done when"; a verification table of
   commands to execute; what to do when blocked.
3. **Build it**, in commits of one concern.
4. **Verify by executing.** A report is a claim until someone re-runs it. Re-run
   the suite, restart the services, drive a truck through the gate.
5. **Write the report.** What was built, what was not, every decision the plan did
   not dictate, and anything that looked wrong — **reported, not silently
   corrected.**

**Where behaviour comes from, when porting:** the old system is the reference for
the *data*; the architecture governs the *behaviour*. Where the two disagree, name
the disagreement rather than resolving it quietly.

## 3 · What is finished

Four phases, each independently re-run and re-driven rather than accepted on its
report. What matters is not what each added but what each **proved**.

| Phase | Delivered | Proved |
|---|---|---|
| **0 · Foundations** | 12 Gradle modules, 6 bootable services, 5 shared primitives, the build checks | That the walls hold: a service cannot reach another's schema, and a query cannot escape its scope |
| **1 · The gate path** | Plate read → durable buffer → exactly one visit → customer call → confirmed barrier → recorded fact | **One truck, one visit — 1,000 times**, with two simultaneous events from two instances. The property the whole design turns on |
| **2 · The configuration world** | `orca-core`: identity, permissions, teams, the device registry, settings | That the database refuses what it must refuse — every uniqueness rule is a constraint, not a hopeful check |
| **3 · The clerk workflow** | Work items, routing, operator presence, the SLA timer | That creating human work and advancing the process **cannot disagree** — both halves commit together or roll back together, proven by fault injection |

The gate path runs end to end today. You can drive it yourself in a minute;
`docs/phase-1-demo.md`.

## 4 · What is being built now

**Four streams. They are not a queue — three of them run at the same time.**

```
Stream 1 · Partner event API        ──┐
Stream 2 · Read models & notify     ──┼──→  Stream 4 · Retention & purge
Stream 3 · Core remainder  (independent)    (last — it touches every schema)
```

Streams 1, 2 and 3 are concurrent. **Stream 4 is not concurrent with anything**:
it touches every schema, so it would collide with all three. It also purges tables
that streams 1 and 2 create, which is the second reason it goes last.

### What makes the concurrency actually work

Three mechanisms. Without them, "parallel" means "merge conflicts".

- **Each stream owns whole modules.** Streams 1 and 2 both work inside
  `orca-runtime`, but on different modules — and the module walls are already
  enforced by a build check, so the check polices the boundary for you.
- **Migration numbers are assigned in ranges before anyone starts.** Streams 1 and
  2 both add migrations to the `runtime` schema, which is at `V117` today. Two
  developers both writing `V118` is a conflict that surfaces only when somebody's
  database refuses to start. **The ranges are assigned —
  `docs/MIGRATION_NUMBER_RANGES.md`; use yours.** Read its §3 as well as its table:
  ranges stop two people writing the same number, and they *guarantee* migrations
  arriving out of order, which the committed Flyway settings refuse. The fix is a
  developer-machine one and it is written down there.
- **One branch per feature**, merged to `develop`, then to `main`.

### Stream 1 · Partner event API and integration breadth

The inbound path a customer's own system uses to reach the gate — the endpoints
they call, the queue those events land in, and the connector breadth to call back
out (SOAP, four authentication modes, per-connector certificate trust).

- **Owns:** `orca-runtime` → the `integration` module
- **Reference:** `docs/partner-event-api-from-1x.md` — **already written**, and its
  §0 lists seven defects in the old system that must not be repeated
- **Plan:** ✅ **`docs/stream-1-plan.md`** — work packages, the two-developer split,
  the verification table, and six questions that must be surfaced rather than settled
- **Can assume:** the engine, admission, connectors and the outbox all exist
- **Shape:** splits naturally in two — the partner-facing API surface and its
  dispatch queue (Track A), then the connector breadth (Track B). Large enough for
  two people; they share exactly one work package, and after it they touch different
  files

### Stream 2 · Read models and notifications

The two `orca-runtime` modules that are currently empty packages: pre-built
projections for the operator grids, and the hub that pushes live updates.

- **Owns:** `orca-runtime` → `readmodel` and `notify`
- **Reference:** ✅ **`docs/read-models-notify-from-1x.md`**, and its §0 lists six
  defects in the old system that must not be repeated. ⚠️ **Read its §5 before
  planning this stream** — how a live update reaches a browser across more than one
  instance with no broker is unsettled, and it is the decision the stream turns on
- **Can assume:** visits and work items exist and are stable
- **Plan:** ✅ **`docs/stream-2-plan.md`** — sequenced so the open question in its §5
  blocks only the last work package
- ⚠️ **`readmodel` is the one sanctioned exception to the module walls.** The lane
  monitor legitimately needs running visits *beside* queued work items — two
  modules' data. It must not read another module's tables; it maintains its own
  projection built from both. Getting this wrong is the most likely way this stream
  breaks the architecture rather than extending it
- **Note:** a build check currently asserts these two modules are **empty**. It has
  to be updated in the same commit that fills them; that is deliberate, so nobody
  fills them by accident. **This is also the stream that makes the module wall
  unconditional** — once both modules are populated, `allowEmptyShould(true)` comes
  out of `ModuleWallRule`

### Stream 3 · Core remainder

Custom entities with the single controlled executor of their schema changes, and
the licence-verification module.

- **Owns:** `orca-core`
- **Reference:** ✅ **`docs/custom-entities-from-1x.md`** — six inversions, and a
  vocabulary warning: the old system calls this *reference data*, so searching it for
  "custom entity" finds nothing
- **Plan:** ✅ **`docs/stream-3-plan.md`** — the DDL executor's design is written and
  handed over before it is built, so the other four work packages are never blocked
- **Can assume:** nothing from the other streams — **this is the cleanly parallel
  one.** Different service, different schema, no shared files
- ⚠️ **The DDL executor is security-shaped.** It is the one component allowed to
  change the schema at runtime. Its design is surfaced to the product owner, not
  settled by whoever implements it

### Stream 4 · Retention and purge

The jobs that make the retention classes real — every table that grows with traffic
actually being bounded.

- **Owns:** all schemas
- **Depends on:** streams 1 and 2, because it purges tables they create. **Do it
  last.**
- ⚠️ **It has a prerequisite that is not yet settled.** The architecture says the
  list of retention classes is closed at eighteen values with a database
  constraint; the Phase 1 hardening report says that is still blocked on
  reconciling two published copies of the list. Nine classes are declared in code
  today, all marked provisional. **Settle the list before this stream starts**, or
  it will encode the wrong one.

## 5 · After those four — and an honest statement about the word "complete"

When streams 1–4 land, a site can **run its gate end to end, configure itself,
route human work, integrate with a customer's systems, and stay bounded on disk.**
That is a real milestone.

It is often called "the on-site backend is complete", and that phrase needs care —
**some of what is missing is also backend.** Be precise about which, because plans
are built on this sentence.

**What still will not exist:**

| Missing | Consequence |
|---|---|
| **The workflow compiler and publish pipeline** | Administrators cannot design a process. The platform runs exactly one hand-written process definition. `POST /internal/deployments/v1` — the endpoint core pushes a published version to — appears in **zero** contracts today |
| **The frontend** | Two React applications, the kiosk, and both visual builders. Nothing can be shown to a customer |
| **The operator surfaces** | `/screens/submit`, take-by-lane, the grids and exports are named in the architecture and deliberately unbuilt — they wait on a console to consume them |
| **The deployment story** | Release images, a registry, secrets provisioning, a reverse proxy, licensing at install. `docs/deployment.md` Part 2 is the backlog, with an honest per-step status |
| **The cloud tier** | `orca-portal`, `orca-sync` and `orca-fleet` stay skeletons until cloud scope opens |
| **The verify-device-state branch** | The architecture guarantees that an unknown command outcome is resolved *by looking at the device*, never by retrying or assuming. Today that branch exists only as a comment — `device.state.unknown` is not routed anywhere in the shipped process, so in practice "resolve by looking" means "a person looks". A gate-path gap, and small |

**The frontend is the biggest of these by volume; the compiler is the one most
easily overlooked, and it is the one that changes what the product can claim.** One of
the two properties this platform exists for is *the site's processes belong to the
site* — administrators design them visually, without code. Until the compiler
exists, that property is unimplemented. It is not in any of the four streams
because it needs the developer building the visual builder, working from
`docs/BPMN_EXECUTION_PROFILE.md` — which is still marked *proposed* and has never
been reviewed by them.

None of this is a reason to delay the four streams. All of it is a reason not to
let "backend complete" be read as "product complete" in any plan or estimate.

## 6 · What is deliberately not being started, and why

Not forgotten. Each is a decision with a reason.

- **The cloud tier** — the programme is on-site first, by product-owner direction.
- **Migration from the old system** — there is none, ever. The product ships to new
  clients only. If you find a document implying a cutover, it is out of date.
- **A second database engine** — SQL Server only. The seam that would allow another
  is kept; no second implementation is built.
- **A message broker** — work moves between services through the database, with a
  transactional outbox. This is a decision with a stated cost, not an omission.
- **Rewriting the media service** — inherited and left alone. Real-time media is a
  specialism and the existing relay works.

## 7 · How you know a piece is finished

Not when the code is written. When:

1. `./gradlew check integrationTest` is green — **224 integration tests** today, and
   yours have joined them. Run it with `--rerun-tasks`: without it Gradle answers
   from cache in under a second and reports a success it did not run.
2. **Every guarantee you touched has a property test** that would fail if the
   guarantee broke. Not a test that exercises the path — one that states the claim.
3. **The services start and a truck goes through the gate.** A green suite does not
   prove a service boots; every suite builds its beans directly. This repository has
   shipped a service that passed everything and could not start.
4. **A short report exists** — what you built, what you could not, every decision
   the plan did not dictate, and anything that looked wrong.

## 8 · Where to look next

| For | Read |
|---|---|
| How to work here, and the rules that fail the build | `docs/DEVELOPER_ONBOARDING.md` |
| Setting up and running locally | `docs/LOCAL_DEVELOPMENT.md` |
| The target design and its guarantees | `docs/ORCA_ARCHITECTURE.md` (§B10 first) |
| What is deliberately still undecided | `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` |
| What the old system really does | `docs/*-from-1x.md` |
| What each finished phase built, and what it did not | `docs/phase-*-report.md` |
| Your own stream | Its plan, which will be given to you |

---

*This document is kept current as streams land. If it disagrees with what you find
in the code, the code is right and this is a defect — say so.*
