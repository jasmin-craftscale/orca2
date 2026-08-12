# DERIVED-FROM-1X · Workflow & screen design storage

**For stream 5 (the workflow builder). Extracted read-only from `../Lynxis-Gate`,
12 August 2026, with file:line evidence. This sheet is the authority where it and the
1.x source disagree — it carries the defects deliberately *not* carried forward.**

The rule that governs every line below: **1.x is the reference for the data; the
architecture governs the behaviour.** Where 1.x does something the architecture forbids,
this sheet names the divergence rather than reproducing it.

---

## 0 · The seven things that decide this stream

Read these before the detail. Each is code-verified; each is a design input you cannot
get from the architecture alone.

1. **1.x stores no workflow-graph *document*.** The builder POSTs one JSON graph, and
   the backend **explodes it into 16+ typed node tables** (`start_nodes`, `io_node`,
   `decision_nodes`, …) plus a global `node_links` edge table. The graph exists as a
   document only in transit. 2.0 is free to store the authored document directly (and
   should — see §4).
2. **1.x has NO versioning, NO draft/published split, NO snapshot, NO copy-on-write for
   graphs.** A save mutates the live design rows in place. The executor reads those rows
   *live* during execution, behind a 60-second cache. **Editing a deployed workflow
   changes every in-flight execution.** This is the single defect the whole
   compile-at-publish + immutable-deployment design exists to reverse — it is not a
   reimplementation, it is an inversion. (§6, D1.)
3. **`workflow_deployments` in 1.x is a mutable pointer, not an artifact.** It holds
   `{workflow_id, lane_id/site_id, status}` and **no graph, no version, no compiled
   form**. Same word as the 2.0 concept, completely different thing. Do not let the name
   mislead the schema. (§3.)
4. **The `process_*` twin tables are a reusable-subflow *library*, not a copy-on-write
   snapshot.** Nothing ever copies workflow rows into them. They are a parallel design
   schema for subflows, authored separately. (§2.) This resolves the "1,649 workflows but
   ~125 start nodes" anomaly: most `workflow` rows are historical/cloned/soft-deleted, and
   subflows live in their own tables.
5. **The 2.0 compiler already exists and defines the target contract.** On
   `feature/OCS-4-runtime-migration`, `DesignerWorkflow.java` consumes a **publish
   payload** whose shape is fixed and different from 1.x's save document. **The new
   builder must EMIT that payload** (or a ruled evolution of it), not 1.x's shape. The
   full delta is §5. This is the most important interface in the stream.
6. **A "screen" in 1.x is one row whose only design column is an opaque `ui_description`
   JSON blob.** Identity, SLA thresholds and audit are columns; the entire layout,
   component tree and headers are one JSON string. 2.0's `core.screen` already carries the
   identity and SLA half (V109) and **explicitly disclaims layout** — so this stream
   builds the layout store that does not yet exist. (§7.)
7. **The goldens the compiler is pinned against are PLT's real workflows** (the SOW
   client — "PLT In-Gate Portal", exported from the authored estate). Treat the fixture
   corpus as commercially sensitive evidence, and treat "the compiler passes its goldens"
   as "it reproduces the client's actual designs byte-for-byte." Do not circulate the
   estate corpus.

---

## 1 · The workflow design tables (1.x)

All schema is **GORM `AutoMigrate`-derived** — there is no raw `CREATE TABLE` for any
design table (`common/migration/migration.go:100-234`, applied `:289-297`). The DDL *is*
the struct tags in `common/entity/`; migrations only add indexes/columns afterward. **Any
constraint not present as a struct tag or an explicit index migration does not exist** —
this is the root of half the defect catalogue.

### Root
| Table | Entity | Key columns |
|---|---|---|
| `workflow` | `workflow_entity.go:7-34` | `workflow_id` PK, `workflow_uuid`, `name`, `tag`, `description`, **`version`**, `is_iterator_flow`, `parent_iterator_node_id`, `is_valid`, `is_active`, `is_deleted`, audit×4, `type` (LANE/PWA/DEFAULT/OOS/SWW), `site_id`, `customer_id` |

`version` is bumped **only** by clone (`+0.1`) and import — both of which mint a *new*
`workflow_uuid`. An in-place save never touches it (§6a). So "version" in 1.x means
"this is a copy of that", not "this is a newer revision of the same live thing."

