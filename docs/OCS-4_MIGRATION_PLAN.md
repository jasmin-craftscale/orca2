# OCS-4 runtime → current project — migration plan (for approval)

**Prepared 10 Aug 2026. Plan only — nothing in the repo has been changed.**
Decisions you already made: **merge** OCS-4 into the existing `services/orca-runtime`; **conform
to the current project's flat structure**; **plan first, execute after approval**.

Source: `Lynxis-Gate/services/orca-runtime` (branch `feature/OCS-4-runtime`), per
`docs/OCS-4_RUNTIME_HANDOFF.md`.
Target: `orcs-rewrite` (`services/orca-runtime`, already built through Phase 3).

The good news first: **both trees use the same base package `com.lynxis.orca.runtime.*` and the
same internal module names** (`execution`, `integration`, `workitem`, `notify`, `readmodel`). So
the *layout* mapping is largely one-to-one. The work is not moving files — it is **reconciling two
persistence philosophies and satisfying ten build checks**, and that is where the effort and the
decisions are.

---

## 1 · The one finding that shapes everything

**OCS-4 persists with JPA `@Entity` + Spring-Data repositories, and enforces tenancy with SQL
Server Row-Level Security.** The target **forbids all of that**:

- `ScopeSeamRule` fails the build if any service class touches `EntityManager`, `JdbcTemplate`,
  `JdbcClient`, `DataSource`, raw JDBC, **or extends a Spring Data repository**. Every read goes
  through `platform/scope`.
- The target has **no RLS migration**. Tenancy is the scope seam — a site predicate injected
  *before* your filter, returning zero rows when no scope is set. OCS-4's `V002` RLS +
  `TenantContextHolder.runAs` + `TenantBindingJobRunnableFactory` (binds RLS on engine job threads)
  is a different model entirely.

Concretely, these OCS-4 files are **incompatible as written** and must be rewritten onto the seam,
not copied:

- `execution/internal/persistence/` — `WorkflowExecutionEntity/Repository`,
  `NodeExecutionEntity/Repository`, `VisitDatasetEntity/Repository` (JPA + Spring Data)
- `integration/.../ConnectorConfig` reads, `designer/PublishedGraphReader` (JDBC against the
  external DB), `internal/selector/RuntimeSelectorDataProvider` (data access)
- every place that assumes `sp_set_session_context` provides isolation

**This is the single biggest work item and the main risk.** It is a rewrite of the data layer, and
the target's `AdmissionService`/repositories are the reference pattern to follow
(`execution/persistence/AdmissionRepository.java` shows the seam idiom).

