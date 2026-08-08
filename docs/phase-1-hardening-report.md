# ORCA Phase 1 Hardening — Build Report

**Six bounded work packages, one commit each · August 2026**
**Companions: `docs/phase-1-report.md` (what this hardens), `docs/phase-1-plan.md` §2 (the rules it ran under)**

Same shape as the phase reports: what was built, what was not, every decision the
brief did not dictate, and everything that proved wrong — **reported, not silently
corrected.**

---

## 1 · Headline

**All six packages landed. Nothing was stopped and nothing was descoped.**

| | |
|---|---|
| `./gradlew check` | **Green.** 60 unit tests and build-check assertions |
| `./gradlew integrationTest` | **Green. 138 property tests across 18 suites**, up from 113 across 15 |
| Build checks | **Seven → ten**, each new one watched to fail before it was committed |
| The demo | **Re-run end to end**, on the corrected device-host contract — transcript in `phase-1-demo.md` §11 |

**The two things worth reading if you read nothing else:**

1. **The device-host contract this repository shipped in Phase 1 was invented, and
   it was wrong in every particular** — the route, the body, how a device is
   addressed, and the success rule. H1 replaced it with what the fielded 1.x caller
   actually sends. §H1.
2. **H3's own test found a two-hour clock error** that would have made a healthy
   lane read as a nine-hour outage. It was not in the new code; it was in how edge
   had been reading database-written timestamps since WP5. §H3.

---

## 2 · What was built, package by package

### H1 · The device-host contract was a guess, and the guess was wrong

`docs/device-host-outbound-from-1x.md` — extracted from the ORCA 1.x production
caller — was committed with the correction it forced.

**What WP7 shipped:** one `POST /api/v1/commands` carrying
`{commandId, deviceExternalId, action, params}`, success on any 2xx whose body
contained `"status"` and `"OK"`. Every field name was chosen in this repository.
`phase-1-report.md` §3 said so plainly, and said a green demo proved the plumbing
and nothing about the vendor.

**What actually commands barriers today:**

| Command | Request | Body |
|---|---|---|
| Gate | `POST {host}/api/{device}/raiseGate` · `/lowerGate` | **empty**, with `Content-Type: application/json` |
| Print | `POST {host}/api/{device}/print/{format}` | **the file's raw bytes**, and **no `Content-Type`** |
| IO | `POST {host}/api/io/{device}/{port}/{true\|false}?ioPortName=…` | **empty** |

**The `/api/io/` asymmetry is faithful, not a typo**, and reproducing it rather than
tidying it is the whole discipline: the other side of this contract is a fielded
component.

Success is **HTTP 200 — not 2xx — and a body that decodes** as
`{status, code, message, request_id, timestamp}`. That is §C3's *"an acknowledgement
**and** a body that decodes"* already fielded in 1.x, which is worth saying out loud:
the 2.0 outcome vocabulary maps onto the estate without interpretation.

`RestDeviceHost` was rewritten onto the JDK's HTTP client directly rather than
`RestClient`, because **Spring's `byte[]` converter adds a `Content-Type` and 1.x
deliberately omits one** — see §4.1. `deploy/stubs/device-host/` now has three
mappings speaking the same routes, and both READMEs, the compose file and `.env.example`
say DERIVED-FROM-1X instead of PROVISIONAL.

**`DeviceHostWireIT` asserts the bytes against a real socket** — method, path, query
string, headers, body — rather than against the class's own abstractions. That shape
is deliberate: the suite it replaces agreed with the code about a dialect neither had
any evidence for, and a test that calls `RestDeviceHost` and checks its return value
passes just as happily on the wrong URL.

**Two things the extraction does not settle, both on the register rather than in code:**

- **NEW-4 — the `Authorization` header.** 1.x mints a Keycloak token per device-host
  command and presents it as `Bearer`. Whether the .NET host *validates* it cannot be
  determined from this repository, and the two answers have opposite consequences: if
  it is ignored the header is cargo; if it is enforced, **commanding a barrier requires
  the identity provider**, which collides with ADR-011 on the gate path and with §A1's
  whole premise. A second consequence is worth confirming for its own sake: if the host
  enforces it, **1.x cannot open a gate today while Keycloak is unreachable.** No header
  is sent; the slot is `RestDeviceHost.openQuestionAuthorization(…)` with a test
  asserting its absence, so filling it in cannot be a quiet commit.