### Node tables (one row per authored node; `common/entity/nodes_entity.go` unless noted)
| Table | Entity:line | Node does |
|---|---|---|
| `start_nodes` | `StartNode:6-23` | entry point (read by `workflow_id` only) |
| `node_links` | `NodeLink:197-211` | **the entire edge set** — `current_node_id`/`next_node_id` are node UUIDs; no `workflow_id`, no FK, no soft-delete, no index |
| `decision_nodes` | `:106-130` | branch/wait |
| `decision_result_nodes` | `:132-150` | branch outcomes (child of decision) |
| `display_nodes` | `:164-183` | renders a kiosk screen (`display_kiosks`) |
| `terminator_nodes` | `:290-310` | end node; `mode` selects WORKFLOW/INPUT_OUTPUT/MANUAL_INPUT/PROCESS terminator |
| `io_node` (singular) | `IONode:57-89`, TableName `workflow_entity.go:221` | device/queue I/O bound to an `io_config` |
| `io_node_mappings` | `:91-104` | which IO nodes an INPUT_OUTPUT_TERMINATOR waits on |
| `manual_input_node` (singular) | `workflow_entity.go:293-336` | operator data-entry node; screen in `manual_inputs` |
| `connector_node` (singular) | `:213-268` | outbound HTTP/SOAP or INBOUND entry |
| `connector_response_node` | `:245-284` | per-status-code branch (PK column is literally `id`) |
| `connector_response_config` | `workflow_entity.go:203-219`, TableName `nodes_entity.go:278` | **config, not a node** — child of `connector_config`, shared across workflows |
| `map_iterator_nodes` | `:339-361` | fan-out; child flow found via `workflow.parent_iterator_node_id` |
| `notification_nodes` | `:364-392` | push/gate notification |
| `process_nodes` | `:313-336` | **call-site** invoking a reusable subflow |

Node-type enum the builder emits (18 values): `frontend/gate/src/workflowBuilder/constants/enum.ts:11-30`
— `START, PWA_START, INPUT_OUTPUT, MANUAL_INPUT, DECISION, DISPLAY, CONNECTOR,
MANUAL_INPUT_TERMINATOR, WORKFLOW_TERMINATOR, INPUT_OUTPUT_TERMINATOR, PROCESS_TERMINATOR,
CONNECTOR_RESPONSE, DECISION_RESULT, WAIT, PROCESS, MAP_ITERATOR, TERMINATOR, NOTIFICATION`.
⚠️ **`PWA_START` and `WAIT` exist in the frontend enum but not in the backend `oneof`**
(`services/screen-builder-service/internal/dtos/work_flow_creation_request.go:43`) — a
live 17-vs-16 divergence. Decide the real node vocabulary for 2.0 deliberately; do not
inherit the mismatch.

### Write ownership in 1.x (three services write the graph — a defect, D10)
- **screen-builder-service** — all node tables, `node_links`, screen rows
  (`work_flow_creation_repository.go`).
- **work-flow-management-service** — the `workflow` row, `workflow_area_mappings`,
  `workflow_deployments`, subflow headers (`workflow_repository.go`).
- **work-flow-executor-service** — a **duplicated** `PublishWorkflowRepository`
  (`work_flow_creation.go:148-1068`) behind `POST /work-flow/publish`. A second
  implementation of "insert a graph" that can drift from the first.

2.0 collapses all of this into **orca-core's design module** — one writer, one schema.

---

## 2 · The `process_*` subflow library — NOT a snapshot

14 tables in `common/entity/process_node_entity.go`, each parented by `process_subflow_id`
(never `workflow_id`): `process_subflows`, `process_start_nodes`, `process_io_nodes`,
`process_connector_nodes`, `process_connector_response_nodes`, `process_decision_nodes`,
`process_decision_result_nodes`, `process_terminator_nodes`, `process_io_node_mappings`,
`process_map_iterator_nodes`, `process_node_links`, `process_manual_input_nodes`,
`process_display_nodes`, `process_notification_nodes`.