> Decision D1 (below) asks whether you want to (a) rewrite OCS-4 persistence onto the scope seam
> — the conforming path — or (b) introduce RLS into the target as a second mechanism (a product /
> architecture change, and it collides with the register's NEW-1a/§B6 discussion).

---

## 2 · Structure mapping (OCS-4 → target)

OCS-4 is multi-module; the target is one flat module per service plus a shared top-level
`platform/`. Mapping:

| OCS-4 module | → Target location |
|---|---|
| `orca-platform/orca-platform-tenancy` (TenantContext/Holder) | `platform:scope` — reconcile with the existing seam; **do not** add a parallel tenancy API |
| `orca-platform/orca-platform-persistence` (tenancy interceptor) | Folds into `platform:scope`; RLS interceptor likely dropped (see D1) |
| `orca-platform/orca-platform-web` (`ApiError`) | `platform:web` (already exists — reconcile with `ApiResponse`/error envelope) |
| `orca-platform/orca-platform-test` | test fixtures under the relevant module's `src/test` / `testFixtures` |
| `orca-selector` (9 files, `com.lynxis.orca.selector`) | New package inside the runtime module — proposed `execution/selector` (it is execution-time evaluation). Keep package `com.lynxis.orca.selector` **or** rehome; see D3 |
| `orca-runtime/runtime-execution` (compiler, engine, delegates, persistence, selector glue) | `runtime/execution/*` (merge — see §3) |
| `orca-runtime/runtime-integration` (connector, designer, mirror) | `runtime/integration/*` (merge — see §3) |
| `orca-runtime/runtime-workitem` | `runtime/workitem/*` — target already has the mature Phase-3 version; **OCS-4's is likely older, reconcile or skip** |
| `orca-runtime/runtime-notify` | `runtime/notify` — both **empty**; nothing to move |
| `orca-runtime/runtime-readmodel` | `runtime/readmodel` — both **empty**; nothing to move |
| `orca-runtime/runtime-app` (Spring Boot app, controllers, wiring) | `runtime/` top-level (`RuntimeApplication`, `*Configuration`) + `*/api` controllers — reconcile with existing app wiring |
| `tools/flowable-shadow/` (comparator, estate exporter, FidelityCheck) | **Decision D5** — these are the fidelity *oracles*; they are Go-shadow tooling with no place in the shipped product, but losing them loses the ability to prove the compiler/selector. Proposed: keep as a **separate non-shipped Gradle build** or a test-only module, never linked to the service. |

---

## 3 · What OCS-4 brings, bucketed

### 3a · Net-new and high-value (the reason to do this)

- **The ORCA→BPMN compiler** — `execution/compiler/`: `BpmnIr`, `CanonicalBpmnXml`,
  `ConditionJuel`, `DesignerGraph`, `DesignerJsonCompiler`, `DesignerLint`, `DesignerWorkflow`,
  `IrEmitter`, `OrcaCondition`, `CompileException`. **The target has no compiler** — it runs one
  hand-written `gate-visit.bpmn20.xml`. This is the headline gain. Invariants I1–I9, byte-identical
  canonical BPMN, six frozen PLT goldens.
- **The selector evaluator** — `orca-selector/` (incl. the 94 KB `SelectorEvaluator.java`),
  `GoFmt`, `GoJson`, `OrcaComparisons`, proven 568/568 against Go. New to target.
- **Connector breadth** — `integration/connector/`: `HttpConnectorGateway`, `ConnectorCatalog`,
  `FieldMappingResolver`, `UrlTemplate`, `MappingField`, credentials port (`NoAuthCredentials`
  wired, `EncryptedConnectorCredentials` **unwired — security gate**). Target has only a minimal
  `RestConnector`. **This is Stream 1's declared scope** (see §7).
- **The designer/published-graph reader** — `integration/designer/`: `PublishedGraphReader`,
  `PublishedGraph`, `PublishPayload`, `NodeSource`. Reads the saved graph so validation == deploy.
- **New execution persistence & facades** — `execution/api/` (`CompilationFacade`,
  `ExecutionFacade`, `SelectorNamespace`, `ValidationReport`), `execution/internal/`
  (`DefinitionRegistry`, `RuntimeExecutionFacade`, `VisitDataWriter`), `execution/internal/selector/`
  (`NamespaceService`, `RuntimeSelectorDataProvider`, `SiteCatalog`).
- **New tables** — `visit_dataset`, `node_execution_payload` (and correlation) — not present in the
  target today.
- **Richer engine seam** — `execution/engine/`: `WorkflowEngine` abstraction, `fake/InMemoryWorkflowEngine`
  (fast tests without Flowable), `flowable/` (`FlowableWorkflowEngine`, `NodeExecutionRecorder`,
  `OrcaElFunctions`, `ClerkWorkBridge`, `FlowableStartupGuard`, `TenantBindingJobRunnableFactory`).

### 3b · Overlaps to reconcile (both sides have these — do not duplicate)

| Concern | Target has | OCS-4 has | Reconciliation |
|---|---|---|---|
| Admission | Mature `AdmissionService` + WP0/WP6 proof (the property the design turns on) | `execution/admission/` is **package-info only**; real one is `mirror/MirrorAdmission` | **Keep target's.** Do not regress admission. |
| Engine gateway | `ProcessEngineGateway` + `FlowableProcessEngineGateway` | Richer `engine/` abstraction + `FlowableWorkflowEngine` | Merge OCS-4's richer seam **without** breaking `EngineConfinementRule` (only `execution` may touch `org.flowable`). Keep target's admission wiring. |
| Delegates | `ConnectorCallDelegate`, `DeviceCommandDelegate`, `VisitCompletion*`, `WorkItemCreation/Sla` | `delegate/` + `delegate/spi/` + `delegate/support/` (display, notification, SPI seams) | Superset-merge; OCS-4 adds display/notification delegates and clean SPI. |
| Connector | `integration/domain/RestConnector`, `ConnectorConfigRepository` | Full `connector/` gateway | Replace minimal with OCS-4's, on the seam (D1). |
| Work items | Full Phase-3 (`workitem/*`, V115/V116) | `runtime-workitem` (14 files, likely older) | **Prefer target's**; migrate only genuinely-new pieces. |

### 3c · Probably do NOT migrate (confirm)

- `integration/mirror/` — `DeviceEventMirrorConsumer`, `MirrorAdmission`, `MirrorCatalog`,
  `MirroredConnectorGateway`, `MirrorEstateDeployer`. This is **Go parallel-run/shadow** tooling.
  The target is a greenfield product with no Go to shadow (there is no migration — roadmap §6).
  Proposed: **do not migrate** into the service (keep only if you want it as a throwaway
  verification harness — see D5).
- `tools/flowable-shadow/` comparator/exporter/FidelityCheck — keep as **oracles** outside the
  service build (D5).

---

## 4 · Migrations

Target `runtime` schema is at **V117** and already contains: execution + connectors (V101/V102),
Flowable (V110–V114), work items (V115), presence (V116), collation (V117). OCS-4's `V001–V007` are
a *different numbering universe* and mostly already exist in the target:

| OCS-4 migration | Target status | Action |
|---|---|---|
| V001 core | ≈ V100/V101 | none |
| **V002 RLS** | **absent by design** | **D1** — drop (conform to seam) or introduce (product decision) |
| V003 vendored Flowable DDL | V110–V114 (extracted) | none |
| V004 correlation | check target | new migration if absent |
| V005 work_item | V115 | reconcile schema; likely none |
| **V006 visit_dataset** | **absent** | **new migration** |
| **V007 node_execution_payload** | **absent** | **new migration** |

⚠️ **Numbering is governed and cross-stream.** `docs/MIGRATION_NUMBER_RANGES.md` assigns runtime
`V118–V137` to **Stream 1**, `V138–V157` to **Stream 2**, `V158–V167` to **Stream 4**;
`V168–V199` is reserved. This merge is **not one of those streams**, so it must be given a band
(**D4**) — do not borrow. New tables (`visit_dataset`, `node_execution_payload`, correlation) take
numbers in the assigned band, as *new* migrations; existing shipped migrations are never edited.

---

## 5 · Build-check compliance (the ten gates)

| Check | Risk from OCS-4 | Work |
|---|---|---|
| **ScopeSeamRule** | **High** — JPA entities + Spring-Data repos + RLS | Rewrite persistence onto the seam (§1, D1) |
| **RetentionClassRule** | `@Entity` classes need `@PersistentTable(growth=…)` + `@RetentionClass` | Annotate `visit_dataset`, `node_execution*`, `workflow_executions` entities/tables |
| **EngineConfinementRule** | Only `execution` may import `org.flowable` | OCS-4 keeps Flowable in `execution/engine/flowable` ✓; verify selector/EL stays there |
| **ContractInterfaceRule** | `DesignerValidationController` (`/api/designer/validate`, `/namespace`) must implement a generated interface | Add these ops to `openapi/orca-runtime.yaml`, regenerate, implement generated iface |
| **ErrorEnvelopeRule** | Controllers must return `ApiResponse`/generated envelope | Adapt OCS-4 controllers (they use `ApiError`) |
| **InternalSurfaceRule** | `/internal/**` mapping vs public | Place designer endpoints correctly (validate/namespace are console-facing → public `/api`) |
| **ScopeIndexRule** | Every table with `site_external_id` needs a scope-leading index | Add to new migrations |
| **PlatformPurityRule** | `platform/` must not name domain | Ensure tenancy fold-in adds no domain nouns to `platform:scope` |
| **ModuleWallRule** | Cross-module reach | Route cross-module needs through `api` packages / a `readmodel` projection |
| **SystemContextRule** | `@Scheduled` runs under identity | Any OCS-4 background job (mirror consumer, job runnable) must use `SystemContext` — or is dropped with mirror |

⚠️ **Version pins.** OCS-4 is Java **21** / Boot **4.1.0** / Flowable **8.0.0** / Jackson 3; the
target is Java **25** / Boot 4 (top-level `gradle/libs.versions.toml`). The compiler's **byte-identity
BPMN goldens are version-sensitive** — moving to Java 25 / the target's Flowable pin may shift
canonical bytes. **Re-run the golden gate after the move and hand-review any diff** (it is a reviewed
event, not a rebase artifact). Confirm the target's Flowable pin equals 8.0.0 before trusting the
goldens.

---

## 6 · Do NOT migrate (from handoff §5, §12)

- `docs/analysis/` — **signed client contract PDFs**. Never copy anywhere.
- `.devcontainer/`, Dockerfile BuildKit cache-mount patches, `common/keycloak/service.go` test
  patch, local `.gitignore` additions — local-only dev aids.
- Commit trailer: OCS-4 uses `Assisted by AI`; **the current repo's convention differs** — follow
  `orcs-rewrite`'s trailer style, not OCS-4's. (Confirm in D-notes.)

---

## 7 · Governance — surface, do not settle (per DEVELOPER_ONBOARDING §6, §8)

This merge crosses **three streams' territory**, which the onboarding says to flag rather than
absorb silently:

- **Connector breadth + designer/partner path = Stream 1's declared scope** (`stream-1-plan.md`,
  owns `integration`).