- **NEW-5 — `PTZ_PRESET` has no route anywhere in the extraction.** §C3 names five
  actions and the 1.x caller has three calls. Edge **refuses** the command with the
  reason in the outcome an operator reads. Demonstrated live in §3 of the demo
  transcript.

### H2 · Three build checks, each watched to fail

`build-checks/AGENTS.md` makes that the bar, and this repository's own history is the
argument: `PlatformPurityRule` passed vacuously for an entire phase because its word
matcher required a non-letter after the match, so `VisitResponse` — the single most
likely violation there is — did not match.

| Rule | Governs | The deliberate violation that stopped the build |
|---|---|---|
| `ScopeIndexRule` | Every table declaring `site_external_id` has **some index leading with it** | A table whose only index led with `lane_external_id` |
| `ContractInterfaceRule` | Every `@RestController` implements an interface from a generated `*.api.generated` package | A `@RestController` with a route that exists in Java and in no contract |
| `InternalSurfaceRule` | Internal tag ⇒ internal path, internal path ⇒ internal tag, and no third surface | An operation tagged `internalProbe` authored at `/probe/v1`, **and its mirror image** at `/internal/probe/v1` tagged `probe` — one edit, both directions |

Each violation's message is quoted in the commit. Each was reverted and the ten rules
are green.

**`ScopeIndexRule` closes the trap `phase-1-report.md` §5.13 flagged and could not
close.** Every seam read leads with the scope predicate, so the seam decides the
leading column of every query shape in the product. A scoped table with no index
leading with that column can only be **scanned**, and a scan under the `UPDLOCK` the
lane lock provides takes an update lock on **every lane at the site** — which is what
deadlocked WP6's eight-lane run until `lane_session`'s key was widened. Nothing in
Java can see an index, so the rule reads the migrations.

⚠️ **It is necessary, not sufficient, and says so.** No build check can read a query
plan; the optimizer may still scan a small table. What the rule removes is the case
where a seek was never available at all — the case that produced the deadlock, and the
one a migration author can be held to.

**`ContractInterfaceRule` closes an enforcement gap `ai-context-report.md` §7.1 named.**
The brief attributed *"controllers implement generated interfaces"* to
`ErrorEnvelopeRule`; that check inspects return types and nothing anywhere asserted the
`implements`. The practice held by convention plus the compiler, and nothing stopped a
*new* controller from implementing nothing and passing every check.

**`InternalSurfaceRule` takes its path pattern from `InternalCallProperties` rather
than copying it**, so the check cannot drift from the ADR-011 filter it guards — which
is the entire failure mode it is named for.

**Two of the three read files rather than bytecode**, so both assert a floor on what
they read. `ImportedSetGuard` records that too, and now counts every `@RestController`
rather than the six health controllers by name.

### H3 · `/internal/buffer/stats`, and a two-hour clock error

§C3 names the endpoint; Phase 1 did not build it, so a `DEAD` event — the one event a
site operator most needs to see — was visible only to somebody with a database login.

Contract-first: tag `internalBuffer`, generated interface, hand-written controller,
scope from configuration and never from the request. Three numbers per lane and one
flag, and **each is there because of a pair of readings that are indistinguishable
without it**:

| | |
|---|---|
| `depth` vs `oldestUndeliveredAgeSeconds` | A depth of 4 that is nine hours old is a severed link; a depth of 400 that is four seconds old is a busy morning. They sort identically on depth |
| reported vs omitted | Every lane the site publishes appears, empty or not. A missing lane and an empty lane are different answers to *"why did nothing happen"* |
| `ownedByThisInstance` | §C3 gives one instance a lane at a time, so the same backlog means "we are draining this" on one host and "nothing here is draining it" on another |

No aggregate was added to the seam. The oldest event is a single-row read ordered by
`sequence_no`, which the `(site, lane, sequence)` index already serves — ordered by
sequence and not by clock, because two captures in one millisecond still have an order.

