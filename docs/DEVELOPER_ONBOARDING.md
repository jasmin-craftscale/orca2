# Developer onboarding — what this is, and how to work in it

**Read this once, in full, before writing anything. It is self-contained.**

Written for a developer joining ORCA 2.0 and for the AI assistant working alongside
them. If you are setting a machine up, `docs/LOCAL_DEVELOPMENT.md` comes first —
this document assumes the platform already runs for you.

---

## 1 · What ORCA does

It automates the gates of logistics facilities — container terminals and
distribution centres, where trucks arrive, are identified, are checked against the
operator's own systems, and are released.

At a lane: a camera reads a plate. The platform recognises the visit and runs the
process **the site's own administrators designed for that lane** — asking the
Terminal Operating System whether the truck is expected, reading a seal or a
document, printing a ticket, raising the barrier. Where automation cannot finish
something, the work goes to a clerk, tracked against a time target. Carriers
pre-announce visits through a driver portal.

### The two properties that shape every decision

> **The gate must keep working when other things do not.**
>
> A truck at a barrier is a physical queue. If the platform stops, the queue does
> not — it grows into the public road. So the design assumes the wide-area link
> will drop, a server will fail, and an integration will time out, and it states
> what happens in each case.

> **The site's processes belong to the site.**
>
> Terminals do not run the same process. Administrators design processes and
> screens visually, without code, and the platform stays true to that as those
> processes grow more demanding.

Almost every rule below follows from one of those two. When a decision is unclear,
ask which property it protects.

## 2 · Where the build actually stands

Be accurate about this — it is easy to assume more exists than does.

| | State |
|---|---|
| **orca-core** — sites, lanes, devices, users, permissions, teams, settings | Built. Process and screen *design* is not built |
| **orca-runtime** — the engine, visits, work items, connectors | Built. Two of its five modules (`notify`, `readmodel`) are still empty |
| **orca-edge** — camera protocol, device commands, the durable buffer | Built and proven against stubs. Never yet run against real equipment |
| **orca-portal**, **orca-sync**, **orca-fleet** | Skeletons — an application class and a health endpoint. Cloud work has not started |
| **orca-media** | Inherited, not rebuilt. No code here |
| **The workflow compiler** | **Does not exist.** The one process the platform runs is a hand-written file |

The gate path works end to end: plate read → exactly one visit → a call to the
customer's system → a confirmed barrier → the visit's outbound fact. You can drive
it yourself in about a minute; `docs/phase-1-demo.md`.

## 3 · The rules that fail the build

Ten checks run on every build and **stop it** when broken. They are not advisory,
and they exist because each one is a defect this codebase already shipped once.

| Rule | What it stops |
|---|---|
| **Platform purity** | Nothing in `platform/` may name a visit, lane, ticket, driver or truck. The shared layer stays free of domain |
| **Module walls** | No module of `orca-runtime` may reach into another's internals, and no service may import another service's packages |
| **Scope seam** | No service class may touch `JdbcTemplate`, `EntityManager`, a `DataSource` or raw JDBC. **Every read goes through one seam** |
| **Error envelope** | Every controller method returns the shared response shape |
| **Retention class** | Every table that grows with traffic must declare how long its data is kept |
| **System context** | Every scheduled job must run under an explicit identity. Nothing runs anonymously |
| **Engine confinement** | Only the `execution` module may touch the workflow engine's API |
| **Scope-leading index** | Every table carrying the site column needs an index leading with it. *Reads the migrations, not the code* |
| **Contract interface** | Every controller must implement an interface generated from the OpenAPI document |
| **Internal surface** | Anything tagged internal lives under `/internal/**`, and vice versa |

**If a check fails, it is right and you are wrong.** They have a history of being
correct. Do not weaken one to get past it — if you genuinely believe a rule should
not apply, that is a conversation, not an edit.

### The three that surprise people most

**The scope seam.** You cannot write a query by hand. Reads go through
`platform/scope`, which applies the site condition *before* your filter and returns
**zero rows** when no scope is set — never all rows. The old system has roughly 816
hand-written scope conditions, and forgetting one leaks another site's data with no
error and no log line.

**Contract-first, never controller-first.** Edit the service's OpenAPI document,
regenerate, then implement the generated interface. Change the contract and the
build breaks until the controller matches. That is the point.

**A migration that has shipped is never edited.** Not for a bug, not for a comment.
Fix it with a *new* migration. Editing one changes its checksum and every database
that already ran it refuses to start.

## 4 · How work is verified here

**By executing it, never by asserting it.** A written test is not evidence it
passes. A check nobody has watched fail may not be wired in.

```bash
./gradlew build                    # compile, unit tests, the ten checks
./gradlew check integrationTest    # FULL verification — 222 tests, ~7 minutes
```