**Written** by the designer at subflow-edit time (`POST /save-process-subflow` →
`work_flow_creation_service.go:7533`), **read** live by the executor at subflow invocation
(`process_executor_repo.go:66-107`). **No step anywhere copies workflow rows into
`process_*`.** (Negative traced: every `Process*{}` construction in `services/` is in
screen-builder, work-flow-management, or sync — none reads a `workflow`-table row as its
source. Clone/import mint new UUIDs for a new workflow, they do not version an existing
one.)

**Consequence for 2.0:** a subflow is a *reusable authored unit*, invoked by a `PROCESS`
call-site node. 2.0 collapses the twin schema — a subflow is a workflow-shaped design
artifact, referenced, not duplicated into a parallel table set. (The 2.0 compiler already
models this: `NodeSource.java` offsets subflow ids by `SUBFLOW_OFFSET = 10_000_000` and
reads the same node shapes.) **Behaviour source:** the architecture — subflows are
designs; the call-site references a published version.

---

## 3 · `workflow_deployments` — a mutable pointer, do not copy the concept

Entity `workflow_entity.go:79-109`. **Entire payload:** `workflow_deployment_id`,
`workflow_deployment_uuid`, `status` (ASSIGNED|DEPLOYED|FAILED), `is_active`, `is_deleted`,
audit×4, `workflow_id`, `lane_id` (nullable), `site_id` (nullable). **No version, no graph,
no compiled form, no publish timestamp, no content hash.**

- **Assign** (`workflow_repository.go:955-1096`) creates the row `status=ASSIGNED`.
- **Deploy** (`workflow_deployment_repository.go:453-467`) only flips `ASSIGNED→DEPLOYED`
  and demotes the previous DEPLOYED row on that lane. Its pre-deploy validation
  (terminator present, subflow terminators, running-execution guard `:326-354`) is the
  **closest thing 1.x has to "publish"** — but it writes nothing except the status flip
  and asserts against *live* design rows.
- **Read** on the executor hot path (`workflow_executor_repo.go:344-352`): join
  `workflow_deployments × lanes_and_portals × workflow` where `status='DEPLOYED'`, cached
  60 s.

⚠️ **No DB guarantee of one DEPLOYED workflow per lane** (D15): the index is non-unique
`(workflow_id, lane_id, is_active, is_deleted)`; uniqueness is a read-then-write in Java.
Concurrent deploys can leave two DEPLOYED rows and the executor's `Find` returns both.

**2.0's `workflow_deployment` is the opposite object:** an immutable published version
holding the authored JSON *and* the compiled BPMN, frozen at publish, that a visit binds
and never re-reads (architecture §C1, §B10 "nothing changes under a truck already
moving"; the branch's V170 already adds `workflow_id`/`definition_version` to
`runtime.execution` for that binding). Assignment (which version runs on which lane) is a
**separate** concern from the artifact — keep them in separate tables, unlike 1.x which
conflates pointer and status in one row.

---

## 4 · Assignment, and how a lane resolves its workflow

- **`workflow_area_mappings`** (`workflow_entity.go:49-76`) is an **organisational/UI
  grouping only** — written once at create, read for grid filtering and the designer's
  area lookup. **The executor never reads it** (zero references in
  work-flow-executor-service). A lane resolves its workflow through
  `workflow_deployments.lane_id + status='DEPLOYED'`, not through the area mapping.
- **Dead/unowned schema (GAP):** `visit_mission_workflow_mappings`
  (`workflow_entity.go:553-570`) and `workflow_configuration_mappings`
  (`nodes_entity.go:263-291`) have entities and AutoMigrate but **no reader or writer
  found** in `services/`. Do not carry them without a live use — treat as dead until
  proven otherwise.

**2.0 shape:** assignment is core-owned config (which published version → which
lane/area), consumed by runtime through the deployment-receiving endpoint
`POST /internal/deployments/v1` (in **zero** contracts today — this stream adds it). The
branch's `DefinitionRegistry` is deliberately in-memory and its javadoc defers durable
registration to exactly this work.

---

## 5 · THE INTERFACE THAT MATTERS — 1.x save-document vs 2.0 publish-payload

The 2.0 compiler's input is fixed and already implemented. Verified from
`origin/feature/OCS-4-runtime-migration`:
- **Parser/contract:** `services/orca-runtime/src/main/java/com/lynxis/orca/runtime/execution/compiler/DesignerWorkflow.java`
  — class javadoc: *"the publish payload … the compiler's ONLY input … the live DB is
  never read at compile time."*
