# ORCA — Repository Guide

**For review before Phase 0 starts · August 2026**

What each folder is, why it exists, and how the whole thing builds and runs. One decision at the end needs settling before the build agent runs.

---

## 1 · The structure

```
orca/                                    git root = Gradle root
├── build.gradle.kts                     AGGREGATOR — no src/, no application
├── settings.gradle.kts                  12 modules registered
├── gradle/libs.versions.toml            one version catalog for every module
│
├── platform/                            THE PRIMITIVES — no domain types
│   ├── outbox/                          transactional outbox + relay
│   ├── lease/                           lease with fence token
│   ├── scope/                           the query seam
│   ├── idempotency/                     recorded outcomes
│   └── web/                             envelope · codes · system identity
│       └── src/main/resources/openapi/_shared.yaml
│
├── services/                            SIX BOOTABLE APPLICATIONS
│   ├── orca-core/                       CoreApplication
│   │   └── src/main/
│   │       ├── java/com/lynxis/orca/core/   api/ · domain/ · persistence/
│   │       └── resources/
│   │           ├── application.yaml          own port, own DB login
│   │           ├── openapi/orca-core.yaml    its own contract
│   │           └── db/migration/             its own migrations
│   ├── orca-runtime/                    + execution · workitem · integration
│   │                                      · notify · readmodel
│   ├── orca-edge/  orca-portal/  orca-sync/  orca-fleet/
│   └── orca-media/                      README only — NOT a module
│
├── build-checks/                        TESTS ONLY — output is build failures
│       platform purity · module walls · scope seam
│       error envelope · retention class
│
├── deploy/
│   ├── bootstrap/                       schemas, logins, grants — once, first
│   ├── docker-compose.yml               SQL Server 2022 + Keycloak 26
│   ├── .env.example                     committed — working local values
│   └── keycloak/realm-export.json
│
├── .github/workflows/ci.yml
└── docs/
```

**12 Gradle modules. Six bootable applications. Zero business logic.**

**Each service owns its schema, its database login, its migrations and its OpenAPI contract.** The shared response envelope lives with `platform/web`, which implements it. Only `orca-runtime` is decomposed into modules — those five are named by the architecture and the module wall depends on them; every other service is flat.

---

## 2 · The five top-level folders

### `platform/` — the shared primitives

**The problem these five solve is not "shared code". It is that each of them is a place where correctness is hard, the obvious implementation works perfectly in testing, and the failure is silent in production.**

That combination is why they are built once, first, by everyone — rather than five times, later, by whoever needed one that week.

Here is what each one actually prevents.

#### outbox — *the fact and the notification cannot disagree*

**The obvious implementation:** save the visit, then call the other service (or publish a message).

**How it fails:** the save commits and the call fails — the visit happened and nobody was told. Or the call succeeds and the transaction rolls back — everyone was told about a visit that does not exist. Both leave two systems permanently disagreeing, with nothing to detect it. Under a network blip this happens perhaps once in ten thousand times, which means it is never seen in testing and is seen constantly in production.

**What the primitive does:** the fact and its outbox row are written in **one transaction** — neither can exist without the other. A relay delivers afterwards, retrying until each consumer acknowledges. Delivery may be late; it cannot be lost, and it cannot describe something that did not happen.

#### lease — *a dead instance cannot corrupt what it was doing*

**The obvious implementation:** an `is_owner` flag, or a "last heartbeat" column, or simply letting whichever instance polls first take the work.

**How it fails:** instance A owns a lane. Its network stalls for twenty seconds. The system declares it dead and gives the lane to instance B. Instance A wakes up — it never knew it had died — and finishes the write it started. Two instances have now written as owner, and the corruption is invisible because both writes looked legitimate.

**What the primitive does:** the lease carries a **fence token that increases every time the lease changes hands**, and every write protected by the lease presents its token. Instance A's write arrives with an old token and is **rejected**. The zombie cannot do damage, which is a stronger property than trying to guarantee it is dead.

#### scope — *forgetting once is not possible*

**The obvious implementation:** `WHERE site_id = ?` in each query. Everyone knows to do it.

**How it fails:** it is written correctly hundreds of times and omitted once, in a query added under time pressure a year later. The result is a customer seeing another customer's data. There is no error, no exception, no log line — just wrong rows. **This is the current system's actual position: roughly 816 hand-written conditions and no single place to fix them.**

**What the primitive does:** one seam applies the scope, and **a build check fails on any query constructed outside it**. Correctness stops depending on everyone remembering, which is the only form of correctness that survives a team and a decade.

#### idempotency — *a retry gets the answer, not an error*

**The obvious implementation:** check whether it already happened, then do it.

