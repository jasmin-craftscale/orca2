# Stream 1 — Implementation Plan: the partner event API and integration breadth

**For the two developers building stream 1, and the AI each of them drives ·
August 2026 · Companion report: `docs/stream-1-report.md`**

Self-contained. Where it points at another document, read that document before
building the thing it describes.

**This stream is large enough for two people and splits cleanly in half.** Track A is
the partner-facing surface and its queue; Track B is the connector breadth. §3 names
the one thing Track A must land before Track B needs anything from it — after that the
two run independently, in different files.

---

## Read first, in this order

1. **`AGENTS.md` (root) and `services/orca-runtime/AGENTS.md`** — the operating rules:
   contract-first, the scope seam, the ten build checks, tests-prove-properties, the
   definition of done. Enforced, not advisory.
2. **`docs/CODE_PATTERNS.md`** — the shape a change takes here. §2 (*never check, then
   act*) and §4 (*the shape of a migration*) are the two you will use every day.
3. **`docs/MIGRATION_NUMBER_RANGES.md`** — **your band is `V118–V137` in the `runtime`
   schema.** Read §3 as well as the table: another stream's lower-numbered migration
   will land after yours, and the committed Flyway settings refuse it by default. That
   is expected and the fix is written down.
4. **This plan, in full.**
5. **`docs/partner-event-api-from-1x.md`** — the DERIVED-FROM-1X reference. **Read its
   §0 first: the seven inversions.** They are this stream's acceptance criteria. The
   data is 1.x's; the behaviour is the architecture's.
