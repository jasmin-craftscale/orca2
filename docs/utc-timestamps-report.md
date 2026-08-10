# UTC timestamp correction report

## Outcome

`orca-runtime` now converts SQL Server `DATETIME2` values to and from `Instant`
as UTC wall-clock readings on both sides of every affected Java-owned column.
Database-default timestamps, Java-written timestamps, mixed-origin durations and
visit search windows now agree on a non-UTC JVM. No migration or backfill was
added.

The work is on `feature/utc-timestamps` in four repository-sized commits:

- admission timestamp writes and the runtime-local `Utc` utility
- work-item timestamp reads/writes and properties 1–3
- presence timestamp reads/writes
- visit timestamp reads/search binding, property 4 and this report

## Changes by file and column

| File | Columns and behavior |
|---|---|
| `runtime/persistence/Utc.java` | New service-local `DATETIME2` conversion: `instantAt` interprets stored `LocalDateTime` as UTC; `timestampOf` writes an `Instant` as a UTC wall clock; `now` applies the same write conversion. It mirrors orca-edge without creating a forbidden service dependency. |
| `execution/persistence/AdmissionRepository.java` | `execution_event.received_at` and `execution.completed_at` now use `Utc`. Removing the old local-zone `now()` helper also exposed two unlisted consumers, `lane_session.updated_at` and `lane_session.bound_at`; these now use `Utc.now()` as well. |
| `workitem/persistence/WorkItemRepository.java` | Writes for `work_item.started_at`, `completed_at` and `sla_breached_at` use UTC. Reads for those columns plus database-written `queued_at` use `Utc.instantAt`. Database-written `work_item_audit.occurred_at` also uses `Utc.instantAt`. The old `instant(Timestamp)` path was deleted. |
| `workitem/persistence/PresenceRepository.java` | Writes and reads for `user_activity.started_at` and `ended_at` use the same UTC conversion. |
| `execution/persistence/VisitReadRepository.java` | The `since` parameter is bound with `Utc.timestampOf`; `started_at` and `completed_at` use `Utc.instantAt`, replacing the repository's separate UTC-calendar helper. |
| `UtcTimestampPropertiesIT.java` | Four properties force `Australia/Sydney`: database-default reads, Java write/read round-trip, mixed DB/Java duration, and honest visit search windows. |

## Verification

The clean baseline on `0581be8` was:

```text
./gradlew check integrationTest --rerun-tasks
BUILD SUCCESSFUL in 6m 22s
integrationTest: suites=31 tests=236 failures=0
```

Before each suite run the services and Gradle daemons were stopped. The worker
check printed `0` (also confirmed by `jps`, so the shell running the check was not
mistaken for a worker).

| §7 | Real result |
|---|---|
| 1. `./gradlew build` | `BUILD SUCCESSFUL in 6s`; `68 actionable tasks: 4 executed, 64 up-to-date`. |
| 2. Full rerun | `./gradlew check integrationTest --rerun-tasks` → `BUILD SUCCESSFUL in 6m 45s`; XML count `suites=32 tests=240 failures=0 errors=0`. The count rose by exactly the four new tests. |
| 3. Red evidence | Against unfixed production code, property 1 read `2026-08-10T07:18:06.249Z` for a database value written around `17:18:06Z`; property 3 read `36000051L` milliseconds and failed the expected range `[0L, 10000L]`. A later combined red run reproduced property 3 as `36000014L`. |
| 4. Completed truck | `sendPlate T-UTC-01` was ACKed. SQL returned `vis-1f0cef67-3a05-4110-bbd1-075eaa784185 | COMPLETED | 2026-08-10T17:30:58.784 | 2026-08-10T17:30:59.387 | 1`. |
| 5. Park/take/audit | `T-UTC-02` parked with `wi-447efa74-3258-490c-b550-6d42f9fc6878 QUEUED`. Take-next returned `IN_PROGRESS`, assignee `usr-demo-clerk`, queued `17:31:15.145Z`, started `17:31:23.707Z`. Audit returned `TAKE`, `elapsedSec: 8`, `occurredAt: 2026-08-10T17:31:23.736Z`; SQL returned `TAKE | usr-demo-clerk | 2026-08-10T17:31:23.736 | 8`. |
| 6. Visit search | Request used `since=2026-08-10T17:26:05.146Z` and returned the just-completed `T-UTC-01` visit with `startedAt: 17:30:58.784Z` and `completedAt: 17:30:59.387Z`. |
| 7. Six-service boot | Health checks: `18081 200`, `18082 200`, `18083 200`, `18084 200`, `18085 200`, `18086 200`. All services were stopped afterwards. |
| 8. Connector restored | Final SQL output: `tos 200 APPROVED`. |

### Before-and-after database duration

The shipped live evidence before the fix was:

```text
started_at                  completed_at                DATEDIFF(second)
2026-08-10 12:52:15.026     2026-08-10 14:52:23.846     7208
```

The forced-Sydney pre-fix property independently produced a raw
`DATEDIFF_BIG(millisecond, queued_at, completed_at)` of `36000051`, exposing its
ten-hour JVM offset. After the fix, the live visit query returned:

```text
started_at                  completed_at                DATEDIFF(second)
2026-08-10 17:30:58.784     2026-08-10 17:30:59.387     1
```

## Watch-it-fail evidence

The suite saves and replaces the JVM default with `Australia/Sydney` in
`@BeforeAll`, asserts that zone inside every property, and restores the original
`TimeZone` in `@AfterAll`.

Property 3 was written and run first against unfixed code:

```text
a work item completed immediately must not inherit the JVM's UTC offset
expected: between 0L and 10000L
 but was: 36000051L
```

Property 1 then failed with a database-written instant ten hours early. Property
4, after correcting an overlong test plate fixture, failed with an empty result
where the one-minute-old visit was expected. Property 2 passed before the fix,
which is the central cancellation trap from §2: the local-zone Java write and the
local-zone Java read were both wrong by the same offset. It therefore proves the
read and write remain paired after correction, but cannot be made red against the
old paired defect. Section 7 correctly requires explicit red evidence from
properties 1 and 3; property 4 supplied additional red evidence.

## Sweep findings and plan observations

The direct `Timestamp.from(...)` and `ResultSet.getTimestamp(...)` sweep found no
runtime production site outside the repositories named in §3. However,
`AdmissionRepository`'s private `now()` helper had two consumers omitted from the
column table: `lane_session.updated_at` and `lane_session.bound_at`. Replacing that
helper necessarily brought those Java-written values onto UTC too. This is a plan
inventory omission, not an additional repository.

The first version of §5 seeded immediately after bootstrap on a wiped volume. It
failed with `Invalid object name 'core.lane'` because bootstrap creates schemas,
logins and grants, not tables. The corrected plan's executed order—wipe, bootstrap,
boot core/runtime/edge so Flyway creates tables, then demo seed—succeeded.

Any existing development database contains mixed-zone history. This verification
wiped the local volume before producing post-fix evidence. There is deliberately
no migration or backfill because the product has not shipped to an existing client.

## Utility placement recommendation

Keep the two service-local copies in orca-edge and orca-runtime for now. A third
service needing the same conversion should trigger a deliberate proposal to move
the utility into a neutral platform module, with all six services considered at
once. That architectural decision should not be smuggled into this defect fix.
