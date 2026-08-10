# Implementation Plan — One time zone in the database

**Self-contained. Written to be executed autonomously and verified by the executor.**

**This plan is prescriptive.** Where a decision is made it is an instruction, not an
option. If you are about to make a judgement call not written here, stop and ask.

**Scope:** `orca-runtime` only. **No new table, no migration, no contract change.**
No new endpoint. You are correcting how existing code converts between
`java.time.Instant` and SQL Server `DATETIME2`.

**Where to work:** branch `feature/utc-timestamps` off `main`. Do not commit to
`main`. Do not touch `integration`, `readmodel` or `notify` — other developers own
those.

---

## 0 · The defect, proven

**SQL Server `DATETIME2` carries no time zone. It stores a wall-clock reading.** So
the zone is decided entirely by whoever writes, and must be re-applied by whoever
reads. This codebase currently does neither consistently.

Two ways a value gets written today:

| Written by | Produces |
|---|---|
| `DEFAULT SYSUTCDATETIME()` in the migration | **UTC** wall-clock |
| `Timestamp.from(instant)` in Java | **The JVM's local** wall-clock |

**They are both in use, in adjacent columns of the same tables.** Measured on a
UTC+2 machine against real rows:

```
external_id      started_at (DB-written)   completed_at (Java-written)   diff_sec
vis-c9d5267c…    2026-08-10 12:52:15.026   2026-08-10 14:52:23.846       7208
vis-a90e6fbb…    2026-08-10 12:51:44.755   2026-08-10 14:52:14.048       7230
vis-e74b18bd…    2026-08-10 12:38:49.366   2026-08-10 14:39:05.094       7216
```

Those visits completed in **seconds**. The database says two hours, because
`started_at` is UTC and `completed_at` is local. The container's own clock confirms
it: `SYSUTCDATETIME()` and `SYSDATETIME()` both read `12:59`.

The same fault on the read side, measured live: a work item queued **8 seconds**
earlier reported `elapsedSec=7207` in its audit trail, and its `occurredAt`
serialised two hours behind what the database held.

**Consequences today:** every visit duration, every work-item elapsed time, every
operator-presence duration and every timestamp the API returns is wrong by the
machine's UTC offset — invisible on a UTC machine, wrong at every European site.

⚠️ **This is not one bug. It is an inconsistency**, so a partial fix makes it worse
— see §2.

## 1 · The established pattern — copy it

