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

## The modules are empty, and that is the design

Phase 0 put no business logic anywhere: the five modules hold 20 `package-info`
files and nothing else. Two consequences you will hit on the first real class:

- Both module-wall tests carry `allowEmptyShould(true)`, because the wall had to
  land **with** the module structure it governs rather than after the first class
  arrived. ArchUnit would otherwise fail a rule that checked nothing.
- `ImportedSetGuard.whatIsStillEmptyIsStated` asserts each module still has zero
  classes, so the exemption cannot outlive its reason. **Adding the first class to
  a module fails that test on purpose.** Remove `allowEmptyShould(true)` from
  `ModuleWallRule` and delete the test — do not relax the assertion to make it
  pass.

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
which `src/main` cannot. WP6 places it in `execution` behind the scope seam.

**The engine does not migrate itself.** `flowable.database-schema-update: false`,
and its 45 tables are `V110`–`V114` — extracted from the jars on the runtime
classpath by `./gradlew :services:orca-runtime:extractFlowableSchema`, never
hand-written and never downloaded. `FlowableSchemaUnderFlywayIT` builds the schema
both ways and asserts the tables, columns, indexes and foreign keys are identical.

⚠️ **Upgrading Flowable does not mean re-extracting those files.** They have
shipped, and §B7's expand-only discipline means a migration is never edited after
it does. Extract that version's `upgradestep` scripts as *new* migrations.