6. **`docs/ORCA_ARCHITECTURE.md`** — §C2 (orca-runtime and its modules; the integration
   seam), §B4 (how services talk), §B6 (authentication — read the paragraph on who
   carries a Keycloak token), §B10 (the guarantees and how each is verified), §D2 (the
   four contracts fixed by the other side — **outbound connector semantics is one of
   them, and it is Track B's specification**).
7. **`docs/ORCA_OPEN_QUESTIONS_REGISTER.md`** — **U2** (this surface is free to
   redesign, and how narrowly), **item 6** (the lost-race response), **U3** (the realm
   and client structure, which is where partner credentials live and is open).
8. **`docs/BPMN_EXECUTION_PROFILE.md`** — only if you touch §3's `/callback` question.

**Mirror the existing code — it is your template.** Do not invent shapes this codebase
already has:

| For | Study |
|---|---|
| An inbound HTTP entry point that admits events | `execution/api/DeviceEventController.java` — **including where its scope comes from** |
| A durable queue with claim, retry, and a visible dead state | `orca-edge`'s `EventBufferRepository` + `DeliveryPump` — this is the pattern, not `platform/outbox` (§3, A2) |
| A cross-module call | `execution/api/ManualStepPort.java` — published by the callee, consumed by the caller |
| An error taxonomy | `workitem/api/WorkItemErrorCode.java` — each constant's javadoc says *why it is distinct from its neighbour* |
| A migration | `V115__work_items.sql` (growth, retention class, scope-leading index, binary-collated enum CHECK) |
| A property test | `AdmissionPropertiesIT`, `WorkItemLifecycleIT` |

---

## 1 · What this stream delivers

**The two-way door to a customer's own systems.**

*Inbound (Track A):* a customer's TOS or WMS submits an event; it is recorded, resolved
to a lane, and admitted through **the same admission that a camera uses**; the partner
can inspect, replay and report on what they sent; and the platform closes out its own
queue rows rather than waiting for the partner to do it.

*Outbound (Track B):* a connector can speak **SOAP as well as REST**, authenticate in
**four modes**, and trust **a per-connector certificate** — the shape §D2 fixes because
the other end is the customer's own system.

**What it explicitly does not deliver:**

- **No console.** The frontend is unstaffed. Everything here is an API with tests;
  nothing is demonstrated through a screen.
- **No notifications and no grids** — stream 2 owns `readmodel` and `notify`. If you
  want a projection, you want stream 2; ask.
- **No retention *jobs*.** You declare your tables' growth and retention class; stream 4
  builds the purge. Declaring is not optional — the build check refuses the table
  without it.
- **No mutual TLS.** §D2 is explicit: the per-connector option is one-way, verifying the
  *server*. A customer requiring mTLS is added work, not configuration.
- **No new process design.** You do not author BPMN. See §3's `/callback` question.

## 2 · Ground rules

| Rule | Why |
|---|---|
| **Never invent a resolution to an open question.** Unspecified → check the register → still unspecified → **report the gap**. §5 lists this stream's known-open ones | The programme's most-repeated failure has been filling a gap with something plausible and writing it up as settled |
| **1.x is the reference for the DATA; the architecture governs the BEHAVIOUR.** The sheet's §0 names seven places 2.0 deliberately inverts 1.x — build the inversion and prove it | Porting the 1.x flow reproduces the defects the extraction exists to name |
| **Scope comes from configuration, never from the request.** A partner does not name a site. Read `DeviceEventController`'s class javadoc before you write your first controller | A site identifier on the wire is a value the caller chooses. On an on-site installation there is exactly one primary site, and it is the one the licence binds to |
| **Every table lands with its feature surface in one work package**: migration + seam repository + endpoints + property tests | A table nothing reads is drift |
| **Use your migration band, `V118–V137`** | `docs/MIGRATION_NUMBER_RANGES.md` |
| **Tests prove properties, not paths.** The load-bearing ones here are *one event, one admission* under concurrency, and *a claimed row is never stranded* | "The row is inserted" is not a test |
| **Anything security-shaped is PROPOSE-and-report, never implement** | §5 |

## 3 · Work packages

```
Track A ── A1 ─→ A2 ─→ A3 ─→ A4 ─→ A5      A1 is the only shared dependency:
                └────────────→ (B needs nothing from A after A1)
Track B ── B1 ─→ B2 ─→ B3
```

⚠️ **A5 carries one item that is not last: the event-type registry belongs with A3.**
The rest of A5 can close out the stream.

**A1 first, by whoever starts first.** After it, the two tracks touch different files.

---

### Track A — the inbound path

#### A1 · The admission seam · *the piece that makes the rest legal*

`integration` may not reach `execution`'s `domain` or `persistence` — `ModuleWallRule`
enforces it, with three tests. So the partner path cannot call `AdmissionService`
directly. Publish an interface in **`execution.api`**, implemented over
`AdmissionService`, and consume it from `integration`. `ManualStepPort` is the worked
example of exactly this shape.

**This is not a formality, and here is the evidence.** `AdmissionService`'s own
javadoc says the filtered unique index on `execution(lane_id) WHERE status='ACTIVE'`
exists to hold *"when a future inbound path forgets the lock"*, and its
`DuplicateKeyException` branch logs *"Some inbound path admitted without the lane
lock"*. **You are that future inbound path.** Going through the port means you take
the lane lock and inherit correlate-or-start; going around it means two partner events
on one lane can start two visits, which is the property the whole design turns on.
Architecture §C2: *every inbound path — camera, device host, partner API, portal —
goes through the same one.*

Two details that are easy to get wrong:

- **Give partner events their own idempotency operation namespace.**
  `AdmissionService.OPERATION` is `"device-event"`. A partner's event id and a camera's
  `EventGuid` are different vocabularies from different producers; sharing a namespace
  means one can silently answer for the other.
- **Return the outcome, do not flatten it.** `EventOutcome.Status` already models
  `STARTED | CORRELATED | DUPLICATE | IN_PROGRESS`, and every one of those is a real
  answer a partner needs. `DUPLICATE` is a success carrying the original result;
  `IN_PROGRESS` is not terminal and not a failure. The `/internal/events/v1` contract
  documents each — copy that wording rather than reinventing it.

**Done when** `integration` admits an event through the port with no build-check
suppression, and a property test proves two concurrent partner submits for one lane
produce **exactly one** visit — the `AdmissionPropertiesIT` shape, at least 200
iterations.

#### A2 · The dispatch queue · *inversions 1, 2, 4, 5, 6, 7*

One table, in your band. **Six of the seven inversions are this work package.**

| 1.x defect | What you build |
|---|---|
| **1** — dedup is read-then-insert, so two retries both insert | The **idempotency primitive**: `begin(key, operation, holder)` claimed atomically, replay returns the recorded outcome |
| **2** — two services write two different status vocabularies, neither able to transition the other's rows | **One owner, one enumeration, one place it changes.** A binary-collated `CHECK` (`COLLATE Latin1_General_100_BIN2`) — the database is case-insensitive and a bare CHECK accepts `'pending'` |
| **4** — no tenant column, no soft delete, hard-deleted rows | `site_external_id` **and an index leading with it** — `ScopeIndexRule` reads your migration and will refuse it otherwise |
| **5** — unbounded `text` payload, no limit anywhere | A declared bound, `@PersistentTable(growth = TRAFFIC_GROWING)` and a named `@RetentionClass` |
| **6** — unbounded claim, re-selected by predicate so it steals another instance's rows; no reaper | Claim a **bounded batch by the rows you selected**, exactly as `DeliveryPump` does — `markDispatched(sequences)`, then ack or return those same sequences |
| **7** — an unresolvable route strands the row silently, forever | One resolution path, one outcome. Too many attempts ⇒ a **visible** terminal state, never a row that just stops |

**Follow `orca-edge`'s buffer, not `platform/outbox`.** The outbox publishes facts
*outward* to registered consumers; this is an inbound work queue. Edge already solved
the inbound shape — `PENDING → DISPATCHED → ACKED`, with failures returned to `PENDING`
with the attempt counted and a terminal `DEAD` that a diagnostics endpoint reports.
Copy that status model and its reasoning; do not invent a seventh vocabulary.

> **Where behaviour comes from, on the one 1.x guard worth keeping.** 1.x refuses an
> ambiguous lane match rather than guessing, and that fail-closed instinct should
> survive. Its implementation should not: the count runs outside any transaction. In
> 2.0 the seam puts the site on every read by construction, so the ambiguity cannot
> arise the same way — but keep the refusal, and prove it.

**Done when** the table passes all ten checks; a redelivered submit produces one row
and one effect; a claimed batch belongs to exactly one instance under two concurrent
dispatchers; and a row that cannot be resolved reaches a visible terminal state rather
than sitting in a claimed one.

#### A3 · The partner-facing surface · *contract-first, and the taxonomy is the point*

The routes are in the architecture's runtime interface table (`/submit`,
`/submit/bulk`, `/callback`, `/latest`, `/list`, `/next`, `/{uuid}`, `/{uuid}/status`,
`/{uuid}/replay`). **⚠️ They are on the `/api/v1/**` surface, not `/internal/**`** —
§B6 requires a Keycloak token of a caller acting for a user, *including a customer's
own system on the partner API*. `InternalSurfaceRule` permits no third surface.