**How it fails in two ways.** Two concurrent requests both check, both find nothing, both proceed — the barrier rises twice. And the subtler one: a caller times out, retries, and receives *"duplicate"*. But the caller retried **because it never saw the first answer** — an error is exactly what it cannot use. It retries again, or reports a failure that succeeded.

**What the primitive does:** records the key **and the outcome**. A replay returns the original result. An operation still running reports *in progress*, which is not a terminal answer, so the caller keeps waiting rather than concluding.

#### web — *one shape, and no anonymous execution*

**The obvious implementation:** each service returns whatever suits it; errors carry the exception message.

**How it fails:** callers parse strings to tell one failure from another, so any wording change breaks an integration. Internal detail — stack traces, schema names — leaks to the caller. And scheduled work runs with no identity at all, so nothing can be authorised or attributed.

**What the primitive does:** one envelope everywhere with **machine-readable codes a caller can branch on without reading the message**, no internal detail on any path, and an **explicit system identity** for every entry point that no user invoked — the relay, the reconciler, the scheduled job.

#### Why first, and not when needed

Every naive implementation above **works in development**. The failures need concurrency, a partition, a restart or a retry — conditions a developer building a feature does not produce.

So they are not discovered during the work. They are discovered in production, individually, by six teams, at which point six services each have their own subtly different version and unifying them means touching every query and every write in the product.

**Building them once, first, costs two to three weeks. Retrofitting them costs the product.**

⚠️ **The rule that keeps this module honest:** if a class in `platform/` names a **visit**, a **lane**, a **ticket**, a **driver** or a **truck**, it is in the wrong place. These know about transactions, leases, keys, scopes and HTTP. They do not know what business this is — and the moment one does, every service depends on it and it can no longer be changed.

### `services/` — six service modules plus one placeholder, six deployable applications

Each service module is **its own Spring Boot application**: its own `main`, its own configuration, its own image, deployed independently. They share a repository and a version catalog, nothing else.

| Module | What it owns |
|---|---|
| `orca-core` | The world as configured: sites, lanes, users, devices, workflow and screen design |
| `orca-runtime` | The world as it happens: the process engine, visits, work items, connectors |
| `orca-edge` | Every hardware contract, the capture buffer, the command log |
| `orca-portal` | Carriers, drivers, tickets — the only internet-facing service |
| `orca-sync` | Replication between a site and a hosted tier |
| `orca-fleet` | Licence issuance and signing — cloud only, never at a customer site |
| `orca-media` | Video and intercom — **inherited, not built here.** A README placeholder |

**Only `orca-runtime` is decomposed into modules** — `execution`, `workitem`, `integration`, `notify`, `readmodel`. Those five come from the architecture and the module wall depends on them. The other services stay flat until their modules are defined; inventing a decomposition now would be a guess written as structure.

**Inside every module, the same three packages:** `api` (controllers and DTOs) · `domain` (entities and domain services) · `persistence` (repositories). This is not a style preference — it is what makes the module wall expressible as one rule: *no module may reference another module's `persistence` package.*

### Contracts — the API source of truth, living with their owners

Seven authored OpenAPI documents: one per service at `services/<name>/src/main/resources/openapi/orca-<name>.yaml`, plus `platform/web`'s `_shared.yaml` holding the response envelope, the error object, the error codes and pagination. There is no top-level `contracts/` folder — a contract lives with the service that owns it, and the service contracts `$ref` the shared definitions across the module boundary. (`orca-media` is inherited and has no authored contract here.)

**The API layer is generated from these, not the other way round.** The generator emits an **interface** and its DTOs; the controller is hand-written and implements that interface. Change the contract and the build breaks until the controller matches. A second generator pass bundles a self-contained copy of each contract into its service's jar, and every service serves it at `/openapi/orca-<name>.yaml`.

That is what makes contract-first real rather than aspirational — the API surface stops being documentation and becomes a compile-time constraint.

`_shared.yaml` matters more than it looks: without one shared definition, six services each invent their own error schema and the single-envelope guarantee decays into a convention within a month. `schemaMappings` binds its schemas to `platform/web`'s hand-written Java types, so the generated DTOs and the platform envelope are the same classes rather than two shapes that drift.

### Migrations — each service migrates its own schema

One database, seven schemas, each written by exactly one service — enforced by database credentials, not by convention. There is no shared `migrations/` module: each service carries its own Flyway migrations at `services/<name>/src/main/resources/db/migration`, scoped to its own schema and run under its own login. The platform primitives ship their table DDL on their own Flyway locations, applied into each schema by the owning service's Flyway — one definition, applied per schema.

**Ordering is a deployment property, not a build one.** `core` publishes the read-only views the other services consume, so `core` deploys first; a dependent service started before those views exist **fails startup through the `RequiredViewsGate`, naming every missing view** rather than limping into runtime errors. `deploy/bootstrap/` creates the database, the seven schemas, the seven logins and the grants — and `V004__verify.sql` asserts the isolation properties rather than assuming them.