#### ⚠️ What the test found, and it was not in the new code

The age assertion failed reporting an event buffered one second earlier as **7,200
seconds old** — exactly this machine's UTC offset.

`java.sql.Timestamp` carries no zone. `rs.getTimestamp(column)` reads a `DATETIME2` as
wall-clock time **in the JVM's default zone**, and `Timestamp.from(instant)` writes the
same way. That round-trips correctly for a value Java both wrote and read, and is wrong
by the machine's offset for every value the **database** wrote —
`event_buffer.received_at` defaults to `SYSUTCDATETIME()`.

**An operator would have read "the oldest event here is two hours old" and gone looking
at a healthy network.** Fixed in edge's persistence with an explicit UTC conversion in
both directions (`Utc`), so the answer does not depend on where the appliance is
installed.

**The same pattern is one primitive away and is reported rather than swept** — see §5.1.

### H4 · The path off `database-schema-update: true`

`phase-1-report.md` §7.4: *"There is no migration path off
`database-schema-update: true`, and the demo proved it by tripping over it."*

`deploy/adopt-flowable/` is that clearing as a procedure, `docs/flowable-adoption.md`
is the document, and `FlowableAdoptionIT` runs **the committed script itself** rather
than a copy of its statements — a test that reimplements the procedure proves the test
works.

It adopts **by rebuild**: verify, drop, let Flyway build. No schema-history surgery, no
hand-written checksums, nothing depending on a Flyway internal. It runs as
`orca_runtime` and **not** as `sa`, working on that login's default schema, so the
database itself is what stops it reaching a schema that is not runtime's (ADR-004). The
script drops tables; that confinement is the point.

Five properties, all executed:

1. Flyway **does** refuse an engine-created schema, naming `ACT_GE_PROPERTY`. If that
   ever stops being true the procedure is solving nothing and should be deleted rather
   than kept as reassurance.
2. After adoption, Flyway migrates cleanly, the five migrations are recorded, and the
   **engine boots** against a schema it did not create.
3. **It refuses a schema holding process data, and the schema is unchanged afterwards.**
4. It refuses a schema built by a different Flowable version.
5. It is a no-op where none is needed, so running it in doubt cannot hurt.

Property 3 is the reason the script exists in this shape. Adopting by rebuild destroys
running visits and the audit trail behind completed ones, and a gate with a truck
mid-visit is exactly the installation somebody runs this on in a hurry.

**⚠️ Not built: adoption with process data present.** §4 of `flowable-adoption.md`
states what it would need and why. The reason is not effort — it is that **no
installation can be in that state**, so a data-preserving procedure written today could
be tested only against a situation its own test constructed. That is the shape of
procedure discovered to be wrong at the moment it is first needed, on a gate, with a
queue outside. It becomes necessary the day a Phase 0 build carries real traffic.

### H5 · The seam learns to add, and the buffer stops losing failures

`phase-1-report.md` §3 recorded this as a small gap: `ScopedUpdate` could not express
`attempts = attempts + 1`, so `EventBufferRepository` read every row and wrote it back.

**It was not only slower.** Two deliveries that both read `attempts = 3` and both write
`4` record **one** failure between them, so an event that has failed twice as often as
its counter says is retired later than the limit promises — and nothing in either
transaction could notice.

`ScopedUpdate.increment(column, delta)` emits `column = column + ?`. The delta is bound;
the column goes through the same identifier allow-list as every other column. **There is
no general "set this column to this fragment", and that is the design**: a hole of that
shape in the one place the seam exists to keep caller-authored SQL out of would cost
more than every expression it could ever carry. A second form arrives as a second named
method with its own property test, or it does not arrive. Setting and incrementing the
same column throws before any SQL exists.

**The property test runs both forms under the same eight-thread contention**: the
increment lands 200 of 200, the read-write-back does not. The comparison is in the test
deliberately — *"the increment is atomic"* asserted alone is an assertion about nothing.