Edit `services/orca-runtime/src/main/resources/openapi/orca-runtime.yaml`, regenerate,
implement the generated interface. That file's own header says a route is authored
*when the behaviour behind it exists, and not before* — respect it: do not publish
`/callback` until §5's question is answered.

**The redesign, and its exact bounds.** Register **U2**: keep the route shape and the
envelope; fix the two things that are genuinely poor.

1. **The nine indistinguishable `400`s**, three of them from one resolution failure. A
   partner branches on `code`, never on the wording of `message`. Build an
   `IntegrationErrorCode` enum on the `WorkItemErrorCode` model — and write each
   constant's javadoc saying *why it is distinct from its neighbour*. "Unknown event
   type", "no connector configured for it", and "lane not at this installation" are
   three different things a partner fixes three different ways.
2. **The lost claim on `/next` answers `409`, not `404`** — register item **6**. The row
   exists; somebody else took it. ⚠️ *Implementing this closes register item 6; say so
   in your report so the product owner can record the ruling rather than discover it.*

`/submit/bulk` returns **207** with a per-item result array and is deliberately not the
standard envelope — that is in the architecture, and `ErrorEnvelopeRule` will need the
generated model to carry an `ApiStatus` field. Check that early, not at the end.

⚠️ **`201` must mean something.** In 1.x it means only "accepted", carries no execution
identifier, and leaves polling as the sole completion channel. You have the admission
outcome in hand synchronously — **return the visit identifier**, so a partner has
something to correlate against. Record the decision in your report.

