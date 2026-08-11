# OCS-4 runtime migration — final report

The OCS-4 runtime rewrite (developed beside the 1.x system, handed over in
`OCS-4_RUNTIME_HANDOFF.md`) has been merged into this repository, conformed to
its structure, its scope seam and its build checks. Seven phases, each gated on
a green build run on the developer's machine; the schema and integration phases
additionally gated on the full `integrationTest` suite against real SQL Server
and real Flowable. This report is the definition-of-done's third item: what was
built, what was not, every decision the handoff did not dictate, and what looked
wrong — reported, not silently corrected.

## What was built

**The selector evaluator** (`execution/selector/`) — the Go executor's selector
grammar, ported with its conformance corpus: 337 regression fixtures and the
authored estate conditions, green on Java 25.

**The compiler** (`execution/compiler/`) — designer JSON to canonical BPMN, with
the six frozen goldens byte-identical to the reference output. Validation is
compilation: `DesignerJsonCompiler.validate` answers by really compiling.

**The engine seam's breadth** (`execution/engine/`) — one contract test, two
implementations (`InMemoryWorkflowEngine` for the fast tier, the Flowable
adapter against a real engine), clerk tasks, the startup guard, crash-resume and
claim-race properties.

**The step trace and the visit dataset** (V168–V170, `execution/persistence/`) —
`node_execution` and `visit_dataset` on the scope seam, written by the
engine-event recorder and the delegates in the engine's own transaction, read
back by the selector data provider. The recorder owns per-step trace rows and
CHILD execution lifecycle only: admission creates roots, the visit-completion
path closes them.

**The facade, the registry, the selector's live binding**
(`execution/internal/`) — `RuntimeExecutionFacade` (signal / cancel / inspect),
`DefinitionRegistry` (workflow uuid → deployed key, id derived from
`proc_<workflowId>`), `RuntimeSelectorDataProvider` answering the estate's three
question families plus the same-visit sibling lookup — implemented as the
platform's query, not its misleading method name.

**The designer-authored connector** (`integration/connector/`) — field-mapping
resolution with the Go behaviours pinned by test (the uuid asymmetry,
drop-versus-null, empty-array-is-null, Go boolean vocabulary, float32's
inability to match a JSON number), position-aware URL escaping with Go's two
disagreements with the JDK corrected, per-status dataset extraction, and the
loopback-socket gateway tests.

**The designer's validation surface** (`/api/v1/designer/validate` and
`/namespace`, contract-first) — the builder's per-edit validation and
autocomplete, plus the publish-payload assembly (`integration/designer/`) with
its node-type coverage tests.

**The compiled definitions' delegates** (`execution/delegate/`) —
`orcaConnectorDelegate`, `orcaDeviceEffectDelegate`, `orcaDisplayDelegate`,
`orcaNotificationDelegate` (bean names load-bearing: the compiler emits them),
over seams in `delegate/spi/` with fail-loud unconfigured defaults.
`CompiledDelegatesLiveIT` runs a compiler-shaped definition end to end on a real
engine: status routing, durable dataset, step-row payload, FAILED-versus-UNKNOWN
at the edge, and the write-behind guard.

## What was deliberately NOT built or ported

- **The 1.x mirror and the parallel-run machinery** (ruling D5): the mirrored
  gateways, the mirror catalog, the flowable-shadow oracles. With them went the
  shadow-mode suppression flag and gate (see decisions).
- **`ClerkWorkBridge` and the clerk SPI** (ruling D6): this repository's
  `workitem` module owns that path (`WorkItemCreationListener`,
  `WorkItemIntake`).
- **`PublishedGraphReader`'s SQL half and a live `ConnectorCatalog`**: both read
  the 1.x designer's tables, which do not exist here. Where 2.0 stores
  designer-authored workflows and connector configuration is an **open storage
  decision** — both are unbound ports that refuse by name until it is made.
- **`SiteCatalog`'s configuration half**: lanes, devices, aliases belong to
  other schemas; the port refuses by name until the adapter lands.
