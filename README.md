# ORCA 2.0

**Gate automation for logistics facilities** — container terminals, distribution
centres, and industrial sites where trucks arrive, are identified, are processed
against the operator's systems, and are released. A camera reads a plate; the
process the site's own administrators designed runs the gate — asking the Terminal
Operating System whether the truck is expected, reading a seal or a document,
printing a ticket, raising the barrier — and routes to a human whatever automation
cannot finish. Carriers pre-announce visits through a driver portal.

This repository is the **ground-up rewrite** of a system in production today
(25 Go microservices). It ships to new clients only; there is no migration from the
old system. Java 25 · Spring Boot 4 · SQL Server · embedded Flowable.

Two properties shape every decision here:

> **The gate must keep working when other things do not.** A truck at a barrier is a
> physical queue — if the platform stops, it grows into the public road. The design
> assumes the wide-area link drops, a server fails, an integration times out, and
> states what happens in each case.
>
> **The site's processes belong to the site.** Terminals do not run the same process.
> Administrators design processes and screens visually, without code, and the
> platform stays true to that as they grow more demanding.

---

## How it works

**One truck through a lane, end to end:** a plate read arrives at `orca-edge` over
the camera's own wire protocol and is buffered durably, per lane, in order. It is
delivered to `orca-runtime`, where **admission** starts exactly one visit — even
when two events for the same truck arrive in the same instant. The process runs on
Flowable: it calls the customer's system behind a circuit breaker, routes the answer
to a branch, and commands the barrier through `orca-edge` — a command that is
idempotent, carries an expiry, and is treated as done only when the device host
*confirms* it acted. The visit completes, writing its outcome and an outbound "fact"
in a single transaction. Work that automation cannot finish becomes a clerk work
item, created in the same transaction as the process step that raised it, tracked
against a time target, and escalated when the target passes.

Nothing in flight is held in a running process — coordination lives in the database,
so a restart resumes a visit from the step it had reached, and more than one server
can run at a site. The architecture states the guarantees it makes and how each is
verified; see **§B10 of `docs/ORCA_ARCHITECTURE.md`**, which is also the acceptance
criteria the build is held to.

## The seven services

| Service | Owns | Runs |
|---|---|---|
| **orca-core** | The world as configured — sites, lanes, users, devices, workflow and screen design, licence verification | Site · cloud |
| **orca-runtime** | The world as it happens — the process engine, visits, work items, connectors, the partner API | Site · cloud |
| **orca-edge** | Every hardware contract, the durable capture buffer, the command log | Site · thin edge |
| **orca-portal** | Carriers, drivers, tickets — the only internet-facing service | Cloud (authoritative) |
| **orca-sync** | Replication between a site and a hosted tier | Where a tier is paired |
| **orca-fleet** | Licence issuance and signing, the fleet registry — cloud only | Lynxis cloud |
| **orca-media** | Video and intercom — inherited, not rebuilt | Site |

Six are built here on the JVM; `orca-media` is delivered in its existing technology.
The current programme is **on-site first** — `orca-portal`, `orca-sync` and
`orca-fleet` exist as foundations and are built out when cloud scope opens.

## The load-bearing choices

- **Embedded Flowable executing BPMN 2.0.** Administrators keep ORCA's own visual
  builder; each published process is compiled to BPMN at publish.
- **No message broker inside a site.** Facts travel by a transactional outbox — the
  fact and its outbox row commit together — with at-least-once, per-key-ordered
  delivery. A database table does what a broker would, with nothing extra to operate.
- **Multi-instance by design.** Coordination is database-held with leases and fence
  tokens; device ingestion elects one owner per lane, because cameras address a
  single endpoint.
- **One database, seven schemas, enforced by credentials.** Each service's login can
  reach only its own schema; cross-schema reads go through published views.
- **Keycloak authenticates people; services authenticate each other** with a
  per-installation shared credential verified locally — no identity provider on the
  gate path.
- **Contract-first.** Controllers implement OpenAPI-generated interfaces, so a
  contract change breaks the build. Five shared primitives — outbox, lease, scope,
  idempotency, web envelope — were built once, before any service.

## Technology

Java 25 · Spring Boot 4 · Gradle (Kotlin DSL, one version catalog) · SQL Server 2022
· Flowable 8 · Keycloak · Flyway (per-service migrations) · Testcontainers ·
ArchUnit (the build checks). The Java toolchain auto-provisions; you don't install it.