**Done when** every route is contract-first, every failure has a distinct code a caller
can branch on, and a property test asserts the taxonomy: the same request that produced
nine indistinguishable `400`s in 1.x produces distinguishable codes here.

#### A4 · Closing the loop · *inversion 3, the one with the disk-space consequence*

**Nothing in 1.x ever writes `COMPLETED`** — the workflow executor contains zero
references to `event_dispatch`, so the only writer is a partner who may never call.
Retention deletes only completed and failed rows. Every successfully dispatched event
therefore accumulates forever, and replay refuses anything not terminal, so in practice
only failures are replayable.

**The platform closes out its own rows.** When admission returns, the row reaches its
terminal state in the same transaction. `PATCH /{uuid}/status` stays as a partner's way
to report *their* outcome, but nothing about ORCA's own bookkeeping may depend on it.

Then `/{uuid}/replay` works on any terminal row, not just failures — and a replay is a
**new** row carrying `source_event_id` at the original, never a mutation of it.

**Done when** a submitted event reaches a terminal state with no partner involvement,
and a property test asserts it: submit, let admission finish, assert terminal — with no
`PATCH` anywhere in the test.

#### A5 · The event-type registry, and the operator's view of the queue

⚠️ **The architecture's schema map gives `integration` more tables than A1–A4 use:**
`connector_config` · `connector_response_config` · `event_dispatch` · **`event_type`**
· **`outbox`** · **`outbox_delivery`** · `service_lease`. Three of those have published
endpoints this plan had not named, and one of them is a dependency of A3 rather than an
extra.

**`event_type` is the dependency — do it with A3, not after.** `GET · POST
/event-types`, `PATCH /event-types/{id}` — *"the registry of event codes the gate
accepts."* A partner's submit carries an event type, and something has to say whether
it is one this installation accepts. In 1.x that resolution is `connector_config.slug =
event_type` with no registry at all, which is why an unknown type and a misconfigured
connector are indistinguishable at the API (sheet §2, the nine `400`s). **A registry is
what lets A3's error taxonomy tell those two apart** — so if you skip it, you cannot
finish A3 properly.

**The two operations views can be last, and can be thin:**

- `GET /event-dispatch` · `POST /event-dispatch/{id}/retry` — the inbound queue and its
  claim state, for site operations. This is the surface that makes inversion 7 real:
  *an unresolvable event is visible rather than stranded.* Without it, "visible" means
  "visible to somebody with a database login", which is what 1.x offers.
- `GET /outbox/parked` · `POST /outbox/{id}/retry` — parked outbox events and their
  admin retry. ⚠️ **The outbox is `platform/outbox`'s table but it lives in your
  module's schema map, so the operator surface over it is yours.** Check what
  `OutboxRelay` already exposes before adding anything to the primitive — the surface
  belongs in `integration`, not in `platform`, which may name no domain concept.

**Done when** an unknown event type is refused with a code that is distinct from a
misconfigured connector, and a stranded dispatch row and a parked outbox row are both
visible and retryable over HTTP rather than only in the database.

---

### Track B — the outbound breadth

**§D2 is your specification**: *"REST and SOAP, four authentication modes, per-connector
certificate trust."* It is a contract fixed by the other side — the platform must
**support** the shape; it does not choose it.

#### B1 · The configuration this needs

`connector_config` today is six columns: `site_external_id, connector_name, base_url,
request_path, deadline_ms, is_enabled`. Extend it, in your band.

- **Protocol** — REST or SOAP, binary-collated CHECK.
- **Four authentication modes.** 1.x holds an AES-encrypted blob decrypted at call time.
  ⚠️ **How credentials are stored at rest is security-shaped — propose, do not
  implement.** §5.
- **Per-connector certificate trust** — a trust store for verifying the *server*. One-way.
  Name the four modes explicitly in your report; the sheet says four, and a reader
  should not have to count them in code.

#### B2 · SOAP behind the same port