- **`EncryptedConnectorCredentials` was removed** (not ported forward): the
  `develop` merge brought a `platform/secrets` primitive (`SecretBox` — versioned
  AES-256-GCM seal/open on a managed, rotating key ring) that supersedes the
  bespoke cipher this class carried, so wiring it as-is would reinvent a platform
  boundary. `NoAuthCredentials` is the wired default; opening customer credentials
  is deferred to `platform/secrets` when the auth path is wired. The one thing the
  deleted class uniquely knew — reading **legacy 1.x Go ciphertext** (GCM + the
  unauthenticated CFB fallback) — is preserved in git history should a one-time
  import reader ever be needed; whether credentials are re-sealed into ORCA's
  format on import, or read legacy in place, is the open decision that governs it.
- **`sequence_counter`**: superseded by the platform's transactional outbox.
- **OCS-4's JPA/RLS tests** (`EntityLifecycleTest`, tenancy canaries): the seam,
  not RLS, is this repository's isolation mechanism (ruling D1);
  `TraceDatasetRoundTripIT`'s other-site-sees-nothing property replaces them.

## Decisions the handoff did not dictate

1. **`ExecutionFacade` lost `startExecution`.** Admission owns
   correlate-or-start; a second start door on the module's public surface would
   bypass the lane lock and its backstop. The only production caller was the
   mirror feed (not migrated).
2. **The shadow-mode flag and gate were dropped** (`suppressed` on
   `ConnectorRequest`, `ShadowModeGate`, `SideEffecting`). Their purpose was the
   parallel run; a flag nothing can set is a trap. The protection a
   misconfigured runtime needs is the pattern this repository already uses:
   unconfigured SPI defaults fail the step loudly by name.
3. **Child cancellation records status `COMPLETED`** — the `execution` CHECK
   constraint has no `CANCELLED` value. Widening a shipped constraint is a
   migration-owner's call, so the honest value available was recorded instead.
   Flagged, not settled.
4. **Observed autocomplete keys come from the newest 100 visits**, not a
   DISTINCT over all history — the seam offers neither joins nor DISTINCT, and
   for an autocomplete, keys nothing current writes are stale suggestions. The
   bound is proven by test.
5. **Designer routes live at `/api/v1/designer/*`** (repository convention),
   not OCS-4's `/api/designer/*`.
6. **`site_uuid`-shaped identifiers became `site_external_id`** throughout the
   ported surface, and OCS-4's `execution_uuid` is this schema's `external_id`.

## What looked wrong — reported, not corrected

- **Steps that complete inside the engine-start command cannot be traced for
  compiled processes.** Admission records `process_instance_id` after
  `engine.start` returns; the recorder (which fails a step it cannot correlate,
  deliberately) would fail an `n_`-prefixed start node. Nothing hits this today
  — the hand-written gate process has no `n_` nodes — but it becomes real the
  day compiled processes are admitted. The likely shape of a fix (correlate by
  business key, or pre-allocate the instance id) touches admission, so it is
  surfaced here rather than settled.
- **Two small jargon leftovers from early phases** (`compiler/package-info.java`
  still says "T2"; `CorrelationKeys` references a decision log entry). Comments
  only; sweep opportunistically.
- The 1.x verification pass found the open-questions register slightly stale in
  one place (`event_dispatch` retention); recorded in
  `OCS-4_1X_VERIFICATION.md`.

## Verification

Every phase closed on gates run on the developer's machine: `./gradlew build`
(compile, fast tests, the ten build checks) per phase, and
`./gradlew :services:orca-runtime:integrationTest` for the schema, engine and
delegate phases — 110+ property tests against real SQL Server and real
Flowable, including the admission race, the adoption procedure, the V168–V170
migrations, the trace/dataset round-trip and the compiled-delegate chain. Final
sign-off is the full `./gradlew check integrationTest` plus the demo: start the
local stack and drive a truck.
