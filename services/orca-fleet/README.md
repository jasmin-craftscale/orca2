# orca-fleet — licences and releases

**Issues the signed licence an installation checks at startup, keeps the register of
every licence ever issued, and distributes release artifacts. It runs in the
vendor's own cloud and never at a customer site.**

> ⚠️ **This service is a skeleton. It boots, answers a health check, and does
> nothing else.** No licence issuance is built. See *What is actually here today*.

| | |
|---|---|
| **HTTP port** | `8086` |
| **Database schema** | `fleet` |
| **Database login** | `orca_fleet` — this service can reach no other schema |
| **Runs** | In the vendor's cloud only. **Never at a customer site** |
| **Status** | **Skeleton.** One application class, one health endpoint, three empty packages, and a migration that creates no tables |
| **Why it is not built** | It belongs to the hosted tier, and the programme is completing the on-site system first |

## Why it is a separate service

**Because the signing keys must never ship on a customer's machine.** That is the
whole reason this is its own deployable rather than a module of another service.
It holds the private half of the key that signs a licence; only the public half —
enough to *verify* a licence, never to forge one — ever reaches an installation.
The boundary is structural, not a matter of configuration or discipline.

## What it will be responsible for

- **Issuing and signing licences**, and keeping the ledger of every one ever
  issued: to which site, when, expiring when, signed with which key, revoked when.
- **The register of installations** — what version each is running, what version it
  should be running, and when it last reported in.
- **Distributing signed release artifacts.**

## Two rules it will be built to

**Sites pull; the cloud never pushes.** An installation reports in and fetches what
it is entitled to. Nothing in the cloud opens a connection into a customer's
network.

**A licence is verified locally, always.** An installation checks the signature on
a file it already holds. **No path at runtime ever calls this service to ask
whether it may keep working** — a gate that phones home to stay open is a gate that
stops when the internet does.

What a licence limits is how many instances may be *active at once*, and that is
settled at the site by the instances arbitrating through their shared database. A
cold standby on other hardware is a supported arrangement, not a violation. The
reporting machine's identity is telemetry only: one licence reporting from two
different machines shows up here as something to have a commercial conversation
about, and never as a check that silently stops a gate.

## What is actually here today

```
src/main/java/com/lynxis/orca/fleet/
  FleetApplication.java         the application entry point
  api/HealthController.java     GET /api/v1/health — the only route
  api/package-info.java         empty package, with a note on what belongs here
  domain/package-info.java      empty
  persistence/package-info.java empty
src/main/resources/
  application.yaml              port, database login, migration settings
  openapi/orca-fleet.yaml       one path: the health check
  db/migration/V100__baseline.sql   creates NO tables — it only stamps the schema
```

There are no tests, because there is no behaviour to test.

## Running it

```bash
./gradlew bootRun -p services/orca-fleet --args='--spring.profiles.active=local'
```

It starts on its own — it reads no other service's data.

## When it fails

**Nothing at any customer site stops.** An installation holds its licence locally
and verifies it against the local file. What stops is issuing new licences,
registering new installations and distributing releases.

## Before you build on this

One thing is settled and one is not.

- **Settled** — the licence is a *signed* artifact, and the signature is verified.
  The system being replaced signs its licences correctly and then never checks the
  signature, which makes the signing pointless. That must not be repeated here, and
  verification belongs in the format from the first line of code.
- **Open** — what should happen when a licence expires. The engineering
  recommendation on the table is that a running gate never hard-stops: expiry
  blocks new instances, updates and administrative changes, with loud warnings and
  a grace period, while trucks keep moving. That is a commercial decision and has
  not been made.