### `build-checks/` — the enforcement

A module containing **only tests, no production code.** Its entire output is build failures.

| Check | Fails when |
|---|---|
| Platform purity | A class in `platform/` references a domain type |
| Module walls | A module reads another module's `persistence` package |
| Scope seam | A query is constructed outside the seam |
| Error envelope | A controller returns a shape other than the envelope |
| Retention class | A traffic-growing table has no declared retention class |
| System context | A `@Scheduled` entry point runs without entering `SystemContext` |

The sixth rule was added beyond the brief's five — §B10's "assert every scheduled job, relay and reconciler enters it" can only be a rule over every class, not a unit test. Note it currently governs an empty set: Phase 0 has no `@Scheduled` methods, so it has never fired outside a deliberate violation.

**This folder is why the monorepo is the right choice.** These checks have to see every service at once. In seven separate repositories they degrade into a code-review convention — and a convention is what the current system enforced tenancy with, across 816 hand-written conditions.

---

## 3 · The supporting folders

**`deploy/`** — `docker-compose.yml` (SQL Server and Keycloak), a committed `.env.example` with working local values, and the Keycloak realm export: one realm, one client per service, service accounts for service-to-service calls. A developer clones, copies one file, and runs.

**`.github/workflows/`** — build → unit tests → integration tests on Testcontainers against real SQL Server → all build checks → coverage. A pull request cannot merge with any of them failing.

**`docs/`** — the architecture document (the specification), the open-questions register (what is deliberately unsettled), the Phase 0 brief (what the agent builds), and this guide.

---

## 4 · How it builds

One Gradle multi-project. **The root is an aggregator and holds no code** — no `src/`, no application. It sets the Java 25 toolchain and owns `gradle/libs.versions.toml`, the single version catalog every module draws from, so no two modules can disagree about a dependency version.

The Spring Boot plugin is declared at the root with `apply false` and applied in each service module. That is what makes six bootable applications instead of one.

```
./gradlew build              everything
./gradlew test               unit tests
./gradlew integrationTest    Testcontainers, real SQL Server
./gradlew check              the seven build checks
./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local'
```

⚠️ **The `local` profile is required to run a service on a laptop.** Since
ADR-011, services authenticate to each other with a per-installation credential,
and the committed local value is recognised **by name**: a service refuses to
start with it unless the `local` profile says this genuinely is a development
machine. That refusal is what stops the public fixture credential reaching a
customer site by inertia — do not work around it by changing the credential
check; set the profile.

---

## 5 · How it runs

**Locally:** `docker compose up` gives SQL Server and Keycloak; each service runs from the IDE or `bootRun`, **with the `local` profile active** (see above). Every service validates tokens **by signature, locally** — no call-out to Keycloak per request, which is also what lets a site keep working when the wide-area link drops.

**At a customer site:** the images for that deployment profile run on one server, or on more than one. A profile says *which* services run; it does not say how many instances of each. The platform is designed for more than one instance in every profile.

**There is no message broker.** Services hand work to each other through the database — the outbox in `platform/`. That is one fewer server to provision, patch and monitor at every site, and it is why `platform/outbox` is the most load-bearing module in the repository.

---

## 6 · What Phase 0 delivers, and what it does not

**Delivers:** a repository that builds, a local stack that runs, seven schemas that migrate, five primitives with tests that prove their properties, six build checks that fail the build when violated, CI, and six services that boot and report health.

**Does not deliver:** any product behaviour. No visit can start, no gate can open, no screen exists. Nothing is demonstrable to a customer.

⚠️ **Worth saying out loud before it starts.** Two to three weeks with four developers and nothing to show is entirely reasonable if it was announced and uncomfortable if it was not. The first demonstrable thing — a plate read producing a visit and a confirmed barrier — comes at the end of Phase 1.

**What you are buying** is that the guarantees move from a document into the build. After Phase 0 a developer *cannot* write a query that escapes its scope or publish a fact without recording it, because the build stops them. Before Phase 0, all of it depends on everyone remembering.

---

## 7 · Questions worth raising in review

1. **Does the frontend live in this repository?** It decides §1, and it is cheaper to decide now than after seven services exist.
2. **Is `platform/` the right set of five?** They are the primitives the architecture's guarantees rest on. If your lead sees a sixth, better to know before they are built.
3. **Is one database with seven schemas acceptable to whoever will operate it?** It is a deliberate choice — it is what allows in-transaction reads across services and removes the broker. A database per service would forbid both.
4. **Who owns `platform/` after Phase 0?** It is shared code with no natural owner, and shared code with no owner is how it drifts.
