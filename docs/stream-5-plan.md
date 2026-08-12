# Stream 5 — Implementation Plan: the workflow builder, end to end

**For the developer building stream 5 (Selvedin), and the AI they drive · 12 August 2026 ·
Companion report: `docs/stream-5-report.md`**

Self-contained. Where it points at another document, read that document before building the
thing it describes.

**This stream delivers the property the product is named for.** One of the two properties
ORCA exists for is *the site's processes belong to the site* — administrators design their
gate processes and screens visually, without code. Until this stream lands, that property is
unimplemented: the platform runs exactly one hand-written process. This stream is the design
plane (storage), the publish pipeline (validate → compile → freeze → deploy), the assignment
of a published version to a lane, and the **Angular builder UI** that authors it all.

**You already built the engine of it.** The compiler, the selector evaluator, the engine
seam and the compiled-process delegates are yours, on `feature/OCS-4-runtime-migration`.
This stream gives that compiler something to compile *from* (a real design store) and
somewhere to send its output (a durable deployment). The runtime half you ported is the
consumer; the core half this plan builds is the producer.

⚠️ **Two things make this stream different from streams 1–4, and both matter from the first
commit:**
1. **It spans three layers** — database, API and UI — where the other streams are backend
   only. The plan is sequenced so **every backend work package is independent of the UI**:
   you can complete WP0–WP5 with no Angular at all, driving publish through the API with
   designer-JSON payloads. The UI work packages (WP6–WP7) come last and gate only on the
   product owner standing up the Angular scaffold.
2. **`orca-core` has no module walls.** Unlike `orca-runtime`, core is a flat service with
   no build check separating you from stream 3 (custom entities + licensing, also in core).
   You share a codebase. The fences are: **disjoint packages** (yours is `design`),
   **disjoint migration bands** (§3), and the awareness that **the core OpenAPI document is
   the one file you both edit** — coordinate that edit, never overwrite it.

---

## Read first, in this order

1. **`AGENTS.md` (root, and `services/orca-runtime/AGENTS.md`)** — the operating rules:
   contract-first, the scope seam, the ten build checks, tests-prove-properties, the
   definition of done. Enforced, not advisory. You know these; re-read the runtime one,
   because your runtime work rides on top of the compiler you already conformed to them.
2. **`docs/CODE_PATTERNS.md`** — §1 (a request end to end), §4 (the shape of a migration),
   §5 (the engine is confined — the rule your delegates already obey).
3. **`docs/MIGRATION_NUMBER_RANGES.md`** — **your bands are `core` V151–V180 and `runtime`
   V171–V180.** ⚠️ The runtime band is **usable only after `feature/OCS-4-runtime-migration`
   merges** — your V168–V170 must exist below it. Read §3.1 for the out-of-order failure you
   will hit when you pull another stream's runtime migration.
4. **This plan, in full.**
5. **`docs/design-tables-from-1x.md`** — the DERIVED-FROM-1X reference. **Read its §0 (the
   seven things that decide this stream) first**, then §5 (the save-document → publish-payload
   delta — the most important interface here) and §8 (the eighteen defects not to carry).
6. **`docs/BPMN_EXECUTION_PROFILE.md`** — what the compiler may emit. **You are the
   builder-developer this document has been waiting for review from.** Ratifying it —
   including its §8 boundary-timer question — is WP0, not an afterthought.
7. **`docs/ORCA_ARCHITECTURE.md`** — §A3 (how processes are designed), §A4 (how screens are
   designed — U1's one-registry rule and snapshot-at-publish), §C1 (orca-core: the publish
   pipeline, the `POST /internal/deployments/v1` push), §B9 "Publishing a process", §B10
   ("nothing changes under a truck already moving").
8. **`docs/phase-2-report.md`** — the `orca-core` configuration world you extend; its §7 is
   where the collation trap is explained.
9. **`docs/OCS-4_MIGRATION_REPORT.md`** (on your branch) — your own record of what the
   compiler port did and the three things it flagged. **The `n_`-start-node trace gap is
   WP-relevant here** (§5, WP2).

**Mirror the existing code.** `orca-core` is the most complete service in the repo; the
compiler and delegates you wrote are the template for the runtime half.

| For | Study |
|---|---|
| A config table with its API | `V105__teams_templates.sql` + the seam repository + controller above it |
| An enum CHECK the database enforces | Any of V105–V107 — `COLLATE Latin1_General_100_BIN2` |
| A published view runtime reads | `V102__topology_views.sql` |
| The deployment receiver's consumer | your own `DefinitionRegistry` + `EngineDeployment` on the branch |
| A property test that proves a constraint refuses | Phase 2's `*PropertiesIT` |