`ConnectorPort` does not change. Its javadoc explains why: the compiler's output binds
`ConnectorCallDelegate` by name, so it must stay still when the transport moves.

**Two properties to carry forward deliberately** (sheet §6):

- **Pool clients; do not rebuild per call.** `RestConnector` already does — keyed
  `name@baseUrl|deadlineMillis`. ⚠️ **Extend that key with the auth and trust
  configuration.** If you do not, changing a credential is served by a cached client
  holding the old one — the same class of bug the existing comment describes for
  repointing a host, and harder to see. This is the strongest known performance finding
  in 1.x; do not reintroduce it while fixing something else.
- **The outbound call has no retry in 1.x while the inbound poller retries four times.**
  The sheet calls that asymmetry accidental. Make it deliberate: state what you chose
  and why. ⚠️ A retry on a **non-idempotent** customer operation is not a free
  improvement — if you add one, say what makes it safe.

Keep the two properties `RestConnector` already holds: `exchange` rather than
`retrieve`, because a 4xx/5xx is an *answer* the site may have a branch for; and HTTP/1.1
pinned, because a field TOS of unknown vintage may answer an HTTP/2 upgrade by closing
the connection.

#### B3 · Configuring a connector without a console

`GET · POST /connectors`, `PATCH /connectors/{id}`, `GET · PUT
/connectors/{id}/response-routing`, `POST /connectors/{id}/test`, `GET
/connectors/{id}/health` (circuit-breaker state). Contract-first, `/api/v1/**`.

**Why this is in scope even though the console is not:** `docs/deployment.md` step 10
says a site is configured *through the application*. Today connectors exist only
because `demo-seed` writes rows directly, and that is a demo tool, not an install path.
⚠️ `/connectors/{id}/test` invokes a configured endpoint with sample data on demand —
say in your report what stops it being used to probe the site's internal network.

**Done when** a connector can be created, repointed, response-routed and health-checked
over HTTP; a SOAP connector completes a gate visit against a stub; and a property test
proves a credential change is not served by a pooled client.

## 4 · Verification — run these, record real results

**Running the stack:** `docs/deployment.md` Part 1, and `docs/phase-1-demo.md` §3 for
the port-offset incantation if 8081–8086 are taken. Core first — it publishes the views
runtime and edge wait for. **Keep the output of every command; the report needs it.**

| # | Item |
|---|---|
| 1 | `./gradlew build` green from a clean tree |
| 2 | `./gradlew check integrationTest --rerun-tasks` — **`--rerun-tasks` is not optional.** Without it Gradle answers from cache in under a second and reports a success it did not run |
| 3 | **The Phase 1 demo still runs end to end** — `./gradlew sendPlate`, visit `COMPLETED`, barrier commanded, `visit.completed` in the outbox. The standing regression canary |
| 4 | **A partner event drives a truck through the gate, live** — `POST /submit` → admission → connector → barrier → `COMPLETED`, demonstrated, not only tested |
| 5 | **Two concurrent partner submits for one lane produce one visit** (A1), and a redelivered submit produces one row and one effect (A2) |
| 6 | **A submitted event reaches a terminal state with no `PATCH` from anyone** (A4) |
| 6b | **An unknown event type is refused with a code distinct from a misconfigured connector** (A5) — the taxonomy fix is not done until this is true |
| 7 | **A dispatched row is never stranded**: kill the dispatcher mid-claim, restart, assert the row is reclaimed or visibly terminal — never sitting claimed forever |
| 8 | **A SOAP connector completes a visit** against a stub (B2), and a credential change is not served by a pooled client |
| 9 | Break each of: a new traffic-growing table with no retention class · your queue table's index not leading with the scope column · a controller implementing no generated interface. **Each must fail the build, then revert.** A check nobody has watched fail may not be wired in |
| 10 | `docker compose run --rm verify-isolation` — 36 checks still green |
| 11 | **All six services boot.** A green suite does not prove a service starts; every suite builds its beans directly, and this repository has shipped a service that passed everything and could not boot |

## 5 · Open questions — surface these, do not settle them

**These are known-open. Finding them is not a discovery; resolving them alone is the
failure mode this programme guards hardest against.**