- **Shape** (confirmed against golden `goldens/workflow_31653.json`):
  ```
  { "workflow_id": 31653,               // int, REQUIRED
    "workflow_uuid": "790948ab-…",
    "name": "PLT In-Gate Portal",
    "nodes":    [ {"uuid","type","name", + "mode"/"topic"/"wait_seconds"/"subflow_id"} ],
    "branches": [ {"uuid","decision_uuid","name","order","condition"} ],
    "responses":[ {"uuid","connector_uuid","status_code"} ],
    "node_links":[ {"from","to","position"} ] }
  ```
- **Two edges are implicit and must NOT be modelled as links:** decision→branch via
  `decision_uuid`, connector→response via `connector_uuid`. No link row for either.
- **Insertion order is load-bearing** (`LinkedHashMap`, deliberate) — authored order is
  deterministic emission order. The builder must emit stably ordered arrays.
- **Unknown/extra content is REFUSED by a named invariant** — the corpus test exists to
  kill "the 137-silently-dropped-nodes class." A payload that hides a defect must fail
  with a named error, not deploy broken.

### The delta the new builder must cross
| Concern | 1.x `save-workflow` sends | 2.0 publish payload wants |
|---|---|---|
| Node id key | `id` | `uuid` |
| Node label | `display_text` | `name` |
| Edges | per-node `next_states[]` + delta verbs (`action`, `deleted/new/updated_links`) | flat top-level `node_links[]` of `{from,to,position}` |
| Handles | `link_position:{source_handle,target_handle}` | not present |
| Canvas geometry | `canvas_config:{x_pos,y_pos}` **as strings** | not present |
| Branches | `DECISION_RESULT` nodes inside `nodes[]` | separate `branches[]` keyed by `decision_uuid` |
| Connector outcomes | `CONNECTOR_RESPONSE` nodes inside `nodes[]` | separate `responses[]` keyed by `connector_uuid` |
| Per-type config | opaque `node_config: map[string]any` | flattened typed fields (`mode`, `topic`, `wait_seconds`, `subflow_id`) |
| Container id | `work_flow_uuid` only | `workflow_id` (int, required) + `workflow_uuid` + `name` |
| Mutation model | delta verbs on a live graph | whole-container snapshot |
| Unknown fields | tolerated | refused by named invariant |

