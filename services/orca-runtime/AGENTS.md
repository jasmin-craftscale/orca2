# `services/orca-runtime` — the gate brain

The one decomposed service. Five modules, named by `ORCA_ARCHITECTURE.md` §C2 and
depended on by name in `build-checks`' `OrcaClasses.RUNTIME_MODULES`:

| Module | Owns |
|---|---|
| `execution` | Executions, visits, node executions, payload storage |
| `workitem` | Work items, routing, the audit trail |
| `integration` | Connector configuration, event dispatch, the partner API |
| `notify` | Notifications, the WebSocket hub, push |
| `readmodel` | Projections only — nothing else |

It is by far the largest service, deliberately. **§C2 gives the reason and it is
worth reading before proposing a split** — the short version is transaction
cohesion, and §C2's argument is what any such proposal has to answer.

## The walls are enforced, not agreed

`ModuleWallRule` fails the build when one module depends on another module's
`persistence` **or** `domain` package. Domain is in scope because the same defect
arrives one layer up: a module constructing another module's entities is coupled
to its schema just as tightly, and it is what a developer reaches for once
`persistence` is closed to them.

**Cross-module data needs go through a `readmodel` projection** — never another
module's tables, and never another module's entities. §C2 works one example
through (the lane monitor, which needs two modules' data); reach for that shape
rather than inventing an exception.

## One module has code. Four do not, and the checks say which

WP4 put the first classes into **`execution`** — the two delegates, the engine
gateway and its Flowable adapter — and WP6 added admission there: `AdmissionService`,
`AdmissionRepository`, `DeviceEventController` and the `V101` tables they use.
`workitem`, `integration`, `notify` and `readmodel` still hold nothing but
`package-info`.

- Both module-wall tests still carry `allowEmptyShould(true)`, because four of the
  five sets are still empty and ArchUnit fails a rule that checked nothing.
  **Remove it once every module is populated, not before.**
- `ImportedSetGuard.whatIsStillEmptyIsStated` states exactly that position: it
  asserts `execution` **has** classes and that the other four have none. Adding the
  first class to one of those four **fails it on purpose** — remove that module
  from its list. Do not delete the test while any module is still empty, and do not
  relax the assertion to make it pass.

⚠️ An earlier version of this file said to delete that test outright on the first
module class. That instruction assumed all five modules would populate at once;
they did not, and deleting it would have removed the record of four rule sets that
really are still empty. Recorded in `phase-1-report.md` §5.11.

## Admission is the property this design turns on

Two device events for one truck, same instant, two instances: **exactly one visit
starts.** That is the single hardest property in the service and every inbound
path depends on it. Read §C2's *Admission* subsection before writing anything near
it — it constrains the shape of the operation, not just its outcome.

**It is proven, and the proof is executable.** WP0 ran it against real Flowable 8
and real SQL Server: 1,000 iterations, two simultaneous events each, exactly 1,000
visits. `AdmissionPropertiesIT` in `src/integrationTest/` is that proof — an
UPDLOCK on the lane's `lane_session` row, a filtered unique index as the backstop,
and the engine start inside the same transaction as the insert. **Change any of
those three and read the test before deciding it still holds**; the backstop in
particular is only known to work because the suite removes the lane lock and
watches it fire.

That code is WP0 shape, not shipping placement: it uses `JdbcTemplate` directly,
which `src/main` cannot. **WP6 placed the shipping operation in `execution` behind
the scope seam** — `AdmissionService`, proven through HTTP by
`AdmissionThroughHttpIT` in both lane shapes (8 lanes × 125, and 1,000 consecutive
trucks through one lane). WP0's suite stays: it carries the deliberate lock bypass
that makes the backstop observable, and that is not a mode the shipping operation
has. Two shapes of the same tables is a real cost, recorded in `phase-1-report.md`.

⚠️ **Two ordering facts admission depends on, both found by measurement:**

- `lane_session` is keyed **`(site_external_id, lane_id)`**, in that order. Every
  seam read leads with the scope predicate, and a key that does not lead with the
  scope column makes SQL Server scan — which under `UPDLOCK` locks *every lane at
  the site*.
- The lane lock is taken **before** the idempotency claim. The other order
  deadlocks: two events for one truck each hold their own claim while contending
  for the lane. Coarse resource before fine, on every path.

**The engine does not migrate itself.** `flowable.database-schema-update: false`,
and its 45 tables are `V110`–`V114` — extracted from the jars on the runtime
classpath by `./gradlew :services:orca-runtime:extractFlowableSchema`, never
hand-written and never downloaded. `FlowableSchemaUnderFlywayIT` builds the schema
both ways and asserts the tables, columns, indexes and foreign keys are identical.

⚠️ **Upgrading Flowable does not mean re-extracting those files.** They have
shipped, and §B7's expand-only discipline means a migration is never edited after
it does. Extract that version's `upgradestep` scripts as *new* migrations.