`recordFailure` is now three set-based statements where it was one update plus N reads
plus N writes: increment, retire the rows the new count has exhausted, requeue the rest.
Three rather than one because the new status depends on the new count, and a `CASE` over
it would be exactly the caller-authored SQL the seam refuses.

### H6 · Housekeeping

`REPOSITORY_GUIDE.md` §1's header said *"For review before Phase 0 starts"* and its tree
listed five build checks, no `integrationTest` source sets, no `processes/`, no phase
documents, and a `deploy/` with two entries. Both are current, and so is everything the
header made inconsistent: the checks table (ten rules and the guard, with when each
arrived), §3's folder list, §4's commands, §6 (*"what Phase 0 delivers"* → what exists
today), and §7's review questions, which are now marked historical with where each one
went.

---

## 3 · Verification — every command executed

| # | Run | Result |
|---|---|---|
| 1 | `./gradlew check` | **Pass.** 60 unit tests and build-check assertions |
| 2 | `./gradlew check integrationTest` | **Pass. 138/138** across 18 suites — re-run from `clean --rerun-tasks` during the QA pass |
| 3 | Deliberately violate `ScopeIndexRule` | **Build stopped**, naming the table, its key and what its indexes lead with. Reverted |
| 4 | Deliberately violate `ContractInterfaceRule` | **Build stopped**, naming the controller. Reverted |
| 5 | Deliberately violate `InternalSurfaceRule`, both directions | **Build stopped** with three violations from one edit. Reverted |
| 6 | `FlowableAdoptionIT` | **Pass, 5/5**, including the refusal that leaves the schema intact |
| 7 | Full runtime `integrationTest` after H4 | **Pass, 42/42** — including the three suites that share the `runtime` schema H4's suite rebuilds |
| 8 | The demo, from `docker compose up` | **Pass, re-run end to end.** Plate read → ACK → visit `COMPLETED` → `RAISE_GATE` `EXECUTED` → outbox row. Transcript in `phase-1-demo.md` §11 |
| 9 | The device host's own record of what arrived | **`POST /api/DEV-DEMO-BARRIER/raiseGate`, empty body, `Content-Type: application/json`, no `Authorization`** |
| 10 | `GET /internal/buffer/stats` against the live stack | **Pass.** One lane, `ownedByThisInstance: true`, depth 0, and the age fields **absent rather than zero** |
| 11 | Expired command through the live endpoint | **`FAILED`, "Nothing was sent"**, and `0` device-host calls mentioning it |
| 12 | `PTZ_PRESET` through the live endpoint | **`FAILED`**, naming the missing route. Nothing sent |

**`deploy/bootstrap/verify-isolation.sh` was initially declared unrun** (no schema,
login or grant changed in any package). The QA pass ran it anyway: **36/36**, the
declaration was correct, and the run replaces the declaration.

---

## 4 · Decisions this brief did not dictate

### H1

1. **`RestDeviceHost` was rewritten onto the JDK's `HttpClient`, dropping `RestClient`.**
   The reason is one requirement: **print sends no `Content-Type`**, and Spring's
   `ByteArrayHttpMessageConverter` supplies `application/octet-stream` when none is set.
   Fighting a converter to *not* send a header is worse than not using one. The test
   asserts the header is absent, so the decision has a failing case attached.
2. **A connect timeout is `FAILED`; a response timeout is `UNKNOWN`.** The JDK makes
   `HttpConnectTimeoutException` a subtype of `HttpTimeoutException` and the two mean
   opposite things here: a connection never made means the command never left, which is
   knowable. The previous cause-walking blurred them.
3. **"Decodes" means *a JSON object*, faithful to 1.x including its looseness.** Go's
   decoder accepts any JSON object into that five-field struct — `{}` included — and
   rejects an array, a scalar or anything that is not JSON. Requiring the five fields
   would be **stricter than the fielded caller** and would risk reporting `FAILED` for a
   barrier that rose, which is the exact hazard §B10 exists to prevent. ⚠️ **The cost is
   stated rather than fixed:** a proxy answering `200 {"error":…}` reads as an execution
   here, as it does in 1.x today.