| # | The question | What to do |
|---|---|---|
| **Q1** | **`/callback` has nothing to answer.** *"An external system answers a workflow that is already waiting"* — but `gate-visit.bpmn20.xml` contains **zero** receive tasks, message or signal events, and `ProcessEngineGateway` exposes only `startVisit`, `isRunning`, `currentActivity`, `terminate`. There is no wait state for a partner to answer and no engine method to answer it with. The only wait state in the shipped process is the human `manualInput` | **Do not author a process, and do not add a correlation method on a guess.** This lands on the BPMN execution profile, which the builder-developer owns and has not reviewed. Options: (a) defer `/callback` and do not publish the route; (b) build the engine-side correlation with a test-only process definition proving it, and leave the production process alone. **Recommend (b)** — the capability is provable without committing a process design, and `wp0-admission.bpmn20.xml` already uses a `receiveTask` as a wait state, so the fixture shape exists. **Product owner and builder-developer decide** |
| **Q2** | **How a partner obtains a credential is not designed.** §B6 settles the *mechanism* — a partner carries a Keycloak token, validated locally by signature. The *client structure* is register **U3**, explicitly open, and it records that the six service-account clients in the dev realm are "a local development convenience only" carrying no weight in the target design | Build against a Keycloak token and nothing else. ⚠️ **Do not invent an API-key mechanism.** 1.x's published specification advertised one that does not exist in its code, so a partner integrating from that document gets `401` on every call — inventing one here would make that document accidentally true and add an unreviewed credential path. **Security-shaped: propose, report, wait** |
| **Q3** | **Where connector credentials live at rest.** 1.x uses an AES-encrypted blob decrypted at call time. 2.0 has no established secret-at-rest mechanism, and `docs/deployment.md` step 6 lists secrets provisioning as owed to the product owner | **Security-shaped.** Propose in the report with the trade; do not implement a scheme on your own authority |
| **Q4** | **The retention class for your queue table.** `@RetentionClass` takes a free-form String and the check only requires it non-blank, so you are not blocked. But the closed 18-value list is unreconciled — nine provisional values exist in code | Name one, follow the existing naming, and **mark it provisional in your report**. Stream 4 reconciles the list; it must be able to find yours |
| **Q5** | **Priority ordering must match the queue that already exists.** 1.x: lower is more urgent, unset sorts last. `work_item` reads use `COALESCE(priority,-1)`, set-before-unset, then FIFO. Two orderings that disagree is what 1.x shipped | Pick one, make it the same as `work_item`'s unless you have a reason, and state it |
| **Q6** | **Synchronous admission versus a queued `202`.** 1.x has both modes; the architecture prescribes neither. **Recommend synchronous** — it is what `/internal/events/v1` does, it is what lets `201` carry a visit identifier (A3), and it is what makes A4's self-closing row natural. The cost is that a slow admission occupies the partner's call | Implement synchronous, **state the decision and its cost in the report** |

**When a 1.x behaviour contradicts the architecture:** the architecture wins, and the
divergence is recorded rather than resolved quietly. **When something is genuinely
unspecified in both:** report the gap. A gap reported is worth more than a gap filled.

## 6 · The report — `docs/stream-1-report.md`

The established shape:

- **What was built, per work package** — and what was not, named.
- **The §4 verification table with real results**, including the commands' output.
- **Each of the seven inversions, with the property test that proves it.** This is the
  section a reviewer reads first: the sheet's §0 is the acceptance criteria, so answer
  it point by point.
- **Every decision this plan did not dictate**, with the reasoning — Q4, Q5 and Q6 at
  minimum, plus the retry asymmetry in B2 and what `201` carries in A3.
- **The §5 questions you had to leave open**, and what you would need to close each.
- **Anything found wrong** in this plan, the reference sheet, the architecture, or 1.x's
  shapes — **reported, not silently corrected.** Two documents were found wrong while
  this plan was written; assume there are more.

---

*Track A starts at A1. Track B can start at B1 the moment A1 is merged. If you are
about to write a resolution to something in §5, stop and ask instead.*
