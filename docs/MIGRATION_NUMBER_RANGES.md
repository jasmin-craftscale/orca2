# Migration number ranges — who may write which numbers

**Read this before you write your first migration. It is the one piece of
coordination four parallel streams cannot do implicitly.**

Assigned 10 August 2026, before any stream started, because a number collision is
a conflict that surfaces only when somebody's database refuses to start.

---

## 1 · How numbering works here, which is not obvious

Each service runs **one Flyway instance over four locations**, merged into a single
version namespace per schema:

```
classpath:db/migration              this service's own migrations      V100+
classpath:db/platform/outbox        the primitives' tables, applied    V001
classpath:db/platform/lease         INTO this service's schema by      V002
classpath:db/platform/idempotency   THIS service's Flyway              V003
```

So `V118` means "the 118th version of *this schema*", and a platform migration and
a service migration **share one number line**. The split that keeps them apart today
is the convention that platform takes `V001–V099` and services take `V100+`.

**Where each schema stands right now** (read out of the migration files and confirmed
against the running database, 10 Aug 2026):

| Schema | Migrations | Highest | Notes |
|---|---|---|---|
| `runtime` | V100–V102, V110–V114, V115–V117 | **V117** | **V110–V114 are Flowable's 45 extracted tables.** V103–V109 are unused and stay that way — see §4 |
| `core` | V100–V110 | **V110** | |
| `edge` | V100–V103 | **V103** | |
| `portal` · `sync` · `fleet` | V100 | **V100** | Skeletons |
| every schema | V001–V003 | — | The three primitives, applied into all six |

## 2 · The ranges

**Runtime — streams 1 and 2 both write here. This is the collision the assignment exists to prevent.**

| Range | Owner |
|---|---|
| **V118–V127** | **Stream 1 Track A** — partner event API and dispatch queue |
| **V128–V137** | **Stream 1 Track B** — connector breadth, including the shared credentials-at-rest first consumer |
| **V138–V157** | **Stream 2** — read models and notifications |
| **V158–V167** | **Stream 4** — retention and purge, runtime's share |
| **V168–V170** | **OCS-4 runtime migration** — the step trace (`node_execution`), the visit dataset, and the compiled-definition columns on `execution`. Taken from the reserved band, 10 Aug 2026, because the migration is not one of the four streams |
| V171–V199 | Reserved: Flowable version upgrades, and anything urgent that cannot wait for a band |

**Core**

| Range | Owner |
|---|---|
| **V111–V140** | **Stream 3** — custom entities, the DDL executor, licence verification |
| **V141–V150** | **Stream 4** — retention and purge, core's share |
| V151–V199 | Reserved |

**Edge**

| Range | Owner |
|---|---|
| **V104–V113** | **Stream 4** — retention and purge, edge's share |
| V114–V199 | Reserved — no stream owns edge today |

**Portal · sync · fleet** — skeletons; nothing is assigned. If stream 4 finds a
traffic-growing table in one, it takes **V101–V110** there and says so in its report.

**Platform primitives — a new one takes `V900`, not `V004`.** See §3; this is the
non-obvious half.

Stream 1 is split because its two developers work in the same schema at the same
time. Giving Track A and Track B separate sub-bands prevents the credentials
foundation from taking Track A's first number while that developer is writing it.
The wider stream bands remain deliberately generous: unused numbers cost nothing;
a collision costs a merge and usually a local schema rebuild.

## 3 · ⚠️ Two traps that ranges alone do not fix

### 3.1 · Parallel streams produce out-of-order arrivals, and the default refuses them

Bands stop two people writing `V118`. They **guarantee** something else: migrations
arriving in an order different from their numbers.

Developer B (stream 2) applies `V138` to their local database on Tuesday. On Thursday
they pull `develop`, which now carries stream 1's `V118`. Their database holds a
higher version than a migration that has never run.

Every service is configured `validate-on-migrate: true`, and `out-of-order` is unset —
**Flyway's default is `false`**. Reproduced against this stack on 10 Aug 2026, with
these exact settings:

```
FAILED: FlywayValidateException
   Validate failed: Migrations have failed validation
   Detected resolved migration not applied to database: 118.
   To ignore this migration, set -ignoreMigrationPatterns='*:ignored'.
   To allow executing this migration, set -outOfOrder=true.
```

The service does not start. This is exactly the failure the ranges were meant to
prevent, arriving through the other door.

**What to do, and what not to.**

- **On a developer machine:** re-run with out-of-order allowed, or drop the schema and
  migrate from scratch. Dropping is the honest option while schemas are cheap, and it
  is what the local stack is for: `docker compose down -v`, then `docker compose up -d`
  and `docker compose run --rm bootstrap`.
- **Never on a customer site.** There, migrations only ever arrive in release order, and
  strict ordering is a property worth keeping. `out-of-order` stays `false` in the
  committed configuration.
- ⚠️ **Whether to relax it for the `local` profile is a change to committed configuration,
  and it is the product owner's call, not a stream's.** It is recorded here as a
  proposal, not applied. The argument for: four developers will hit this repeatedly, and
  the alternative is re-migrating a database several times a week. The argument against:
  a setting that makes local behave unlike production is how a migration that only works
  out of order reaches a release.

### 3.2 · A new platform primitive cannot take `V004`

The primitives sit at `V001–V003` — *below* every service migration. A sixth primitive
numbered `V004` would arrive at a `runtime` schema already at `V117`, and would be
refused by the mechanism in §3.1, permanently and in every schema at once.

**A new primitive migration therefore takes `V900`, then `V901`, and so on** — above
every service band, so it is always the highest number in every schema it reaches and
can never be out of order.

The existing `V001–V003` are not renumbered. They have been applied everywhere, and a
migration that has shipped is never edited — which is the neighbouring rule:

> ⚠️ **Editing a primitive's migration invalidates its checksum in all six schemas at
> once.** A primitive is applied into every service's schema, so the blast radius of an
> edit there is six databases refusing to start, not one.

## 4 · Why `runtime` V103–V109 stay empty

They sit *below* Flowable's extracted block at V110–V114. Filling them now would be an
out-of-order arrival for every existing database by construction — §3.1 again. They are
not spare capacity; treat them as consumed.

---

*If a stream needs more numbers than its band, ask rather than borrow — the next band
belongs to somebody who is using it.*
