# `deploy/` — the local development stack

**Everything ORCA needs *around* it that the platform deliberately does not build,
plus the one-time privileged database setup and the demo tooling.**

> ⚠️ **This is a development environment. It is not a production artifact, and no
> part of it is used at a customer site.**
>
> Every credential here is a committed fixture, the stubs stand in for components
> ORCA does not own, and there are no service images — during local development the
> six services run on your host from Gradle. The production install sequence, with
> an honest per-step status for each of its gaps, is **`docs/deployment.md` Part 2**;
> that is the deployment phase's backlog, not this directory.

---

## Quick start

```bash
cd deploy
cp .env.example .env                 # ONCE — never overwrite an existing .env
docker compose up -d                 # SQL Server, Keycloak, and the two stubs
docker compose run --rm bootstrap    # ONCE against a fresh database: schemas, logins, grants
```

Then start services from the repository root, **each with the `local` profile**.
Core first — it publishes the views runtime and edge refuse to start without:

```bash
./gradlew bootRun -p services/orca-core    --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-edge    --args='--spring.profiles.active=local'
```

One truck through the gate:

```bash
docker compose run --rm demo-seed    # after core and runtime have migrated
./gradlew sendPlate -Pplate=T-DEMO-01
```

The full walkthrough with inspection queries and the four deliberate
demonstrations — dedup, the admission race, an unrouted branch, an expired
command — is **`docs/phase-1-demo.md`**.

## What runs

`docker compose up -d` starts four containers. The other three are one-shot tools
behind the `tools` profile and never start with the stack.

| Service | Image | Host port | Why it is here |
|---|---|---|---|
| `sqlserver` | `mcr.microsoft.com/mssql/server:2022-latest` | `1433` | The only database (ADR-003). One database, seven schemas |
| `keycloak` | `quay.io/keycloak/keycloak:26.7.1` | `8080`, `9000` (management) | Identity. Realm `orca`, imported at startup from `keycloak/` |
| `tos-stub` | `wiremock/wiremock:3.13.1` | `9200` | Stands in for the customer's Terminal Operating System |
| `device-host-stub` | `wiremock/wiremock:3.13.1` | `9300` | Stands in for the per-lane .NET device host that drives the barrier |
| `bootstrap` | *(reuses the SQL Server image)* | — | **Profile `tools`.** The privileged database setup, run once |
| `verify-isolation` | *(reuses the SQL Server image)* | — | **Profile `tools`.** Proves each login reaches only its own schema |
| `demo-seed` | *(reuses the SQL Server image)* | — | **Profile `tools`.** One site, lane, camera, barrier, connector and clerk world |

Services run on the host, not in compose: `orca-core` 8081, `orca-runtime` 8082,
`orca-edge` 8083, `orca-portal` 8084, `orca-sync` 8085, `orca-fleet` 8086, and
edge's camera listener on 9100.

The database volume is named `orca-sqlserver-data`. `docker compose down` leaves it;
`docker compose down -v` drops it, which is how you get a genuinely empty database.

## What is in this directory

| Path | What it is |
|---|---|
| `docker-compose.yml` | The seven services above |
| `.env.example` | Committed fixtures with working local values. `.env` is gitignored |
| `bootstrap/` | `V001`–`V004` plus `run.sh` and `verify-isolation.sh` — the privileged half of the database setup |
| `keycloak/realm-export.json` | Realm `orca`: one client per service, imported on container startup |
| `stubs/tos/`, `stubs/device-host/` | WireMock mappings. The device-host routes are **DERIVED-FROM-1X** — see `stubs/README.md` |
| `demo/seed.sh`, `demo-site.sql`, `demo-connector.sql` | The demo world (below) |
| `demo/send-plate.py` | The camera, as a standalone Python script. `./gradlew sendPlate` is the cross-platform equivalent and the documented path |
| `adopt-flowable/` | Recovery for a database whose Flowable tables the engine created itself (below) |
| `tools/gen-*.py` | Generate the catalog seed migrations from the DERIVED-FROM-1X reference sheets (below) |

