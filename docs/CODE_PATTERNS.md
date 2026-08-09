# How this code is put together, and how to add to it

**The patterns that repeat. Follow the grain rather than inventing a second way of
doing something this codebase already does once.**

`docs/REPOSITORY_GUIDE.md` says where things live. This says what shape they take,
so a change you write looks like the code around it — and so a plan you write can
prescribe the shape rather than leaving it to whoever picks it up.

---

## 1 · A request, end to end

Every HTTP feature is the same six layers, in this order:

```
OpenAPI document          services/<svc>/src/main/resources/openapi/orca-<svc>.yaml
   ↓ generated at build
generated interface       ...api.generated.*Api        (never hand-written)
   ↓ implements
controller                api/*Controller.java         (thin: HTTP in, envelope out)
   ↓ calls
service                   domain/*Service.java         (the decisions, @Transactional)
   ↓ calls
repository                persistence/*Repository.java (SQL, and only here)
   ↓ through
the scope seam            platform/scope               (never a raw JdbcTemplate)
```

**Contract first, always.** Edit the OpenAPI document, regenerate, then make the
controller satisfy the generated interface. Change the contract and the build
breaks until the code matches — that is the mechanism, not an inconvenience.

**Controllers stay thin.** They translate HTTP to a call and wrap the answer in the
shared envelope. A controller with a decision in it is a controller doing the
service's job.

## 2 · Never check, then act

**The most important pattern in this codebase.** Wherever two instances could race,
the decision is made *inside a single guarded SQL statement*, and the caller learns
what happened from what that statement returned.

Three examples, all the same idea expressed slightly differently:

| Where | The guard | How the caller learns |
|---|---|---|
| Lease acquisition | `UPDATE … WHERE expired OR same holder`, with `OUTPUT` | An empty result means somebody else holds it |
| Idempotency | `UPDATE … WHERE status = 'IN_PROGRESS'` | `updated == 0` means it was already recorded |
| Work-item claim | `UPDATE … WHERE status = 'QUEUED'` | Rows-affected is the only guard; the loser gets a typed conflict |

**What this rules out:** reading a row, deciding in Java, then writing. Between the
read and the write, the other instance does both. Pre-checks may exist for a nice
error message, but they are never the guard — and comments in the code say so where
it matters.

**A corollary:** the database's clock decides anything two instances must agree on.
Never a JVM clock. Expiry comparisons live inside the statement.

## 3 · The five primitives, and when to reach for each

| You need | Use | The rule |
|---|---|---|
| To tell another service something happened | `platform/outbox` | The fact and its outbox row commit **in one transaction**. Writing outside a transaction is refused |
| Only one instance may do this at a time | `platform/lease` | A lease with a fence token. Holding it is not permission to write — the token is checked in the write |
| To read or write any table | `platform/scope` | The seam applies the site condition before your filter. No scope set means **zero rows**, never all rows |
| A command that might be retried | `platform/idempotency` | A replay returns the **recorded outcome**, never "duplicate" — the caller retried because it never saw the answer |
| To return anything over HTTP | `platform/web` | One envelope, machine-readable codes, no internal detail. Background work enters an explicit system identity |

**Nothing in `platform/` may name a visit, lane, ticket, driver or truck.** A build
check enforces it. If a primitive needs to know about the domain, the design is
wrong somewhere else.

## 4 · The shape of a migration

Every table that grows with traffic needs all four of these, and checks enforce the
first three:

1. **A growth declaration** — `@PersistentTable(growth = TRAFFIC_GROWING)` or `BOUNDED`. No default; you must choose.
2. **A retention class** if it is traffic-growing — otherwise the build stops.
3. **An index leading with the scope column.** Every read leads with the site
   condition, so a table without one can only be scanned — and a scan under the lock
   the gate path uses locks *every row at the site*. This deadlocked an eight-lane
   run once; that is why a check reads the migrations.
4. **Binary collation on enum `CHECK` constraints** — `COLLATE Latin1_General_100_BIN2`.
   The database is case-insensitive, so a bare `CHECK (x IN ('PUSH','PROMPT'))`
   silently accepts `'push'`. Six migrations carry this; copy the shape.

**A migration that has shipped is never edited** — not for a bug, not for a comment.
Fix it with a new one. Editing changes its checksum and every database that ran it
refuses to start.

## 5 · The engine is confined

Only `orca-runtime`'s `execution` module may touch the workflow engine's API, and a
build check enforces it. Everything else reaches it through `ProcessEngineGateway`.

Two engine facts worth knowing before you design anything around it:

- **A boundary timer on a service task cannot fire** — the job is created and
  deleted inside one transaction, so nothing else ever sees it. On a wait state
  (`userTask`) it fires, survives a restart, and fires once.
- **Work-item creation and visit completion are engine event listeners**, not steps
  in the process. A step a designer could delete would produce a process that runs
  perfectly and leaves every visit open forever.

## 6 · What a test looks like here

> *"The outbox writes a row" is not a test — "killing the process between the two
> writes leaves neither" is.*

The idioms that recur:

- **Fault injection.** Poison a table, assert both halves of a transaction unwind, heal it, assert both then apply.
- **True concurrency.** Two threads racing the same claim, a thousand times, asserting exactly one winner.
- **Watched to fail.** Before trusting a new check or assertion, break the thing on purpose and see it caught. A check nobody has watched fail may not be wired in — this repository has shipped exactly that.
- **State the zone, the clock, the collation.** Several defects here were invisible on the machine that wrote them. Where a test could pass by accident on your laptop, force the condition.

## 7 · The anti-patterns the build already refuses

Useful to know before you write, rather than after the build stops you:

- A `JdbcTemplate`, `EntityManager` or `DataSource` in a service class
- A controller that implements no generated interface, or returns something other than the envelope
- A module reaching into another module's internals
- A `@Scheduled` method that does not enter a system identity
- A traffic-growing table with no retention class, or no index leading with the scope column
- An internal endpoint outside `/internal/**`, or a path there without the internal tag

## 8 · Where to read, in order, to understand this properly

1. `platform/scope/…/JdbcScopeSeam.java` — the seam every read passes through, and the deny-by-default behaviour.
2. `platform/outbox/…/OutboxRelay.java` — the claim, the per-key ordering, and why acknowledgement happens after delivery.
3. `services/orca-runtime/…/execution/domain/AdmissionService.java` — one truck, one visit. The clearest example of how this codebase reasons about concurrency, and its comments explain the ordering that was found by measurement.
4. `services/orca-runtime/src/main/resources/processes/gate-visit.bpmn20.xml` — the process the platform exists to run, written as a compiler would emit it.
5. `build-checks/src/test/java/com/lynxis/orca/checks/` — all of them. They are the architecture as executable constraints, and reading them tells you what the codebase will and will not permit.

---

*If a pattern here disagrees with the code, the code is right and this is a defect
— say so.*