⚠️ **Open (belongs in the plan's §5, not to be guessed):** the 2.0 payload carries **no**
canvas geometry, handle ids, node descriptions, `lane_status_uuid`, `durations`,
`screen_type`, or the `node_config` bag. `PublishPayload.of` simply does not emit them, and
no branch document says whether they move elsewhere (the builder's *own* draft store) or
are dropped. **The builder needs a place to keep geometry and per-node authoring config
that the compiler does not want** — almost certainly the draft/design store is richer than
the publish payload. Decide it; do not infer it.

---

## 6 · Versioning & the live-read defect (the reason this stream exists)

**Confirmed: 1.x has no versioning for graphs, and the executor reads design rows live.**

- A save writes exactly one field on the workflow row (`is_valid`) —
  `work_flow_creation_service.go:5086-5090` — no version bump, no new row, no draft flag.
- `FetchNextStates` (`workflow_executor_repo.go:745`):
  `WHERE current_node_id = ?` — no workflow, no version, no execution, no soft-delete
  predicate (the table has none). Every node fetch is the same shape, by UUID, against the
  live row, none filtering `is_active`/`is_deleted`. Two 60-second caches sit in front, so
  an edit applies to running executions after the cache expires.
- **The one real copy-on-write in 1.x is on *config*, not graphs:** `io_config` and
  `connector_config` version-on-edit-when-referenced (`io_service.go:951-1014` — if
  `workflowCount>0`, increment version and insert a new `ioconfig_uuid`; running flows keep
  the old version). Cite this in the 2.0 design as *"the freeze-on-reference pattern
  already exists here, it was simply never applied to graphs."*

**2.0 reverses this by construction:** publish compiles and freezes an immutable version;
a visit binds the version it started on and never re-reads a live pointer; editing a design
never touches anything running. This sheet exists so nobody "simplifies" the builder back
into a live-read.

---

## 7 · Screens

**A 1.x screen is one row** — `manual_inputs` (WORKITEM/WEB_SCREEN) or `display_kiosks`
(KIOSK), both in `common/entity/screens_entity.go`. **The only design column is
`ui_description`**, an opaque JSON string:
```
{ "layout":  [ recursive component tree ],
  "headers": { "timer":…, "timer_config":{expected_processing_time_sec, max_processing_time_sec},
               "site_name":…, "customer_name":…, "defer_to_lane":…, "park_work_item":… } }
```
Each layout node carries `component_type` (discriminator), `config_panel_uuid`,
`alias_name:{name, alias_selector}` (selector namespace, e.g. `$.screen.row_main`),
`visibility:{condition_selector[], condition}`, type-specific props, and `children[]`.
Containers are `row → column` on a 12-col grid.

- **Identity + SLA are columns; layout is the blob.** `manual_inputs` additionally carries
  the SLA trio (`below_expected/expected/max_processing_time_sec`, `*int`, nullable) —
  **`display_kiosks` has none.** The SLA values are **derived from
  `ui_description.headers.timer_config` on every save** (`screen_builder_service.go:1821-1834`),
  not authored independently; two on-disk shapes exist (flat and nested `{time,color}`) and
  `color` is authored but **never persisted** (no column).
- **Save API:** screen-builder-service `PUT /screen` (`routes.go:134`), one document,
  `ui_description` as an object the backend marshals to a string.
- ⚠️ **Live-read defect, confirmed** (`screen_builder_repository.go:262,294`):
  `FetchScreenData` predicate is `<uuid>=? AND is_deleted=false` — **no version predicate**,
  and it **omits `is_active`** (a deactivated screen still renders at runtime; the builder's
  own read at `:1511` filters both). Editing a screen changes what an in-flight execution
  sees on its next fetch. The codebase versions *workflows* (`workflow_entity.go:13`) but
  deliberately not screens.
- **Renderer reality (corpus claim corrected):** there are **three** diverged
  `switch(component_type)` dispatch tables — gate/responseBuilder (35 of 36 types), pwa (26),
  kiosk (22, cannot render `stepper`/`textarea` at all, all `default: return null` silent
  drops) — plus one **vestigial** component set in gate/screenBuilder that imports the gate
  table. Not "four copies." The builder's live preview reuses the gate table, so builder and
  operator console agree; pwa and kiosk do not.
- **35-vs-36 resolved:** the 36th type is `header`, authored via a header panel (not
  draggable) and stored under `headers`, not `layout`. 35 = layout-renderable types.
- Builder palette is gated by screen type: KIOSK 20 items, WEB_SCREEN 16 (no Advanced
  category), WORKITEM 24.

**2.0 state:** `core.screen` (V109) already carries the **identity + SLA half** —
`below_expected_sec/expected_sec/max_sec`, renamed, now `CHECK > 0`, unique per
`(site, process_definition_key, node_reference)` — and its header **explicitly disclaims
layout**: *"screen IS ONLY THE SCREEN'S IDENTITY … does NOT carry its layout, its fields or
its components."* **There is no layout store on `develop`** (verified: no
`screen_layout`/`screen_definition`/component table in any migration). **This stream builds
it.** Behaviour source: the architecture (U1 — one component registry every renderer
imports, a schema version in the artifact root, snapshot-at-publish §A4). Do not reproduce
the three-renderer divergence; do not reproduce the live-read.

---

## 8 · Defect catalogue — do NOT carry these forward

Evidence-backed; each is a thing 2.0's design already avoids or must avoid.

| # | Defect | Evidence | 2.0 stance |
|---|---|---|---|
| D1 | **Live-read execution, no version predicate** | `workflow_executor_repo.go:745` + all 10 node fetches | Reversed by compile-at-publish + immutable deployment |
| D2 | 60 s topology cache → two instances traverse two graph versions mid-window | `workflow_executor_repo.go:127-131,180-183` | Frozen version binding removes the hazard |
| D3 | **Hard delete of design nodes** | `work_flow_creation_repository.go:5703-5704` | Soft-delete everywhere; a published version is immutable |
| D4 | `node_links` is global, unconstrained, **unindexed**, no `workflow_id` | `nodes_entity.go:197-211`; absent from all index migrations | Edges belong to a workflow, scope-led index, FK |
| D5 | Guaranteed **orphan links** — deletion never cleans `node_links`, relies on the browser | `cleanupNodeReferences` switch `:7735-7759` omits links | Referential integrity in the DB, not the client |
| D6 | **No uniqueness on node UUIDs** — the only join key | index specs `migration.go:5486-5712` carry none for node UUIDs | Unique constraints day one |
| D7 | Inconsistent soft-delete columns across sibling tables (some both, some one, some neither) | per-entity tags | Uniform `deleted_at` |
| D8 | Filtering inconsistent even where columns exist (`FetchProcessNextStates` omits them) | `workflow_executor_repo.go:3282` vs `:4575` | Scope seam applies it by construction |
| D9 | **SQL built by string interpolation** on the design write path; UUIDs interpolated raw | `work_flow_creation_repository.go:2926,2950,4649,4673` | Parameterised only; the seam forbids raw JDBC |
| D10 | Design writes split across **three** services incl. a duplicated publisher | executor `work_flow_creation.go:148-1068` | One writer: core's design module |
| D11 | **Both `/publish` endpoints on unauthenticated routers**; `created_by` hardcoded `"admin"` | screen-builder `routes.go:106`, executor `:157` | (→ private security doc; 2.0 authorization is a separate ruling) |
| D12 | Polymorphic FKs with constraints deliberately dropped | `migration.go:8538-8580` | Model subflow references explicitly |
| D13 | Divergent delete semantics for the same entity across two services | §7 | One delete path |
| D14 | `is_valid` has no execution teeth (executor never reads it) | written `:5089`, read only in assign | Publish validation is a hard gate |
| D15 | No DB guarantee of one DEPLOYED workflow per lane | non-unique index `migration.go:5620-5622` | Unique where the invariant demands it |
| D16 | Dead code carrying a latent bug (`FetchAssociatedWorkflow`, `id` column that doesn't exist) | `io_executor_repo.go:197,296` | Do not port dead code |
| D17 | Kiosk workflow resolution non-deterministic (no `status`/`ORDER BY`) | `pwa-kiosk-service/kiosk_repository.go:74-84` | Deterministic resolution |
| D18 | Schema entirely AutoMigrate-derived; no CHECK/NOT NULL beyond tags; a duplicated migrate entry; a two-fields-one-column copy-paste (`WorkflowExtractionDetails.IsDeleted` → `is_active`) | `migration.go:100-297`; `workflow_entity.go:501` | Explicit migrations, CHECK constraints, BIN2 collation on enums |

---

## 9 · Gaps — explicitly NOT established (do not fill by guessing)

- **G1** — where the *layout* store lives in 2.0: it does not exist yet; this stream
  designs it. The 2.0 publish payload does not carry geometry/handles/`node_config` and no
  branch doc says where the builder keeps them.
- **G2** — the estate corpus (the full set of PLT designs the compiler is tested against)
  is **generated, not committed** (`ORCA_ESTATE_CORPUS`); its size/breadth is unknowable
  from the repo. Only six goldens are in-tree.
- **G3** — `visit_mission_workflow_mappings` and `workflow_configuration_mappings` have no
  reader/writer found; intent unknown. Do not carry without a live use.
- **G4** — `DeleteProcessRecord` (`process_repository.go:880`): not read in full; whether
  it blocks deleting a subflow still referenced by a live `process_nodes` row is unverified.
- **G5** — ingress/gateway auth in front of the 1.x unauthenticated `/publish` routers not
  inspected; the D11 claim is "no service-level middleware", not "publicly reachable."
- **G6** — 1.x's `DRAFT` constant (`common/utils/constants.go:156`) is defined but its use
  for workflows was not traced; do not assume 1.x has a draft state without confirming.

---

*Companion: `docs/stream-5-plan.md` (the plan), `docs/BPMN_EXECUTION_PROFILE.md` (what the
compiler may emit), `docs/ORCA_ARCHITECTURE.md` §A3/§A4/§C1 (design, screens, publish). The
2.0 compiler lives on `feature/OCS-4-runtime-migration` until merged.*