4. **The `params` key names are `CHOSEN-HERE`, not derived.** The extraction records the
   wire, not the upstream message 1.x built it from. `format`/`content` for print and
   `ioPort`/`state`/`ioPortName` for IO are this repository's and can be changed; the
   routes they feed cannot. Marked in source.
5. **The IO call sends no `Content-Type`.** The document records the header for gate and
   its deliberate absence for print, and says nothing about IO. Asserting
   `application/json` because the gate call sends it would be reasoning about a vendor's
   component from a neighbour. Marked `CHOSEN-HERE`, and it is a coin-flip worth naming.
6. **The print `encoding` field is not implemented.** Its semantics are not recoverable
   from the extraction; `content` is read as base64 and nothing else. Folded into NEW-5.
7. **`deviceExternalId` became required on `/internal/commands/v1`.** Not a preference —
   the device is addressed **in the URL**, so a command that names none cannot be sent.
   The old contract's wording (*"a lane with one barrier does not need to say which
   barrier"*) was true only of the invented body-carrying shape. It is carried as
   `commandDeviceExternalId` through the process, with the same slice-shaped
   configuration caveat as `commandAction`, and `VisitLifecycleIT` asserts it is on the
   wire so it cannot be quietly dropped again.

### H2

8. **`ScopeIndexRule` accepts *any* index leading with the scope column** — the key, a
   unique index, or an ordinary one. Requiring the primary key would fail
   `event_buffer`, `execution`, `command_log`, `execution_event` and `device_state`,
   which are all correct: they carry an identity key and a covering secondary index that
   leads with the scope column.
9. **The scope dimension is named in the rule, and there is one.**
   `SCOPE_COLUMNS = ["site_external_id"]`. A second dimension must be added by hand, and
   **nothing automated will say so** — a dimension living only inside a `Scope.of(…)`
   call is not discoverable from a migration. The limit is recorded in the rule rather
   than hidden.
10. **`InternalSurfaceRule` enforces both directions plus "no third surface".** The
    reverse direction (a path under `/internal/` with no internal tag) is the same defect
    read the other way: the tag names the surface in the served document and decides the
    generated interface's name.
11. **Two rules read files, and `RepositoryFiles` finds the repository root by walking up
    for `settings.gradle.kts`** rather than trusting the working directory — because a
    rule that silently reads nothing is the failure mode this module exists to prevent.

### H3

12. **`ownedByThisInstance` is on the response.** Beyond a literal reading of §C3, and
    the justification is that without it the same numbers mean two different things
    depending on which host was called, with nothing saying which.
13. **The answer is explicitly not a consistent snapshot.** Lanes are read one at a time,
    outside a transaction. Holding a read transaction over every lane's buffer to make a
    number tidy would put a diagnostic endpoint in the path of the thing it diagnoses.
    `observedAt` is on the response so the reader knows what they have.
14. **An empty lane reports no `oldestUndeliveredAt` rather than an age of zero.** Those
    are different facts, and zero reads as *"something arrived just now"*.
15. **The UTC fix was applied to edge's two repositories only**, not repo-wide. See §5.1.

### H4

16. **Adoption is a `deploy/` script, not application code.** Two reasons: a startup path
    that can rewrite schema history is not something a service should carry, and
    `ScopeSeamRule` forbids a service class from touching a `DataSource` at all — so the
    component could not have lived in `src/main` without an exemption that would have
    been worse than the problem.
17. **It runs as `orca_runtime` and works on `SCHEMA_NAME()`.** The confinement is the
    database's, not the script's.
18. **The version guard reads `ACT_GE_PROPERTY.common.schema.version`** — the engine's own
    marker — rather than diffing the schema. Tables built by Flowable 8.0.0 *are* what
    8.0.0's create scripts build; that is what the marker means.

### H5

19. **A negative delta decrements, and nothing bounds the result.** A counter that must
    not go below zero says so in its own `where`: the seam does not know what a column
    means.
20. **`recordFailure` is three statements rather than a `CASE`.** Stated above; the
    alternative is caller-authored SQL in the one place the seam refuses it.

---

## 5 · Found wrong, or unfinished — reported, not corrected

### 5.1 · ⚠️ The zone-less timestamp conversion exists in two primitives, and is not fixed