- **notify / readmodel = Stream 2's scope** (both empty here — no conflict yet, but populating them
  later is Stream 2's).
- **The compiler is in no stream.** Roadmap §5: it "needs the developer building the visual
  builder, working from `BPMN_EXECUTION_PROFILE.md`, which is still *proposed* and never reviewed."
  Bringing a working compiler in is a **scope-changing event**, not an implementer's call.
- **`EncryptedConnectorCredentials` is security-shaped** (handoff ruling 5): written, **unwired,
  untested**; must not be wired until a human reviews the four listed properties (CFB authenticates
  nothing; per-call OAuth; credentials over http allowed — the PO reversed a TLS-only refusal). Bring
  it in **unwired**, as-is.
- **`executeQuery` / reference-data SQL and the `SiteCatalog.UNBOUND`-throws behaviours** are
  deliberate refusals awaiting product rulings — carry them as refusals, do not "fix".

---

## 8 · Proposed phased sequence (each phase ends green)

Verification note: I can run `./gradlew build` (compile + unit + the ten checks) **in the cloud
without Docker**; `check integrationTest` (222+ tests, real SQL Server) needs Docker and is your
local run. Each phase below is written to end at a green `build`.

1. **P0 — Scaffolding & versions.** Confirm Flowable/Boot pins; wire `orca-selector` package;
   no behaviour. Green `build`.