## The bootstrap, and why it is separate

Each service authenticates to SQL Server **as itself** and can reach only its own
schema. That wall is ADR-004, and it is enforced by database credentials rather
than by convention or a build check: `orca_core` owns the `core` schema and has no
permission of any kind on `runtime`, and neither a code review nor ArchUnit is what
stops it — the database does.

Creating that wall needs administrative rights that **no service is ever allowed to
hold**, so it is one privileged step that lives here and runs once:

| File | Does |
|---|---|
| `V001__database.sql` | Creates the database |
| `V002__schemas_and_logins.sql` | Seven schemas, seven logins, one owner each |
| `V003__grants.sql` | Grants each login its own schema and nothing more |
| `V004__verify.sql` | Asserts the shape the first three claim, and fails loudly rather than printing a summary |

Re-running is a no-op. The confinement can be re-proven at any time:

```bash
docker compose run --rm verify-isolation      # 36 checks
```

Every login writes and reads its own schema; every cross-schema read is refused.
The seventh login is not an ORCA service — it is how the telephony engine reaches
its own configuration tables (§B5, §C7), which ORCA never reads or writes.

`bootstrap/run.sh` and `verify-isolation.sh` are the same scripts, runnable
directly from a Unix host. Both paths execute identical SQL; the container form
exists so a Windows machine needs nothing but Docker and a JDK.

## The demo world

`demo-seed` writes under **two different logins** — the topology is `orca_core`'s
and the connector configuration is `orca_runtime`'s, and neither can write the
other's schema. That is ADR-004 working rather than an inconvenience.

It creates one site, area and lane; a camera and a barrier; the `tos` connector
pointing at the stub with the routing row that says `200` means `APPROVED`; and the
clerk world Phase 3 needs — a role, a user, a team with its membership, a screen
identity and the routing rule that reaches it.

**It is a deliberate act, never a profile or a migration.** Nothing runs it but a
person, so no profile, environment variable or ordering accident can put demo rows
on a customer site.

`./gradlew sendPlate` then speaks the real camera wire format (STX/ETX ZapPacket,
`docs/lpr-wire-format-from-1x.md`) at edge's listener. It is a Gradle task rather
than a container because it must reach a host process, and rather than a Python
script because Gradle is already on every developer's machine:

```bash
./gradlew sendPlate                                          # T-DEMO-01 on LANE-DEMO-01
./gradlew sendPlate -Pplate=T-RACE                           # any plate
./gradlew sendPlate -Pplate=T-D -PeventGuid=evt-fixed -Prepeat=2   # the dedup key
```

Also accepts `-Phost=`, `-Pport=`, `-Plane=`, `-Pcamera=`, `-Pconfidence=`.

## `adopt-flowable/` — for a database the engine migrated itself

Phase 0 shipped `flowable.database-schema-update: true`, so a database first
started on that build has the engine's 45 `ACT_*`/`FLW_*` tables and no Flyway
history for them. `V110` then fails with *"There is already an object named
'ACT_GE_PROPERTY'"* and `orca-runtime` does not start.

Stop runtime, then once:

```bash
cd deploy && ./adopt-flowable/run.sh
```

⚠️ **Unlike the three tools above, this one is not a compose service** — it is a
bash script only, so it needs a Unix host or WSL2. Every other setup path in this
directory works on Windows with nothing but Docker; this one does not, and that is
a gap rather than a decision.

It adopts **by rebuild**: verify, drop, let Flyway build. It **refuses** — without
changing anything — if those tables hold process data, or if they were built by a
Flowable version other than the one the migrations were extracted from. On a
database that needs no adoption it says so and does nothing, so running it when you
are unsure is safe. The procedure and what it deliberately does *not* do is
`docs/flowable-adoption.md`.

## `tools/` — the seed generators

The entitlement and device catalogs are **generated, never transcribed**. Each
script parses its DERIVED-FROM-1X reference sheet in `docs/`, asserts the sheet's
own counts before emitting a row, and mints external ids as deterministic UUIDv5 —
so regenerating produces a byte-identical migration. They are run when a catalog
migration is written, not as part of running the stack.

