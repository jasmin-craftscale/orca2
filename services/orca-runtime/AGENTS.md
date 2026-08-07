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

**It is unproven against the engine.** Register item #11 folds Spike 1 into Phase 1
as Work Package 0: the property is proven first, against real Flowable 8 and real
SQL Server, under a signed halt condition — if WP0 cannot pass, Phase 1 stops and
reports. See `docs/phase-1-plan.md`. Do not build on the assumption that it holds.

Phase 0 wrote out Flowable's `database-schema-update: true` visibly rather than
leaving it invisible, and nobody owns migrating the engine's ~46 tables — an open
contradiction recorded in `phase-0-report.md` §7.4, not a settled decision.
