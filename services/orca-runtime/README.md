# orca-runtime — the gate brain

**Runs a truck's visit from the moment it is identified to the moment the barrier
lifts, and routes to a person whatever automation cannot finish.**

This is the service that decides things. A plate read arrives from `orca-edge`,
this service starts exactly one visit for that truck, runs the process the site's
administrators designed for that lane, calls the customer's own system, commands
the barrier, and records what happened. When a step cannot be completed
automatically, it creates a work item, tracks it against a time target, and
advances the process when a clerk resolves it.

| | |
|---|---|
| **HTTP port** | `8082` |
| **Database schema** | `runtime` |
| **Database login** | `orca_runtime` — this service can reach no other schema |
| **Depends on** | `orca-core` for configuration, read through the published views `core.topology_lane` and `core.topology_device`. It **refuses to start** if those views are missing or not readable, naming them |
| **Talks to** | `orca-edge` (device commands, over HTTP), the customer's own system (through configured connectors) |
| **Status** | Built and working end to end. Two of its five internal modules are not written yet — see *What is built today* |

## What it is responsible for

- **Admission** — turning device events into visits. Two events for the same truck
  can arrive in the same instant, from two different servers, and exactly one visit
  must start. This is the property the whole design turns on.
- **Running the process** — an embedded workflow engine executes the BPMN process
  for the lane: calling the customer's system, branching on the answer, commanding
  the barrier, and ending the visit.
- **Connectors** — the outbound calls to a customer's Terminal Operating System or
  warehouse system, each with a deadline, a circuit breaker and a bulkhead so that
  one slow customer system cannot stall the gate.
- **Work items** — the queue of human work. Creating an item, routing it to the
  eligible teams, letting an operator claim it, and completing it in a way that
  advances the process atomically.
- **Operator presence** — who is idle, working, on a break or offline, which is
  what lets work be pushed to someone who can actually take it.

## What it deliberately does not do

- **It does not talk to hardware.** Every camera, barrier, printer and IO port is
  `orca-edge`'s. This service issues a command and waits for a confirmed outcome.
- **It does not own configuration.** Sites, lanes, devices, users, teams and the
  process designs all belong to `orca-core`. This service reads what it needs
  through core's published views, in its own transaction, and cannot write them.
- **It does not authenticate people.** An identity provider issues tokens; this
  service validates them locally by signature.

## What is built today

Five internal modules are planned. Three are real; two are empty packages with a
comment explaining what will live there. That emptiness is deliberate and is
asserted by a build check, so it stays visible rather than being forgotten.

| Module | State | Holds |
|---|---|---|
| `execution` | **Built** | Admission, the workflow engine gateway, the delegates a process calls, visit completion, lane reset |
| `workitem` | **Built** | The work-item lifecycle, routing, eligibility, operator presence |
| `integration` | **Built** | Connector configuration and the outbound REST connector |
| `notify` | **Empty** | Will hold operator notifications and the live-update hub |
| `readmodel` | **Empty** | Will hold pre-built projections for the operator grids |

**The modules are walls, not folders.** A build check fails the build if one module
reaches into another's internals. Where two modules genuinely must cooperate — a
process step raising a work item, a completed work item advancing that process —
they do it through a narrow interface each module publishes, and nothing else.

### The workflow engine

The process engine (Flowable) runs **inside this service**, not as a separate
server: there is nothing extra to install at a site. Its tables live in this
service's own schema and are created by this service's migrations, not by the
engine itself — engine self-migration is switched off deliberately, so that the
schema is versioned like everything else.

⚠️ **The engine's background worker must be running.** If it is disabled, this
service will accept visits and never advance any of them, which looks like a
hang rather than an error.

Only the `execution` module may touch the engine's API — a build check enforces
that — so that the platform is not written against one engine's API throughout.

## The data it owns

Created by the migrations in `src/main/resources/db/migration`:

| Migration | Adds |
|---|---|
| `V100` | The empty baseline for this schema |
| `V101` | `lane_session`, `execution`, `execution_event` — a visit and the events attached to it |
| `V102` | `connector_config`, `connector_route` — where a customer system lives and which of its answers takes which branch |
| `V110`–`V114` | The workflow engine's own tables. **Extracted verbatim from the engine's jars — never hand-edit these** |
| `V115` | `work_item`, `work_item_audit` |
| `V116` | `user_activity` — operator presence, one row per change of state |
| `V117` | A constraint fix, shipped as a new migration rather than an edit to `V115`, because a migration that has already run must never change |

Plus tables the shared platform libraries create in this schema: the outbox pair
that records facts leaving this service, the coordination lease, and the
idempotency record that makes a replayed command safe.

## Its HTTP interface

| Group | Paths |
|---|---|
| Work items | `/api/v1/work-items`, and per item `/take`, `/takeover`, `/park`, `/assign`, `/complete`, `/audit` |
| Operators | `/api/v1/me/presence`, `/api/v1/operators/idle`, `/api/v1/operators/activity` |
| Lanes | `/api/v1/lanes/{laneExternalId}/reset` |
| Internal | `/internal/events/v1` — device events from `orca-edge`, deduplicated |
| Health | `/api/v1/health`, `/actuator/health` |

Endpoints under `/internal/` are for other ORCA services, never for a browser, and
carry a shared credential rather than a user's token. Every route here is generated
from `src/main/resources/openapi/orca-runtime.yaml`: change the contract and the
build breaks until the controller matches.

## Running it

```bash
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local'
```

Start `orca-core` first — this service waits for core's published views. The local
stack (database, identity server, and stubs standing in for a customer system and a
device host) is `deploy/`; see `deploy/README.md`.

## How it is tested

Twelve suites under `src/integrationTest`, run with `./gradlew integrationTest`
against a real SQL Server and a real engine — not mocks. They are written as
**properties rather than paths**: the interesting ones assert what must be true
under failure.

Worth reading first, in this order:

| Suite | Proves |
|---|---|
| `AdmissionPropertiesIT` | One truck, one visit — a thousand times, with two simultaneous events each |
| `WorkItemLifecycleIT` | Creating an item and parking the process happen together or not at all; completing it advances the process in one transaction |
| `WorkItemSlaIT` | The time-target timer really fires, survives a restart, and fires exactly once |
| `RuntimeRestartIT` | A visit that one instance started is finished by a different instance |
| `GateVisitProcessIT` | The process itself — including that no outbound call happens while a lane is locked |

## When it fails

| Failure | What happens |
|---|---|
| This service is down | Visits stop advancing. `orca-edge` keeps accepting and buffering plate reads, so nothing is lost — but the queue at the gate grows |
| A customer's system is slow or down | Its circuit breaker opens and the process takes its failure branch immediately. Other lanes and other connectors are unaffected |
| An instance dies mid-visit | Another instance picks the work up from the database and the visit continues from the step it had reached |

## Where to look first

1. `execution/domain/AdmissionService.java` — the one-truck-one-visit guarantee,
   and the clearest example of how this codebase reasons about concurrency.
2. `src/main/resources/processes/gate-visit.bpmn20.xml` — the process the whole
   platform exists to run, written as a compiler would emit it.
3. `workitem/domain/WorkItemService.java` — the clerk lifecycle, and where
   completing a work item advances the process.
