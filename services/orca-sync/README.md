# orca-sync — replication between a site and a hosted tier

**Carries facts between a facility and a cloud tier when the two are paired: a
completed visit travelling up, a new ticket travelling down.**

> ⚠️ **This service is a skeleton. It boots, answers a health check, and does
> nothing else.** No replication is built. See *What is actually here today*.

| | |
|---|---|
| **HTTP port** | `8085` |
| **Database schema** | `sync` |
| **Database login** | `orca_sync` — this service can reach no other schema |
| **Status** | **Skeleton.** One application class, one health endpoint, three empty packages, and a migration that creates no tables |
| **Why it is not built** | It only exists where two tiers are paired, and no hosted tier has been built yet. The programme is completing the on-site system first |

## What it will be responsible for

- **Reading each service's outbox** — the record every service writes, in the same
  transaction as the fact it describes, of something that happened.
- **Delivering those facts to the paired tier**, in order per key, at least once.
- **Recording what was acknowledged**, so nothing is lost and nothing is applied
  twice.
- **Tracking how far behind a site is**, and raising an alarm when it falls too
  far.

## The two rules it will be built to

Both are already decided, and both are the reason this service can be simple.

**It carries facts, not commands.** Sync knows which kinds of fact cross a tier
boundary and in which direction. A service cannot ask it to carry something
arbitrary — it is not a general-purpose message bus.

**It never writes another service's tables.** A fact is delivered to the endpoint of
whichever service *owns* that kind of data, and that owner applies it in its own
transaction. Combined with the rule that only one tier may author each kind of
record, this means no conflict can arise that anyone has to adjudicate.

## What is actually here today

```
src/main/java/com/lynxis/orca/sync/
  SyncApplication.java          the application entry point
  api/HealthController.java     GET /api/v1/health — the only route
  api/package-info.java         empty package, with a note on what belongs here
  domain/package-info.java      empty
  persistence/package-info.java empty
src/main/resources/
  application.yaml              port, database login, migration settings
  openapi/orca-sync.yaml        one path: the health check
  db/migration/V100__baseline.sql   creates NO tables — it only stamps the schema
```

There are no tests, because there is no behaviour to test.

**The skeleton still earns its place.** The schema, the login and its confinement,
the contract-first wiring and the response envelope are all in place and proven, so
none of that has to be invented later under time pressure.

## What already exists elsewhere, and matters here

The hard half of replication is **already built and tested**, in the shared outbox
library every service uses: a fact and its outbox row commit together or not at
all, delivery is claimed so that two instances never contend for the same row,
facts are ordered per key, and a fact nobody has acknowledged cannot be deleted by
a retention job.

So this service is not starting from nothing. What it adds is the transport between
two tiers and the bookkeeping of how far each one has got.

## Running it

```bash
./gradlew bootRun -p services/orca-sync --args='--spring.profiles.active=local'
```

It starts on its own — it reads no other service's data yet.

## When it fails

Once built: facts accumulate in each service's outbox and the paired tier goes
stale. **Nothing is lost**, and nothing at the gate stops. The floor on how long
data must be kept is set by how far behind replication is allowed to fall, which is
what stops an outage silently destroying data that was never replicated.
