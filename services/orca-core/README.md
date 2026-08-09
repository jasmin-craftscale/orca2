# orca-core — the world as configured

**Everything an installation is set up to be: its sites and lanes, its devices, its
people and what they are allowed to do, and the processes and screens its
administrators design.**

Nothing here executes. This service describes the world; `orca-runtime` runs in it.
That separation is the point: configuration changes rarely and is read constantly,
and a fault in the administration tooling must never be able to stop a gate.

| | |
|---|---|
| **HTTP port** | `8081` |
| **Database schema** | `core` |
| **Database login** | `orca_core` — this service can reach no other schema |
| **Depends on** | Nothing. It is the source the others read from, so it starts first |
| **Read by** | `orca-runtime` and `orca-edge`, through published read-only views — never by reaching into these tables |
| **Status** | Built. The configuration world is real; process and screen *design* is not built here yet |

## What it is responsible for

- **The world model** — the customer, its sites, the areas within a site, the lanes
  within an area, and the devices attached to each lane. Exactly one site is the
  installation's own primary site.
- **People and permissions** — user accounts, the roles they hold, and the
  catalogue of things a role can be granted. It maps an identity provider's claims
  onto platform permissions; it does not issue or check credentials.
- **The device registry** — what each device is, where it lives, how it is
  addressed, its IO port layout, and the parameters a device host needs to load the
  right driver for it.
- **Clerk teams** — who is on which team, which work each team is eligible for, and
  the shift and break templates behind that.
- **Settings and branding** — validated platform settings with a history of who
  changed what, per-site colours and language.

## What it deliberately does not do

- **It does not execute anything.** No visit, no process, no device command.
- **It does not issue tokens.** An identity provider authenticates people; this
  service only maps the resulting claims onto permissions.
- **It does not store device credentials.** Those columns deliberately do not exist
  yet, because how they should be stored is a security decision with a named owner
  and no answer.
- **It does not let another service write its tables.** Consumers get views.

## How other services read it

Core publishes **read-only views** — `topology_lane`, `topology_device`,
`topology_screen`, `topology_team_routing`, `topology_team_member`,
`topology_operator`, `topology_setting` — and grants SELECT on them to the services
that need them. A consumer reads a view inside its own transaction: no HTTP call to
core, no delay on the path a truck is waiting on, and no ability to write.

**A view is a contract.** Core can restructure its own tables freely as long as the
views keep answering the same columns, and a consumer can never reach anything core
has not deliberately published. `orca-runtime` and `orca-edge` both **refuse to
start** until the views they need exist and are readable.

## What is built today

The configuration world — 34 tables across eleven migrations, with the seeded
catalogues that go with them.

| Migration | Adds |
|---|---|
| `V100` | The empty baseline for this schema |
| `V101` | `site`, `area`, `lane`, `device` — the world model |
| `V102` | The first two published views, and the grants that let runtime and edge read them |
| `V103` | Roles, user accounts, and the four-level catalogue of grantable permissions |
| `V104` | That catalogue's contents — 174 leaf permissions, generated rather than typed |
| `V105` | Teams, team members, shift and break templates |
| `V106` | The device registry: device details, IO port assignments, camera presets, scoped variables |
| `V107` | Settings with their change history, saved user filters, grid preferences, the audit trail |
| `V108` | Completes the device catalogues — device types, IO port names, device kinds |
| `V109` | Screen identities and team routing rules, plus the views runtime reads them through |
| `V110` | A view exposing settings to runtime |

**The seeded catalogues are generated, never transcribed.** The scripts in
`deploy/tools/` read a reference sheet, assert its row counts before emitting
anything, and mint identifiers deterministically — so regenerating produces a
byte-identical migration and a test can prove the seed is stable across two clean
databases.

### Not built here yet

**Workflow and screen design.** The service will own the visual builder's saved
designs and the publish pipeline that validates a design, compiles it to an
executable process and freezes an immutable version. None of that is written: today
the one process the platform runs is a file on the runtime's classpath.

## Its HTTP interface

Thirty paths, all under `/api/v1/`, all consumed by an administration console that
does not exist yet: users, roles, permissions, teams, shift and break templates,
lanes and their devices, device catalogues, IO assignments, camera presets, scoped
resource configuration, screens, team routing rules, settings, site branding,
saved filters, grid preferences and the audit trail.

Every route is generated from `src/main/resources/openapi/orca-core.yaml`. Change
the contract and the build breaks until the controller matches — which is the
point.

## Running it

```bash
./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local'
```

**Start this service before runtime and edge.** They wait for its views. The local
stack is `deploy/`; see `deploy/README.md`.

To create a demo site, lane, camera and barrier to work against:
`cd deploy && docker compose run --rm demo-seed`.

## How it is tested

Eight suites under `src/integrationTest`, against a real SQL Server. They assert
properties rather than exercise paths — most usefully, that the database refuses
things it must refuse.

| Suite | Proves |
|---|---|
| `IdentityPropertiesIT` | Every uniqueness rule on users, roles and grants is enforced by the database, not by a hopeful check in Java |
| `CatalogSeedPropertiesIT` | Two clean databases produce byte-identical seed rows, identifiers included |
| `TopologyViewsIT` | The published views serve what consumers expect, and retired rows never appear in them |
| `TransactionalityPropertiesIT` | A failure part-way through a multi-statement change leaves nothing behind |
| `SettingsWorkspaceAuditPropertiesIT` | A secret-shaped setting key is refused before it can ever reach the table |

## When it fails

**The gate keeps running.** Runtime and edge read configuration through views in
their own transactions, so core being down does not stop a visit in progress or a
plate read arriving. What stops is *changing* things: no new user, no new device,
no settings change.

## Where to look first

1. `V101__world_model.sql` and `V102__topology_views.sql` — the world model, and
   how it is published to everyone else.
2. `DeviceAdminService` — the largest piece of domain logic here, and a good
   example of how a set-valued admin surface is replaced rather than patched.
3. `SettingsService` — validated writes, appended history, and the rule that a
   secret never enters the settings table.