`services/orca-edge/src/main/java/com/lynxis/orca/edge/persistence/Utc.java` already
solves this, and its javadoc describes this exact symptom (*"an event buffered one
second ago as two hours old"*). **Read it before writing anything.** It states the
conversion in **both** directions:

```java
	static Instant instantAt(ResultSet rs, String column) throws SQLException {
		LocalDateTime stored = rs.getObject(column, LocalDateTime.class);
		return stored == null ? null : stored.toInstant(ZoneOffset.UTC);
	}

	static Timestamp timestampOf(Instant instant) {
		return instant == null ? null
				: Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
	}

	static Timestamp now() {
		return timestampOf(Instant.now());
	}
```

`platform/outbox` and `platform/lease` were corrected the same way on 10 August.
`orca-runtime` was never swept.

## 2 · ⚠️ The rule that governs this whole plan

**Fix reads and writes together, per column. Never one without the other.**

A Java-written value read back by Java currently **round-trips correctly** — both
sides are wrong by the same offset, so they cancel. Fixing only the read side turns
five working columns into five broken ones.

| Column | Written by | Read today | If you fix ONLY reads |
|---|---|---|---|
| `work_item.queued_at` | database (UTC) | zone-less | **fixed** ✓ |
| `work_item_audit.occurred_at` | database (UTC) | zone-less | **fixed** ✓ |
| `work_item.started_at` | Java (local) | zone-less | **breaks** ✗ |
| `work_item.completed_at` | Java (local) | zone-less | **breaks** ✗ |
| `work_item.sla_breached_at` | Java (local) | zone-less | **breaks** ✗ |
| `user_activity.started_at` | Java (local) | zone-less | **breaks** ✗ |
| `user_activity.ended_at` | Java (local) | zone-less | **breaks** ✗ |

**Both sides, in the same commit, per repository.**

## 3 · Every site to change

Verified by sweep on 10 August. **If you find one not listed here, report it — do not
assume the list is complete.**

### 3.1 Writes — `Timestamp.from(…)` → `Utc.timestampOf(…)` / `Utc.now()`

| File | Line | Column |
|---|---|---|
| `execution/persistence/AdmissionRepository.java` | 239 | `execution_event.received_at` |
| `execution/persistence/AdmissionRepository.java` | 286 | the private `now()` helper — feeds `execution.completed_at` |
| `workitem/persistence/WorkItemRepository.java` | 153, 169 | `work_item.started_at` |
| `workitem/persistence/WorkItemRepository.java` | 210, 222 | `work_item.completed_at` |
| `workitem/persistence/WorkItemRepository.java` | 236 | `work_item.sla_breached_at` |
| `workitem/persistence/PresenceRepository.java` | 38 | `user_activity.ended_at` |
| `workitem/persistence/PresenceRepository.java` | 56 | `user_activity.started_at` |

### 3.2 Reads — `rs.getTimestamp(…)` → `Utc.instantAt(rs, …)`

| File | Line | Column |
|---|---|---|
| `workitem/persistence/WorkItemRepository.java` | 270 | `occurred_at` |
| `workitem/persistence/WorkItemRepository.java` | 297–301 | `queued_at`, `started_at`, `completed_at`, `sla_breached_at` |
| `workitem/persistence/PresenceRepository.java` | 82, 88 | `ended_at`, `started_at` |

The private `instant(Timestamp)` helper in `WorkItemRepository` becomes unused —
delete it rather than leaving a second conversion path.

### 3.3 ⚠️ `VisitReadRepository` is internally inconsistent — fix it too

It **reads** correctly (a private `instantOf` with a UTC calendar) but **binds its
search parameter** zone-lessly:

```java
	parameters.add(java.sql.Timestamp.from(since));      // line 55 — WRONG
```

So the `since` window on `GET /api/v1/visits` is shifted by the machine's offset: a
caller asking for the last hour gets a window two hours off. **Replace the binding
with `Utc.timestampOf(since)`, and replace the private `instantOf` with `Utc.instantAt`
so the class has one conversion, not two.**

## 4 · Where the helper lives

**Create `services/orca-runtime/src/main/java/com/lynxis/orca/runtime/persistence/Utc.java`**,
mirroring edge's. Make it `public` (edge's is package-private and runtime needs it
from two packages).

⚠️ **Do not move edge's copy, do not import it, and do not put this in `platform/`.**
Importing across services is forbidden — a service may not depend on another
service's packages, and a build check enforces it. Promoting it to `platform/` is a
larger decision that affects all six services; **note in your report that a third
copy would be the moment to make it**, and leave the decision there.

## 5 · Existing data is mixed-zone, and that is fine

Rows already written carry both conventions. **There is no migration and no
backfill.** The product ships to new clients only, and this is development data.

**Wipe and rebuild your local database** so you are not reading pre-fix rows.

⚠️ **`bootstrap` does not create tables.** It creates the seven schemas, seven logins
and their grants — that is all. **Each service migrates its own schema when it
boots**, which is what bootstrap's own closing line means by *"every service can now
migrate its own schema on startup"*. So a freshly wiped database has no tables until
the services have started once, and `demo-seed` writes to **both** `core.*` (a dozen
tables) and `runtime.connector_config` / `connector_route`, as two different logins.
Seed before booting and it fails twice, once per schema.

**The order, in full:**

```bash
cd deploy
docker compose down -v            # drops the volume — this is what makes it a rebuild
docker compose up -d
docker compose run --rm bootstrap # schemas, logins, grants. NO tables.
```

Now boot the services once so they migrate (§0.4 has the commands and the port
notes — core first, it publishes the views the other two wait for). Once all three
answer `200` on `/actuator/health`:

```bash
cd deploy && docker compose run --rm demo-seed
```