## What is deliberately not here

Each of these is a decision with a stated cost, not an omission.

| Absent | Why |
|---|---|
| **A message broker** | ADR-007. Work is handed between services through the database, with a transactional outbox. If you are about to add one, read §B4 first |
| **A reverse proxy** | A site runs one and it is what terminates TLS on the site network. Nothing locally needs TLS terminated |
| **PostgreSQL** | ADR-003 — SQL Server is the only engine built and tested. Advice elsewhere that assumes PostgreSQL, particularly about row-level security, does not apply |
| **Service images / Dockerfiles** | There are none in the repository. Release images, a registry and a release pipeline are the largest single gap between this and a customer machine — `docs/deployment.md` Part 2, step 3 |
| **Real secrets** | Everything in `.env.example` is a fixture: committed, identical on every machine, and worthless off a laptop |

## Configuration

`.env.example` is committed and documents every variable inline. The groups:

- **SQL Server** — `MSSQL_SA_PASSWORD`, `MSSQL_PORT`
- **Keycloak** — `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_PORT`, `KEYCLOAK_MANAGEMENT_PORT`
- **Seven per-service database logins** (ADR-004) — the services default to these
  same values in their own `application.yaml`, so a developer who has exported
  nothing still gets seven distinct logins rather than seven copies of `sa`
- **`ORCA_INTERNAL_CREDENTIAL`** (ADR-011) — the per-installation shared credential
  services present to each other on `/internal/**`. **This committed value is
  recognised by name**, and a service refuses to start with it unless the `local`
  profile is active, so the public fixture cannot reach a customer site by inertia
- **Six Keycloak client secrets** — local development convenience only since the
  7 Aug 2026 ADR-011 ruling; services no longer authenticate to each other with
  tokens. They remain because they are the easiest way to mint a token and exercise
  a resource server
- **Stub ports** — `ORCA_TOS_STUB_PORT`, `ORCA_DEVICE_HOST_STUB_PORT`

## Things that will bite you

**The SA password needs 8+ characters with upper, lower, digit and symbol.** SQL
Server exits during startup otherwise, and the message it prints does not say that.
This is the single most common cause of "Docker will not start SQL Server".

**The SQL Server image is `linux/amd64` only.** On Apple silicon it runs under
emulation. It works, and it is slow — a first start takes tens of seconds. Do not
swap in `azure-sql-edge` to get a native image: it is a different engine, and
testing against it is not testing against what ships.

**`--spring.profiles.active=local` is not optional on a laptop.** The committed
inter-service credential is refused outside `local` by name. Set the profile; do
not weaken the check.

**Do not blind-copy `.env.example` over an existing `.env`.** It is gitignored
precisely because it holds machine-local values — ports in particular.

**Start order matters, and the platform enforces it.** Runtime and edge refuse to
start before core has migrated, naming the views they are waiting for rather than
failing later on a lane lookup.

**If another stack already holds these ports**, set `MSSQL_PORT` / `KEYCLOAK_PORT`
in `.env`, pass `--server.port=` to each service, and export `ORCA_DB_URL` and
`ORCA_OIDC_ISSUER_URI` to match. `docs/phase-1-demo.md` §3 shows the exact
incantation, verified on a machine where ORCA 1.x owns the defaults.

**Hand-editing a table with a filtered unique index needs
`SET QUOTED_IDENTIFIER ON`** in the same `sqlcmd` batch — `core.user_account` is
one, so the demo's step that links a Keycloak subject to the demo clerk fails
without it, with an error that blames SET options rather than the index.

**Full verification is `./gradlew check integrationTest`**, from the repository
root. Plain `test` skips every property suite that needs a real database.

---

*Companions: `docs/deployment.md` (local dev in full, and the road to a customer
site) · `docs/phase-1-demo.md` (one truck, end to end) · `stubs/README.md` (what
the two stubs stand in for) · `docs/flowable-adoption.md` (the adoption procedure).*