`platform/outbox`'s `created_at` and `platform/lease`'s `expires_at` are
database-written and read through the same zone-less `rs.getTimestamp(…)` path H3
fixed in edge.

**Neither is a live defect today**, and the reason is worth stating precisely: the
lease and the relay do every time **comparison inside SQL** against
`SYSUTCDATETIME()` and never against a JVM clock — `JdbcLeaseManager`'s Javadoc calls
that out deliberately. So the skew is confined to values they merely *report*: a
`Lease.expiresAt` handed back to a caller is wrong by the machine's UTC offset, and
nothing reads it for a decision.

**It is still the same trap, one primitive away, and it is now the only place in the
repository where it survives.** The moment anything compares one of those Instants to
`Instant.now()` it becomes the H3 defect with a lease in place of a diagnostic. Not
fixed here because it is a platform change with property tests to re-prove and it is
outside the six packages; named so it is not rediscovered.

### 5.2 · ⚠️ The CI workflow has never run, and where this is hosted is unowned

**Recorded, not acted on, as instructed.**

`.github/workflows/ci.yml` describes build → unit tests → integration tests on
Testcontainers → build checks → coverage, and says a pull request cannot merge with
any of them failing. **This repository has no git remote.** `git remote -v` is empty,
so that workflow has never executed once, and **every result quoted anywhere in this
corpus — Phase 0's, Phase 1's and this document's — was produced by running the
commands locally.**

Three consequences, none of them urgent and all of them cheap now:

- **"CI" is a file, not a gate.** The merge protection it describes protects nothing,
  because there is nowhere to open a pull request.
- **The workflow is unverified.** A YAML error, a missing action version, a
  Testcontainers-in-CI problem — none of it can be known until the first run, and the
  first run will be the day the repository is pushed, which is not a good day to
  discover it.
- **There is one copy of this work.** Six commits of hardening on one laptop.

**Where it is hosted is the product owner's decision** — and it carries a real
question rather than a preference, since the corpus references a private
`Lynxis-Gate` estate the extractions came from. Surfaced, not settled.

### 5.3 · The `RepositoryGuide` was inconsistent in ways beyond the two named

H6's brief named the header and the tree. Bringing those current made three further
sections contradict themselves, and all three were fixed: §4 still said *"the seven
build checks"*, §6 was written in the future tense about a Phase 0 that has since
happened, and §7's review questions were pre-Phase-0 with no record of where their
answers went. `ai-context-report.md` §7.2 had already flagged the check-count
contradiction; it is closed.

### 5.4 · `DeviceCommandPropertiesIT` keeps two tests the wire suite now covers better

`aRefusingDeviceHostProducesFailed` and `anUndecodableAnswerIsNotAnExecution` were
written against the invented route and now overlap `DeviceHostWireIT`. They were
repointed rather than deleted: the duplication is small, and deleting a passing
property test to reduce a count is the wrong instinct. Named so the next reader knows
it is deliberate.

### 5.5 · What §C3 names and this hardening still did not build

Unchanged from `phase-1-report.md` §3 except where noted, and none of it was in scope:

- **`edge.device_suppression`** — named, unread.
- **The verify-device-state branch.** `device.state.unknown` routes to a human;
  §B10's *"resolve by looking"* still means *"a person looks"*.
- **A consumer of `visit.completed`.** Still none, still deliberate.
- **The unmatched-event operator surface.** Phase 2's. ⚠️ **H3 narrowed this**: a `DEAD`
  event is now visible on an endpoint rather than only in a table, which is the
  operator-blindness half. The *surface that lets somebody act on it* is still absent.
- **`/internal/commands/v1` still carries no issued-at**, so edge measures the deadline
  from arrival. Unchanged, and `phase-1-report.md` §5.11 still describes it correctly.
- **The 18-value retention-class `CHECK`.** Still blocked on reconciling two published
  copies of the list.

---

## 6 · What the next session should pick up

1. **NEW-4 needs a ruling before any real device host.** It is the one item here that
   blocks a deployment rather than a phase, and only the vendor or a test against a
   real host can answer it.