## Repository layout

```
platform/         the five primitives — no domain types live here
  outbox/ lease/ scope/ idempotency/ web/
services/         six bootable applications, each owning its schema, migrations,
  orca-core/        DB login and OpenAPI contract
  orca-runtime/     (the only service decomposed into modules)
  orca-edge/ orca-portal/ orca-sync/ orca-fleet/
  orca-media/       a README — inherited, not a module
build-checks/     ArchUnit rules that FAIL THE BUILD — the architecture, enforced
deploy/           the local stack (SQL Server, Keycloak, stubs), bootstrap, demo
docs/             architecture, the open-questions register, phase plans and reports
```

`docs/REPOSITORY_GUIDE.md` walks it in full.

## Getting started

**→ `docs/LOCAL_DEVELOPMENT.md`** takes you from nothing to a truck through the gate,
and onboards your AI assistant at the end. It is the only setup document you need.

The short version: Docker and a JDK, clone this repository **and the old system
side by side**, bring up the stack, run three services, send a plate.

```bash
./gradlew build                    # compile, unit tests, the ten build checks
./gradlew check integrationTest    # FULL verification — plain `test` skips the
                                   # property suites that need a real database
```

## The system this replaces

ORCA 2.0 is a ground-up rewrite of a platform running in production today — 25 Go
microservices. **You need that repository cloned alongside this one**, as
`../Lynxis-Gate`: when you reimplement a feature, its code is the record of what the
feature really does.

It is **evidence, not a specification, and it is read-only.** Where an extraction
sheet exists in `docs/*-from-1x.md`, that sheet is the authority — each one carries
both what the old system does *and* the defects deliberately not carried forward.
`docs/LOCAL_DEVELOPMENT.md` explains the setup and the discipline; **read
`../Lynxis-Gate/CLAUDE.md` before searching it**, or you will misread what you find.

## The rules that keep it honest

The architecture is enforced, not just documented. **Ten build checks fail the build**
when a rule is broken — platform purity, module walls, the scope seam, the response
envelope, retention classes, system context, engine confinement, the scope-leading
index, contract interfaces, and the internal surface. **`AGENTS.md`** is the short,
authoritative statement of the rules and how to work here — read it once; it binds
people as much as tools.

The definition of done for any substantive change: the build is green **including**
`check` and `integrationTest`, every guarantee you touched has a property test that
would fail if the guarantee broke, and you leave a short written report — what you
built, what you couldn't, every decision the spec didn't dictate, anything that
looked wrong (reported, not silently corrected). And the habit that matters most:
**if something is unspecified, check the register before concluding it was
forgotten — never invent a resolution.**

## Contributing

Branch `feature/<TICKET>` off the trunk; PR back into it; keep each PR to one concern.
CI runs the build, the checks, and the integration suite; all must pass. Label
AI-assisted PRs accordingly.

## Documentation map

| For | Read |
|---|---|
| **Set it up and run it — start here** | **`docs/LOCAL_DEVELOPMENT.md`** |
| **Joining the project, as a developer or an AI** | **`docs/DEVELOPER_ONBOARDING.md`** |
| The rules that fail the build | `AGENTS.md` |
| The target design and its guarantees | `docs/ORCA_ARCHITECTURE.md` |
| What's deliberately undecided | `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` |
| The repository layout | `docs/REPOSITORY_GUIDE.md` |
| What each primitive prevents | `docs/PLATFORM_PRIMITIVES.md` |
| What the old system really does | `docs/*-from-1x.md` |
| What has been built in each phase, and what was not | `docs/phase-*-report.md` |
| One truck through the gate, in detail | `docs/phase-1-demo.md` |
| Production deployment — the target, and the gaps | `docs/deployment.md` |

Each service also has its own `README.md`.

## Production status

⚠️ **This repository is not yet installable at a customer site, and `deploy/` is a
development stack, not a production artifact.** The gate path is built and verified
end to end; what stands between it and a customer machine — release images,
licensing, secrets provisioning, a reverse proxy, the operator console, and two
vendor answers — is known, named, and tracked in the register and `docs/deployment.md`
(Part 2). The deployment phase is the next major implementation plan. Do not ship the
development stack to anyone.