---

## 1 · What this stream delivers

**A site administrator can design a workflow and its screens in a visual builder, publish
it, assign it to a lane, and have a truck run it — with every published version immutable and
every running visit unaffected by later edits.**

Concretely, in dependency order:

- **Design storage in `orca-core`** — the authored workflow graph (nodes, links, branches,
  connector responses) and the authored screen artifacts (layout, components, headers),
  stored as the builder authors them, with geometry and per-node authoring config the
  compiler does not need.
- **The publish pipeline** — validate → compile to BPMN (your compiler) → freeze an
  **immutable version** holding both the authored source and the compiled definition → push
  to runtime. Publishing writes a new version; it never mutates a live one.
- **Assignment** — which published version runs on which lane/area, with a running-visit
  guard, demoting the previous version.
- **The runtime deployment receiver** — `POST /internal/deployments/v1`, idempotent by
  deployment id, deploying the compiled BPMN and registering it durably (replacing the
  in-memory `DefinitionRegistry`), so admission stops hardcoding `gate-visit` and starts the
  version assigned to the lane.
- **The Angular builder UI** — workflow canvas (Foblex Flow) and screen builder, emitting the
  publish payload the compiler already consumes.

**What it explicitly does NOT deliver** (name these in the report so nobody assumes them):
- The operator console, the kiosk, the driver portal — other frontends, not this stream.
- The screen *renderer* used at runtime by an operator console — this stream builds the
  screen *store and builder*; whoever builds the console consumes it. (Design the stored
  format to U1's one-registry rule so a single renderer can exist; do not build the renderer.)
- Per-route authorization of the new admin endpoints — that is an open product-owner ruling
  (§5 Q4). Build the endpoints behind the existing authenticated chain; do not invent an
  entitlement check.

---

## 2 · Ground rules

- **1.x is the reference for the data; the architecture governs the behaviour.** Where they
  disagree, `docs/design-tables-from-1x.md` wins and the divergence is recorded. The
  eighteen defects in its §8 are things you must *not* reproduce — most importantly the
  live-read (D1): a published version is immutable and a visit binds the version it started
  on.
- **Contract-first.** Edit the core OpenAPI document, regenerate, make the controller satisfy
  the generated interface. ⚠️ Stream 3 also edits that document — coordinate; never overwrite.
- **The scope seam, always.** Every read and write through `platform/scope`. Design tables
  are site-scoped like everything else in core.
- **Every migration** — growth declaration, retention class if traffic-growing, scope-leading
  index, `COLLATE Latin1_General_100_BIN2` on every enum CHECK. A shipped migration is never
  edited.
- **Tests prove properties, not paths.** The property that matters most here is immutability:
  *a publish while a visit is running does not change that visit*. Prove it by execution, the
  way `WorkItemSlaIT` proves the timer.
- **Anything security-shaped or scope-changing is the product owner's.** Publish and DDL are
  powerful; where a decision record does not authorise the exact choice, PROPOSE-and-report.

---

## 3 · Migration bands and the merge order

- **`core` V151–V180** — design storage, screen artifacts, the deployment/version store,
  assignment. Nobody else writes here (stream 3 is V111–V150).
- **`runtime` V171–V180** — the deployment-receiving half and definition binding. ⚠️ **These
  sit above your V168–V170**, which arrive only when `feature/OCS-4-runtime-migration` merges.
  **Do not write a runtime migration in this stream until that branch is on `develop`** — a
  V171 with no V168–V170 beneath it is an out-of-order failure on every database.

**The launch gate for this whole stream:** `feature/OCS-4-runtime-migration` independently
verified and merged to `develop`. You build directly on the compiler it carries. Until then,
the stream is `READY — WAITING FOR OCS-4 MERGE`; you may onboard, ratify the BPMN profile
(WP0) against the branch, and design the schema, but you branch and write migrations from the
merged `develop`.

---

## 4 · Work packages, in dependency order

Each is one concern — migration + code + contract + property tests together — with its own
"done when". **WP0–WP5 are backend and need no Angular. WP6–WP7 are UI and gate on the
scaffold.**

### WP0 · Ratify the execution profile · *begins the stream; no code*
You are the builder-developer `docs/BPMN_EXECUTION_PROFILE.md` has awaited. Review it end to
end against the compiler you built. Settle its **§8 boundary-timer question** (a service-task
boundary timer cannot fire; a wait-state one can — your compiler must know which constructs it
may emit). Record the ratification in the profile (mark it no longer *proposed*) and in your
report.
- **Done when:** the profile is ratified or its open points are listed as decisions for the
  product owner; nothing in later WPs contradicts it.

### WP1 · The workflow design store · *the spine of the stream*
The authored graph in `core`: a `workflow` design table (identity, name, draft/published
lifecycle) and the node/link/branch/response tables — **or a collapsed form**. ⚠️ **This is a
design decision, not a given:** 1.x exploded the graph into 16+ typed tables (§1 of the
reference); 2.0 need not. A single authored-document column (the builder already sends one
JSON graph) with typed *projections* only where a query needs them may be far simpler. Decide
it, state the trade in the report, and prove the choice round-trips the publish payload.
- Store geometry, handles, node descriptions and per-node authoring config the compiler does
  not consume (§5 of the reference names them) — the draft store is richer than the publish
  payload.
- Unique constraints on every node identity day one (reference D6). Soft-delete uniform
  (D7). No live-read (D1). No raw SQL (D9).
- **Done when:** a designer-JSON document persists and reloads byte-faithfully; a property
  test proves duplicate node identities are refused by the database, not by Java.

### WP2 · The publish pipeline · *validate → compile → freeze → the n_ fix*
Publishing takes a draft, validates it, compiles it with your compiler, and writes an
**immutable version** row (authored source + compiled BPMN + a schema/version stamp). A
published version is never mutated; re-publishing writes a new one; rollback is re-publishing
an earlier one.
- ⚠️ **The `n_`-prefixed start-node trace gap you flagged becomes real here.** Admission
  records `process_instance_id` after `engine.start` returns, so a compiled process whose
  start node completes synchronously cannot be traced. Today's hand-written process has no
  `n_` nodes; the first *compiled* process admitted does. **The fix touches
  `AdmissionService`, which stream 1 Track A also edits** — coordinate the change, and prove
  it with a compiled process driven end to end (correlate by business key, or pre-allocate
  the instance id, per your report's own analysis).
- **Done when:** publishing a real designer payload produces an immutable version; a
  **publish while a visit is running leaves that visit on its original version** (the
  immutability property — prove it by execution); a compiled process is admitted and its
  steps are traced.

### WP3 · Assignment · *which version runs where*
A published version is assigned to a lane/area, with the running-visit guard (a lane with a
running visit does not silently switch versions) and demotion of the previous version.
Separate from the version artifact (reference §3 — 1.x conflated pointer and status; do not).
- **Done when:** assigning a version routes new visits to it while running visits finish on
  their bound version; a property test proves the guard.

### WP4 · The runtime deployment receiver · *runtime V171+; after OCS-4 merge*
`POST /internal/deployments/v1` on runtime, idempotent by deployment id: receive a published
version, deploy the compiled BPMN to the engine, and **register it durably** — replacing the
in-memory `DefinitionRegistry` (whose javadoc already defers durable registration to this
work) with a persisted definition catalog. Admission resolves the lane's assigned version
instead of the hardcoded `gate-visit` key.
- **Done when:** core pushes a published version and runtime admits trucks onto it; a restart
  does not lose the registration; the endpoint is under `/internal/**` (InternalSurfaceRule).

### WP5 · The screen design store · *layout, at last*
The authored screen artifact that `core.screen` (V109) deliberately disclaims: layout,
components, headers, per-component config — with a **schema version in the artifact root**
and a **single component registry** (U1) so one renderer can exist. Reuse `core.screen`'s
identity + SLA row; add the layout store beside it.
- Do not reproduce the three-renderer divergence or the live-read (reference §7). Snapshot at
  publish (§A4): a visit sees the screen version it started with.
- **Done when:** a screen artifact persists with its schema version and is frozen at publish;
  a property test proves an edit mid-visit does not change the running visit's screen.

### WP6 · The Angular builder scaffold · *first UI package; gates on the scaffold*
The Angular application and the **Foblex Flow** workflow canvas (register item 15 — MIT,
Angular-native). Authoring a graph, saving a draft, publishing — emitting the **publish
payload the compiler already consumes** (reference §5; the builder emits shape (b), not 1.x's
shape (a)). ⚠️ **Emit stably ordered arrays** — the compiler treats authored order as
deterministic emission order.
- **Done when:** an administrator authors a workflow in the browser, publishes it, and a truck
  runs it end to end through the demo stack.

### WP7 · The screen builder UI
The Angular screen builder over the WP5 store: the component palette, the layout canvas, the
header/SLA panel. One component registry shared with the (future) renderer.
- **Done when:** an administrator authors a screen, publishes it, and it is bound immutably to
  the workflow's wait state.

---

## 5 · Open questions — surface these, do not settle them

**These are known-open. Finding them is not a discovery; resolving them alone is the failure
mode this programme guards hardest against.**

| # | The question | What to do |
|---|---|---|
| **Q1** | **The designer-surface placement.** Your branch put `/api/v1/designer/validate` + `/namespace` on **runtime**; the architecture puts design and publish in **core** (§C1, §B9), and no `designer/*` route exists in any architecture endpoint table. Validation-is-compilation and the compiler lives in runtime's `execution` — so there is a real argument for runtime — but it is an architecture addition either way | **Do not silently keep it on runtime.** Propose the placement (core-owned design API calling a runtime compile/validate service, or the validate surface staying on runtime with an architecture amendment) and let the product owner rule. Record it; amend the architecture to match whatever is ruled |
| **Q2** | **How rich is the draft store beyond the publish payload?** The 2.0 payload carries no canvas geometry, handle ids, node descriptions, `lane_status_uuid`, `durations`, `screen_type`, or the `node_config` bag (reference §5). The builder needs somewhere to keep them; the compiler does not want them | **Decide the draft-vs-publish split and state it.** The draft store is almost certainly richer than the payload. Do not drop authoring data because the compiler ignores it, and do not smuggle it into the payload the compiler validates |
| **Q3** | **One authored-document table, or the exploded typed-table model?** (WP1). 1.x exploded into 16+ tables and paid for it in eighteen defects; 2.0 need not | This one **is** yours to decide — but state the trade and prove the choice round-trips the payload. Lean toward the simpler store unless a query genuinely needs typed projections |
| **Q4** | **Per-route authorization of the publish/design/assign endpoints.** The shared web chain authenticates callers but implements **no per-route authorization** (`PlatformSecurityAutoConfiguration` — `anyRequest().authenticated()`). Publishing a process and assigning it to a lane are high-privilege admin actions; today any authenticated caller could invoke them | **Security-shaped: build behind the authenticated chain, mark the authorization slot as an open gate, and do not invent an entitlement check.** This is the same ruling the credentials work stopped at, and it is the product owner's. Report it as blocking the admin surface's production readiness, not the build |
| **Q5** | **The node vocabulary.** 1.x's frontend enum carries `PWA_START`/`WAIT` that its backend `oneof` rejects (reference §1); your compiler accepts a defined set | Decide 2.0's node vocabulary deliberately from the compiler's accepted types, not by inheriting 1.x's mismatch. State it |
| **Q6** | **The retention class for any traffic-growing table you add** (published-version history grows with every publish). `@RetentionClass` takes a free-form string; the closed list is unreconciled (nine provisional values). ⚠️ The compiler port already added two members to class `visit` | Name one, follow the existing naming, **mark it provisional in your report**. Stream 4 reconciles the list and must be able to find yours. A published-version store is a candidate for its own class — propose it to `docs/decision-retention-classes.md`, do not invent a duration |

**When a 1.x behaviour contradicts the architecture:** the architecture wins, and the
divergence is recorded. **When something is genuinely unspecified in both:** report the gap. A
gap reported is worth more than a gap filled.

---

## 6 · Verification — run these, record real results

Do not trust `BUILD SUCCESSFUL`. Establish a baseline before you change anything, count from
the result XML, and re-drive the truck at the end.

| Step | Command | Expect |
|---|---|---|
| Baseline (services stopped, §6.1 of LOCAL_DEVELOPMENT) | `./gradlew check integrationTest --rerun-tasks` | The count on merged `develop` at your start, 0 failures — record it |
| Count from XML, never the console | the python snippet in `LOCAL_DEVELOPMENT.md` §6.2 | suites/tests/failures/skipped |
| Schema isolation still holds | `(cd deploy && docker compose run --rm verify-isolation)` | `PASS — 36 checks` |
| The gate still runs (regression canary) | `docs/phase-1-demo.md` — drive a truck | `COMPLETED` + `RAISE_GATE EXECUTED` |
| **The immutability property** | your new publish-mid-visit property test | a visit started on version N finishes on N after version N+1 publishes |
| **End to end, the whole point** | author a workflow in the builder → publish → assign → `sendPlate` | a truck runs the *authored* process, not the hardcoded one |

Then the definition of done from `AGENTS.md`: every guarantee you touched has a property test
that fails if the guarantee breaks; the services start and a truck goes through; a short report
exists.

---

## 7 · The report — `docs/stream-5-report.md`

End with it. The fixed handoff format (`docs/PARALLEL_STREAM_LAUNCH.md` §6) plus, specific to
this stream: **the WP1 store decision** (one document vs typed tables) with its trade; **the
draft-vs-publish split** (Q2); **the designer-surface placement proposal** (Q1); **the `n_`
trace fix** and how it was proven; and **every place the builder payload diverges from 1.x**,
named so the divergence is deliberate. A report is a claim until it is re-run — I re-run it.