2. **P1 — Selector.** Port `orca-selector` (no DB) + unit fixtures (337 regression + FidelityCheck
   as an env-gated tier). Pure logic; no seam impact. Green.
3. **P2 — Compiler.** Port `execution/compiler/` + `api` facades + the six PLT goldens as
   env-gated golden tests. Re-run golden gate; review any byte diff. Green.
4. **P3 — Engine seam.** Merge `execution/engine/` (incl. fake engine) with target's gateway;
   keep `EngineConfinementRule`. Green.
5. **P4 — Execution persistence on the seam (the big one, D1).** Rewrite
   `workflow_executions`/`node_executions`/`visit_dataset` onto `platform/scope`; add migrations in
   the assigned band; annotate retention classes. Green `build`; **you** run `integrationTest`.
6. **P5 — Connector breadth.** Port `integration/connector/` onto the seam; bring
   `EncryptedConnectorCredentials` **unwired**; refuse unsupported shapes as OCS-4 does. Green.
7. **P6 — Designer reader + validation endpoints.** Port `integration/designer/`; add
   `/api/designer/validate` + `/namespace` to the contract, regenerate, implement. Green.
8. **P7 — Delegates & wiring.** Superset-merge delegates; reconcile app config; ensure mirror is
   excluded (D5). Green; full `check integrationTest` on your side; drive a truck.

Each phase is one reviewable branch. I do the work in a cloud copy of the repo, get `build` green,
then write the diff back to your repo per phase for your local `integrationTest`.

---

## 9 · Decisions I need before executing

| # | Decision | Why it blocks |
|---|---|---|
| **D1** | Persistence/tenancy: **rewrite OCS-4 onto the scope seam** (conforming, recommended) or **introduce RLS** into the target? | Determines whether P4 is a rewrite or an architecture change; RLS collides with register NEW-1a/§B6 — product-owner territory |
| **D2** | Confirm the target's **Flowable pin is 8.0.0** and Java 25 is acceptable for the **byte-identity goldens** (any diff hand-reviewed) | The compiler's proof is version-sensitive |
| **D3** | `orca-selector` home: keep package `com.lynxis.orca.selector`, or rehome under `runtime/execution/selector`? | Package + module-wall placement |
| **D4** | **Migration band** for this merge's new tables (it is not Stream 1/2/4) — assign a band or confirm reserved `V168+` | Numbering is governed; borrowing breaks another stream |
| **D5** | Fate of `mirror/` and `tools/flowable-shadow/` — drop, or keep as non-shipped test-only oracles? | They are the fidelity oracles but are Go-shadow tooling |
| **D6** | Scope of `workitem` reconciliation — keep target's Phase-3 version and skip OCS-4's, or merge specific pieces? | Avoids regressing shipped work |
| **D7** | Cross-stream sign-off: the compiler (no stream) and connector breadth (Stream 1) — proceed as a merge, or coordinate with those streams' owners first? | Onboarding says surface cross-stream scope |

---

*Plan only. On approval (and D1–D7), I execute phase by phase in a cloud copy, keeping each phase
green on `./gradlew build`, and hand you diffs to run `check integrationTest` locally. If any claim
here conflicts with the code once I start, the code wins and I flag it.*
