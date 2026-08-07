# Adopting a database that let the Flowable engine migrate itself

**The migration path off `flowable.database-schema-update: true`, written and
tested while the population it applies to is still zero.**

`phase-1-report.md` §7.4 recorded this as a gap and said why it mattered:

> *There is no migration path off `database-schema-update: true`, and the demo
> proved it by tripping over it. … Nothing is deployed, so today this is developer
> machines only — but it is a real path that does not exist.*

It exists now. This document is the procedure; `deploy/adopt-flowable/` is the
script; `FlowableAdoptionIT` is the proof, and it runs the committed script rather
than a copy of it.

---

## 1 · The situation, and how to recognise it

Phase 0 shipped `flowable.database-schema-update: true`, so on such a database the
**engine** created its own 45 `ACT_*`/`FLW_*` tables and Flyway knows nothing about
them. WP3 put those tables under Flyway as `V110`–`V114`. The next start therefore
runs `V110` against a schema that already has its tables and fails:

```
Migration V110__flowable_common.sql failed
SQL State  : S0001
Error Code : 2714
Message    : There is already an object named 'ACT_GE_PROPERTY' in the database.
```

The service does not start. Nothing is damaged — Flyway rolls the migration back
and records the failure — but the gate is down until somebody acts.

**Who this applies to.** Today: developer machines, and any installation ever
started on a Phase 0 build. **Nothing is deployed anywhere**, which is exactly why
the path is being built now: it is the only moment at which it can be got wrong for
free.

## 2 · The procedure

Stop `orca-runtime`. Then, once:

```bash
cd deploy && ./adopt-flowable/run.sh
```

Start the service. Flyway builds `V110`–`V114` and the engine comes up against a
schema it did not create.

**It runs as `orca_runtime`, not as `sa`,** and works on that login's default
schema. That is not a formality: the script drops tables, and the database itself
is what stops it reaching a schema that is not runtime's (ADR-004). `bootstrap/`
runs as `sa` because it creates logins; this must not.

**Running it when you are not sure is safe.** On a database that needs no adoption
it says so and changes nothing — that no-op is one of the five tested properties.

## 3 · What it does, and what it refuses

It adopts **by rebuild**: verify, drop, let Flyway build. No schema-history
surgery, no hand-written checksums, nothing that depends on a Flyway internal that
could change under a version upgrade.

| Step | |
|---|---|
| 1 | Nothing to do if there are no engine-created tables, or if `V110` is already in `flyway_schema_history`. |
| 2 | **Refuse** unless `ACT_GE_PROPERTY.common.schema.version` is `8.0.0.0` — the version `V110`–`V114` were extracted from. |
| 3 | **Refuse** if `ACT_RU_EXECUTION` or `ACT_HI_PROCINST` holds any row. |
| 4 | Drop the `ACT_*`/`FLW_*` objects, foreign keys first, generated from `sys.tables` rather than from a list. |

A refusal is a `RAISERROR` at severity 16 and `sqlcmd -b`, so the operator sees
which precondition failed rather than a half-done schema.

**Why step 3 is the point of the whole script.** Adopting by rebuild destroys
running visits and the audit trail behind completed ones. A gate with a truck
mid-visit is exactly the installation somebody runs this on in a hurry, and the
refusal is what stands between them and it. `FlowableAdoptionIT` asserts both
halves: that it refuses, and that the schema is **unchanged** afterwards — a
partial drop would be worse than either outcome.

**Why step 2 exists.** If the tables were built by a different Flowable version
they are not what `V110`–`V114` build. Rebuilding would then silently change the
schema under an engine that has been running on the other one, which is a migration
and not an adoption.

## 4 · ⚠️ What is NOT built: adoption with process data present

**If step 3 refuses, this document has no answer for you, and that is deliberate.**

A data-preserving adoption would have to:

1. Prove the engine-built schema is **equivalent** to what `V110`–`V114` build —
   tables, columns, indexes and foreign keys. The comparison exists
   (`FlowableSchemaUnderFlywayIT` does exactly it, both ways) but it compares two
   schemas in one database, not one schema against a set of files.
2. Record `V110`–`V114` in `flyway_schema_history` as applied **without executing
   them**, carrying the checksums Flyway itself computes — because
   `validate-on-migrate: true` (orca-runtime's `application.yaml`) will reject a history row
   whose checksum does not match the file, and that setting is not one to weaken.
3. Be **exercised against a real installation in that state**.

Point 3 is why it is not built. **No installation can be in that state**: nothing
is deployed, so a data-preserving procedure written today could be tested only
against a situation constructed by its own test. That is the shape of procedure
that is discovered to be wrong at the moment it is first needed, on a gate, with a
queue outside.

**It becomes necessary the day the first installation runs a Phase 0 build with
real traffic on it, and not before.** Whoever builds it should start from
`FlowableAdoptionIT`, which already constructs the state, and from
`Flyway.info()`, which is where the correct checksums come from.

## 5 · Upgrading Flowable is a different thing, and is already written down

Nothing here applies to a Flowable version upgrade. `V110`'s own header states that
rule and it has not changed:

> *Do NOT re-extract this file. It has shipped, and an expand-only discipline (§B7)
> means a migration is never edited after it does. Extract that version's UPGRADE
> STEP scripts — `flowable.mssql.upgradestep.*.sql`, in the same jars — as NEW
> migrations, in the order Flowable applies them.*

The extraction itself is a Gradle task, never a download:

```bash
./gradlew :services:orca-runtime:extractFlowableSchema
```

---

*Companion: `docs/phase-1-report.md` §7.4 (the gap), `docs/phase-1-hardening-report.md` §H4 (what was built), `deploy/adopt-flowable/` (the script), `FlowableAdoptionIT` (the proof).*