⚠️ *Corrected 10 Aug 2026: this section previously ran `demo-seed` straight after
`bootstrap`, which cannot work. It was written without being executed against a wiped
volume — the machine it was written on already had a migrated database.*

⚠️ **Say in your report that any existing dev database holds mixed-zone history**, so
nobody debugs a two-hour gap that predates the fix.

## 6 · The tests — this is the part that matters

A test on a UTC machine passes whether or not you fix anything. **Every test below
must force a non-UTC zone**, which is exactly why this defect survived four phases.

Add to `services/orca-runtime/src/integrationTest/java/.../` a suite named
`UtcTimestampPropertiesIT`, and write these as properties:

1. **A database-written value reads back as the instant the database meant.** Insert
   a row letting `queued_at` default, read it, and assert it is within a few seconds
   of `Instant.now()` — **not two hours out.**
2. **A Java-written value reads back as the instant Java meant.** Write a known
   `Instant`, read it, assert equality to the millisecond.
3. **A duration across a DB-written and a Java-written column is real.** Create a work
   item, complete it, and assert `completed_at - queued_at` is seconds, not hours.
   **This is the property the shipped defect violates**, and it is the one to write
   first.
4. **The visit search window is honest.** A visit started one minute ago is inside a
   `since = now - 5 minutes` window and outside `since = now + 5 minutes`.

⚠️ **Force the zone.** The suite must run under a non-UTC zone or it proves nothing.
Set it explicitly in the test configuration — for example
`-Duser.timezone=Australia/Sydney` on the test JVM, or `TimeZone.setDefault` in a
`@BeforeAll` with restoration in `@AfterAll`. **State in your report which mechanism
you used and confirm you watched the test fail before the fix.**

⚠️ **Watch each fail against the UNFIXED code.** Stash your fix, run the suite, see
tests 1 and 3 fail, restore. A test that has only ever seen fixed code proves nothing.

## 7 · Verification

⚠️ **Stop the services and the Gradle daemons before any suite run.** They share the
`runtime` schema, and orphaned workers survive a failed run:

```bash
for p in 18081 18082 18083; do PID=$(lsof -nP -iTCP:$p -sTCP:LISTEN -t 2>/dev/null | head -1); [ -n "$PID" ] && kill $PID; done
./gradlew --stop
ps aux | grep -c "[G]radleWorkerMain"    # expect 0
```

| # | Check | Expected |
|---|---|---|
| 1 | `./gradlew build` | Green |
| 2 | `./gradlew check integrationTest --rerun-tasks` | Green. **Count must rise from 236** by the number of tests you added |
| 3 | Tests 1 and 3 of §6 **watched to fail** against unfixed code | Both fail; record the messages |
| 4 | Boot the three services, drive a truck to `COMPLETED` | `SELECT started_at, completed_at, DATEDIFF(second, started_at, completed_at) FROM runtime.execution` → **diff is seconds, not ~7200** |
| 5 | Park a truck, take-next, read the audit | `elapsedSec` is single digits, and `occurredAt` matches the database |
| 6 | `GET /api/v1/visits?since=<now minus 5 min>` | Returns the visit just driven |
| 7 | All six services boot | |
| 8 | Connector route restored | `tos 200 APPROVED` |

## 8 · When you are stuck

| Situation | Do |
|---|---|
| A column is not in §3's list | **Report it.** The list is a sweep, not a guarantee |
| A test passes on your machine | Check the zone is actually non-UTC. This is the whole trap |
| Fixing a read breaks an existing test | Expected — you have not fixed its write. §2 |
| Something here looks wrong | Say so with evidence. Five defects were found in the last plan this way |

## 9 · The report — `docs/utc-timestamps-report.md`

1. What was changed, per file and column.
2. The §7 table with real output, including the before-and-after `DATEDIFF`.
3. **Which zone-forcing mechanism you used**, and the failure messages you observed
   before the fix.
4. Any site found that §3 did not list.
5. Your recommendation on promoting `Utc` to `platform/` — **stated, not acted on.**
6. Anything that looked wrong — reported, not silently corrected.

---

*Start with §6 test 3, written against the unfixed code, and watch it fail. Everything
else is mechanical once that test exists.*
