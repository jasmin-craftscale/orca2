# orca-portal — carriers, drivers and tickets

**The service haulage companies and drivers use to pre-announce a visit before the
truck arrives. It is the only part of the platform reachable from the public
internet.**

> ⚠️ **This service is a skeleton. It boots, answers a health check, and does
> nothing else.** No carrier, driver or ticket has been built yet. See *What is
> actually here today* before you go looking for code that is not written.

| | |
|---|---|
| **HTTP port** | `8084` |
| **Database schema** | `portal` |
| **Database login** | `orca_portal` — this service can reach no other schema |
| **Status** | **Skeleton.** One application class, one health endpoint, three empty packages, and a migration that creates no tables |
| **Why it is not built** | The programme is completing the on-site system first. This service is authoritative in a hosted tier, and work on that tier has not started |

## What it will be responsible for

Recorded so the boundary is clear, not because any of it exists yet:

- **Carriers and their staff** — the haulage companies that deliver to a site, and
  the people who book on their behalf.
- **Drivers** — including the licence and plate details a gate needs to recognise
  a truck.
- **Tickets** — the driver-facing artifact of a booked visit, and the QR code the
  gate scans on arrival.
- **Appointments** — when a truck is expected.

## Why it is a service of its own

Two reasons, both about isolation rather than size, and both worth understanding
before anyone proposes folding it into another service.

**It is the only service reachable from the public internet.** Drivers reach it
from consumer networks on consumer phones. Keeping that surface in its own
deployable, with its own database login and its own schema, means a compromise
there does not begin inside the gate.

**Its idea of "who owns this data" is genuinely different from the rest of the
platform.** Everywhere else, a row belongs to a site. Here it does not: a haulage
company delivers to terminals owned by *different customers*, and a driver should
not need one account per terminal they visit. So carrier and driver records
deliberately have no site or customer column at all, and access is decided by
**who the requester is** rather than which site they belong to.

⚠️ **That access rule has not been designed yet, and it blocks real work here.**
It is an open question, not an oversight: nobody should invent an answer to it in
passing while building something else.

## What is actually here today

```
src/main/java/com/lynxis/orca/portal/
  PortalApplication.java        the application entry point
  api/HealthController.java     GET /api/v1/health — the only route
  api/package-info.java         empty package, with a note on what belongs here
  domain/package-info.java      empty
  persistence/package-info.java empty
src/main/resources/
  application.yaml              port, database login, migration settings
  openapi/orca-portal.yaml      one path: the health check
  db/migration/V100__baseline.sql   creates NO tables — it only stamps the schema
```

There are no tests, because there is no behaviour to test.

**The skeleton is not pointless.** It means the schema, the database login and its
confinement, the contract-first wiring and the response envelope are all in place
and proven; when the service is built, none of that has to be invented under time
pressure. Running `deploy/bootstrap` already creates its schema and login, and the
isolation check already proves this login cannot read another service's data.

## Running it

```bash
./gradlew bootRun -p services/orca-portal --args='--spring.profiles.active=local'
```

It starts on its own — it reads no other service's data yet.

## When it fails

Once built: drivers cannot pre-announce a visit or retrieve a ticket, **and the
gate keeps running**. A truck arriving without a pre-announcement is handled as an
unannounced visit, which is an ordinary case rather than an error.

## Before you build on this

Two things are settled and one is not:

- **Settled** — this service is authoritative in the hosted tier; a site holds only
  enough of a replica to check a ticket when the link to that tier is down.
- **Settled** — a site never invents ticket data; the tier owns it.
- **Open** — how access is decided for a principal who legitimately spans several
  customers. Nothing here should be built until that has an answer.