2. **Decide where this repository is hosted** (§5.2). Cheap now; it is the only thing
   standing between the workflow and its first execution.
3. **The zone-less conversion in `outbox` and `lease`** (§5.1) — a small platform change
   with property tests to re-prove.
4. **NEW-5**: what a `PTZ_PRESET` addresses, and what the print call's `encoding` field
   means. One vendor conversation answers it together with NEW-4.
5. **`docs/BPMN_EXECUTION_PROFILE.md` is still PROPOSED** and still waiting on the
   builder developer. H1 added `commandDeviceExternalId` to it, which is a change they
   need to see.
6. **Who owns `platform/`** (`REPOSITORY_GUIDE.md` §7.4) — the one question from that
   list with no proxy anywhere else.

---

## 7 · The review-and-QA pass, before handover

A dedicated pass after the six commits: a code review over the full diff hunting for
defects rather than confirming intent, then verification that re-ran everything from a
forced clean build and probed the parts the hardening did **not** touch.

### 7.1 · Four findings, all fixed, two of them with tests attached

1. **`RestDeviceHost` parsed with Jackson 2, which reaches edge only as Flyway's
   transitive dependency.** The rest of the service speaks Jackson 3 (`tools.jackson`
   — Boot 4's serving stack), so the code that decides whether a barrier moved was
   leaning on another library's baggage: drop Flyway's Jackson 2 need in some future
   upgrade and the barrier path stops compiling. Ported to Jackson 3; behaviour
   identical; `DeviceHostWireIT`'s nine wire tests cover it.
2. **A JSON-array `params` document was silently read as empty**, so a malformed
   `SET_IO` was refused with *"carries no 'ioPort'"* — blaming a missing field for
   what is actually a malformed document — while non-JSON was refused with the right
   message. Both now refuse identically, verified live against the running service.
3. **`DeliveryPump.BufferStats` was dead code with a false Javadoc** — it claimed to
   be *"what `/internal/buffer/stats` reports"*, was never wired to anything, and H3
   built the real shape elsewhere. Two records claiming to be the endpoint's answer,
   one of them a lie to the next reader. Deleted.
4. **The adoption script had an unrecognisable state it walked past**: engine tables
   present but `ACT_GE_PROPERTY` absent (a hand-cleared machine can be in it) read as
   *"nothing to adopt"*, and the next start failed later in Flyway instead. A script
   that drops tables now **refuses** what it cannot recognise. New test in
   `FlowableAdoptionIT` (6 properties now).

**One incidental fix found by reading, kept and pinned by a test:** the old
`ScopedUpdate` went through `Map.copyOf`, which rejects null *values* — so
`set(column, null)` threw `NullPointerException` at execution, and a real caller hits
it (the pump records `exception.getMessage()`, which can be null). H5's assignment
rework fixed this without noticing; `ScopeWritePropertiesIT` now asserts a null SET
lands as `NULL`, so the fix cannot silently regress.

### 7.2 · What QA ran, beyond the phase's own verification

| Run | Result |
|---|---|
| `./gradlew clean check --rerun-tasks` | Green — nothing was riding an up-to-date check |
| `./gradlew integrationTest`, all 18 suites | **138/138** |
| `deploy/bootstrap/verify-isolation.sh` | **36/36** — closing the one declared-unrun item |
| **All six services booted**, including the three the hardening never touched | `core` `runtime` `edge` `portal` `sync` `fleet` — all healthy. This is the regression this repository has actually shipped once (an edge that passed every check and could not boot), probed on the services most likely to break silently |
| One truck end to end on the reviewed code | `COMPLETED`, `RAISE_GATE` `EXECUTED` — the Jackson 3 decode exercised against a real answer |
| `SET_IO` against the refusing stub | `POST /api/io/DEV-DEMO-BARRIER/3/true?ioPortName=loop+A`, empty body, on the wire verbatim; 503 → `FAILED` |
| Array `params` against the live endpoint | Refused before the socket, with the corrected message |
| `/internal/buffer/stats` live | Healthy lane, owned, age fields absent-not-zero |

---

*Six packages, six commits, one report. Everything above was executed against this
repository while it was written.*