⚠️ **`./gradlew test` runs almost nothing that matters.** The property suites are a
separate source set so the build works without Docker.

⚠️ **A green build does not mean a service starts.** Every suite constructs its
beans directly, so a broken bean definition passes all 222 tests. This repository
has shipped a service that passed everything and could not boot. **Start the
services and drive a truck before calling anything done.**

### Tests here prove properties, not paths

The canonical statement of the difference:

> *"The outbox writes a row" is not a test — "killing the process between the two
> writes leaves neither" is.*

Look at `AdmissionPropertiesIT` (one truck, one visit — a thousand times, two
simultaneous events each) or `WorkItemLifecycleIT` (a poisoned table rolls back
both halves together). That is the bar. **In this codebase the tests are the
specification.**

## 5 · The system being replaced, and how to use it

Cloned as a sibling at `../Lynxis-Gate` — 25 Go microservices, in production today.

**Read it to understand.** When you reimplement a feature, that code is the record
of what it really does: the actual wire formats, the real columns, the edge cases.
Reading it makes a port far more accurate than guessing.

**But it is evidence, not a specification.** Four rules:

1. **`docs/*-from-1x.md` are the authority where one exists.** Each is an extraction
   with file-and-line evidence, and each carries both what the old system does *and*
   the defects deliberately **not** carried forward. Where a sheet and the old code
   disagree about what to build, the sheet wins.
2. **If no sheet covers your work, ask for one** rather than porting from source.
3. **Never assume a pattern is intentional because it ships.** Real examples: a
   "unique" index that silently degrades to non-unique; statuses nothing ever writes,
   so the cleanup job never runs; a deduplication that can start two workflows from
   one event.
4. **Never modify anything there.** Read-only, always.

⚠️ **Read `../Lynxis-Gate/CLAUDE.md` before searching that repository.** It
documents the conventions that make its code readable — soft-delete flags on nearly
every table, singular table names, dual integer/UUID keys. Search without it and
you will reach wrong conclusions confidently.

## 6 · What you must not decide alone

Some things are not an implementer's call. Surface them; do not settle them.

- **Anything security-shaped** — authentication, credentials, key handling, what is
  encrypted at rest.
- **Anything commercial or scope-changing.**
- **Anything the open-questions register lists as undecided.** Check
  `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` before concluding something was forgotten
  — *unspecified because undecided* and *unspecified because forgotten* look
  identical and are completely different.

> **Never invent a resolution to an open question.** The most repeated failure on
> this programme has been filling a gap with something plausible and writing it up
> as settled. Every one read well; every one was wrong. **A gap reported is worth
> more than a gap filled.**

## 7 · What a good change looks like

1. **The contract first** if you are adding an endpoint. Then the generated
   interface, then the controller.
2. **The migration, the code and the tests land together** — not in separate passes.
3. **A property test for every guarantee you touch**, written so it would fail if
   the guarantee broke.
4. **Comments that explain themselves.** This repository has a written standard: a
   comment must be understandable by someone who has never read our documents. No
   architecture section numbers, no decision-record codes, no phase or work-package
   numbers. Say what the thing is, why it exists, and what will surprise the reader.
   `services/orca-core/src/main/resources/db/migration/V102__topology_views.sql`
   is the worked example.
5. **The full verification, executed** — `check integrationTest`, the services
   started, a truck driven.
6. **A short written report** for anything substantial: what you built, what you
   could not, every decision the plan did not dictate, and anything that looked
   wrong — **reported, not silently corrected.**

## 8 · Working alongside three other streams

Four developers work in parallel on separate streams.

- **Stay inside your stream's scope.** It is named in your stream plan, and the
  module walls are enforced by a build check.
- **Use your assigned migration number range.** Two people both writing `V118` is a
  conflict that only appears when someone's database refuses to start.
- **Branch per feature**, merged to `develop`, then to `main`.
- **If you need something from another stream's area, ask** rather than reaching in.

## 9 · Where everything is

| To learn | Read |
|---|---|
| Set up and run locally | `docs/LOCAL_DEVELOPMENT.md` |
| The rules, in short, enforced | `AGENTS.md` — and the nested ones in `platform/`, `services/orca-runtime/`, `build-checks/` |
| The target design and its guarantees | `docs/ORCA_ARCHITECTURE.md` |
| What is deliberately undecided | `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` |
| Where anything lives | `docs/REPOSITORY_GUIDE.md` |
| What each shared primitive prevents | `docs/PLATFORM_PRIMITIVES.md` |
| What the old system really does | `docs/*-from-1x.md` |
| One truck, end to end | `docs/phase-1-demo.md` |
| What was built in each phase, and what was not | `docs/phase-*-report.md` |

Each service also has its own `README.md` — what it owns, what it deliberately does
not, what is actually built, and where to look first.

---

*If something here is wrong or unclear, that is a defect in this document. Say so.*
