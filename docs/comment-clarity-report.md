# Comment clarity — the SQL

**Every SQL comment in this repository now explains itself to a developer who has
never read this project's documents and never will.** 33 files rewritten, then
re-read end to end and corrected; not one byte of SQL changed, and that is proven
mechanically rather than asserted.

The standard, the ground rules and the verification table this work was done
against were in `docs/comment-clarity-plan.md`, **which has been removed now that
the work is finished** — a plan nobody should execute again is a plan somebody
eventually executes again. This report is what survived it: what was done, what
was decided along the way, and what was found wrong while reading.

---

## 1 · What was rewritten

**34 SQL files were in scope. 33 were rewritten here; the 34th —
`services/orca-core/.../V102__topology_views.sql` — was already rewritten as the
calibration sample and was used as the reference rather than touched.**

| Package | Files | Comment lines before → after |
|---|---|---|
| `services/orca-core/` migrations | 10 | 525 → 995 |
| `services/orca-runtime/` migrations | 6 | 238 → 431 |
| `services/orca-edge/` migrations | 4 | 93 → 174 |
| `services/orca-{fleet,portal,sync}/` baselines | 3 | 33 → 96 |
| `platform/{outbox,lease,idempotency}/` | 3 | 116 → 239 |
| `deploy/bootstrap/`, `deploy/demo/`, `deploy/adopt-flowable/` | 7 | 273 → 464 |
| **Total** | **33** | **1,278 → 2,399** |

Three commits, roughly ten files each:

| Commit | Contents |
|---|---|
| `524d33e` | orca-core's ten migrations and the six service baselines |
| `ef54df2` | orca-edge's three and orca-runtime's five |
| `fba291c` | the three platform primitives and all seven files under `deploy/` |
| `0a0ff46` | two file paths I had introduced incorrectly, corrected |
| *(review)* | a full re-read: one invented explanation, two invented consequences and one miscount fixed; nineteen sibling-migration references named by filename; the real identifiers the first pass generalized away put back (§4.5, §4.7) |

**The five Flowable migrations `V110`–`V114` in `orca-runtime` were not touched at
all**, as instructed. They are extracted verbatim from the engine's own jars by a
Gradle task; a comment change there would be a fork of a third-party schema.

### The count in the plan is wrong, and this is what is actually there

The plan's §6 said **30 files** in one sentence and **34** in
the next, and described the platform migrations as *"6 under
`platform/*/src/main/resources/db/migration/*.sql`"*.

There are **three**, and they are not under `db/migration/`:

```
platform/outbox/src/main/resources/db/platform/outbox/V001__outbox.sql
platform/lease/src/main/resources/db/platform/lease/V002__service_lease.sql
platform/idempotency/src/main/resources/db/platform/idempotency/V003__idempotency_record.sql
```

The path differs because these are applied into each *consuming service's* schema
by that service's own migration run, so they live in a location a service adds
rather than in the service's own migration folder. `platform/scope` and
`platform/web` have no tables at all. The correct total is 24 service migrations
(29 minus the five untouchable ones) + 3 platform + 7 under `deploy/` = **34**.

---

## 2 · The proof that nothing but comments changed

`deploy/tools/assert-comments-only.py` strips every comment from the committed
and the working version of each changed file and asserts the remainder is
byte-identical. It was run after every few files, and again per commit:

```bash
python3 deploy/tools/assert-comments-only.py
```

```
PASS — 15 file(s) checked; every change is a comment change.   # commit 1
PASS —  8 file(s) checked; every change is a comment change.   # commit 2
PASS — 10 file(s) checked; every change is a comment change.   # commit 3
PASS —  3 file(s) checked; every change is a comment change.   # commit 4
```

Across the whole sweep, against the commit this work started from:

```bash
python3 deploy/tools/assert-comments-only.py --base 737a1c2
```

```
  skipped  docs/ORCA_ORCHESTRATOR_HANDOVER.md
  skipped  docs/comment-clarity-report.md
  skipped  docs/partner-event-api-from-1x.md

PASS — 33 file(s) checked; every change is a comment change.
```

(The three skipped files are Markdown, not SQL, and none of them was touched by
this work — two arrived in the commit before it, and the third is this report.)

**It caught a real mistake once.** While rewriting
`platform/outbox/.../V001__outbox.sql`, an edit that replaced a comment block
immediately above a `CREATE INDEX` re-emitted the statement, leaving the file with
the index created twice. The checker reported `CHANGED` on the next run and the
duplicate was removed before it reached a commit. That is the failure mode this
tool exists for, and it is the reason it is worth running every few files rather
than once at the end.

---

## 3 · Verification

Every row was run, not reasoned about. The numbering is the plan's §7.

| # | Check | Result |
|---|---|---|
| 1 | `assert-comments-only.py`, after every few files and once across the sweep | **PASS.** 33 files; caught one real mistake mid-sweep (§2). Re-run after the review pass: **PASS, 33 files** |
| 2 | `./gradlew check integrationTest --rerun-tasks` | **BUILD SUCCESSFUL in 6m 4s**, 68 tasks executed. **222 integration tests, 0 failures, 0 errors, 0 skipped**; 60 unit tests, 0 failures. Re-run after the review pass: **BUILD SUCCESSFUL in 6m 54s**, same 222/0 and 60/0 |
| 3 | Clean-database migration, then start the services | **PASS.** See below |
| 4 | The rebuild procedure works against a database holding the old checksums | **PASS**, and the failure it repairs was observed first. See below |
| 5 | Bootstrap is a no-op on an already-bootstrapped database; isolation passes | **PASS.** *"Database [orca] already exists — nothing to do"*, then *"verification passed: 7 schemas, 7 logins, each confined to its own"*; isolation reported **`PASS — 36 checks`** |
| 6 | One truck through the gate | **PASS.** See below |
| 7 | The clerk loop | **PASS.** See below |
| 8 | The new build check fails on a deliberate violation | **NOT APPLICABLE TO THIS SESSION.** `CommentClarityRule` is the other work package's deliverable and does not exist yet; nothing was added here for it to check. Flagged rather than silently skipped |
| 9 | `grep` the banned patterns one final time | **Zero hits** in any comment, in all 34 in-scope files. Three hits remain in string *values* — §4.1 |

### Item 4, in full: the checksum failure was observed before it was repaired

The plan predicts this and it happened exactly as described. Starting orca-core
against the pre-existing local database:

```
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 001
Migration checksum mismatch for migration version 002
Migration checksum mismatch for migration version 100
Migration checksum mismatch for migration version 101
Migration checksum mismatch for migration version 102
…
```

Note **001 and 002** in that list: those are the outbox and lease tables from
`platform/`, applied into core's schema by core's own migration run. Editing a
comment in a platform primitive invalidates the checksum in *every* schema it was
applied into, not just one. Worth knowing before anyone edits one of those three
files again.

The three commands in §7 of this report then cleared it. Afterwards **all six
services start and answer `UP`**:

```
8081 {"groups":["liveness","readiness"],"status":"UP"}   orca-core
8082 {"groups":["liveness","readiness"],"status":"UP"}   orca-runtime
8083 {"groups":["liveness","readiness"],"status":"UP"}   orca-edge
8084 {"groups":["liveness","readiness"],"status":"UP"}   orca-portal
8085 {"groups":["liveness","readiness"],"status":"UP"}   orca-sync
8086 {"groups":["liveness","readiness"],"status":"UP"}   orca-fleet
```

### Item 6, in full: one truck

`./gradlew sendPlate -Pplate=T-COMMENTS-01`, then the database:

```
edge.event_buffer   evt-9a9b3ea6-…  LANE-DEMO-01  ACKED  0 attempts
runtime.execution   vis-fe54a1e2-…  COMPLETED     T-COMMENTS-01
edge.command_log    RAISE_GATE      EXECUTED      {"status":"OK","code":200,…}
runtime.outbox      1  lane:LANE-DEMO-01  visit.completed
```

### Item 7, in full: the clerk loop

The connector route was repointed so that the stub's answer maps to nothing, a
second truck was sent, and the work item was taken through claim and completion:

```
runtime.execution   vis-07f1de63-…  ACTIVE  T-COMMENTS-02        ← parked at the wait state
runtime.work_item   wi-bb77ea0e-…   QUEUED  scr-demo-manual      ← eventData {"connectorOutcome":"HTTP_200"}
POST …/take         → IN_PROGRESS, assignee usr-demo-clerk
POST …/complete     → COMPLETED,   completionDurationSec 0
runtime.execution   vis-07f1de63-…  MANUAL  T-COMMENTS-02        ← the visit advanced
work_item_audit     TAKE · COMPLETE, both actor usr-demo-clerk
```

The route was restored to 200 afterwards.

### Everything re-run after the review pass

The review touched migrations again, so the whole live sequence was repeated from
a clean database: `down -v` → `up -d` → bootstrap → the three services → isolation
→ demo seed → one truck.

```
bootstrap          verification passed: 7 schemas, 7 logins, each confined to its own
verify-isolation   PASS — 36 checks
core · runtime · edge   8081 · 8082 · 8083 all UP
sendPlate T-REVIEW-01   runtime.execution  COMPLETED
                        edge.command_log   RAISE_GATE  EXECUTED
                        runtime.outbox     1  lane:LANE-DEMO-01  visit.completed
```

**One thing worth reporting from that run.** Starting core, runtime and edge in
parallel, orca-edge lost the race and refused to start:

```
orca-edge will not start: 2 required published view(s) are absent —
core.topology_lane, core.topology_device. orca-core publishes these; it must have
migrated before this service starts. This is a deployment ordering problem, not a
fault in this service.
```

That is the readiness guard working exactly as designed, and the message says so
in plain words — but it means **the three services have a start ORDER**, and
neither `docs/phase-1-demo.md` nor `deploy/README.md` says core must be up first.
Reported, not fixed.

**The `QUOTED_IDENTIFIER` trap documented in `deploy/demo/demo-site.sql` bit
during this run, which is a small vindication of keeping the warning.** Updating
`core.user_account` through `sqlcmd` without `-I` failed with *"UPDATE failed
because the following SET options have incorrect settings: 'QUOTED_IDENTIFIER'"* —
because `user_account` carries a filtered unique index. `docs/phase-1-demo.md`'s
`q()` helper does not pass `-I`, so anyone following that guide will hit the same
thing on any write to a table with a filtered index. Reported, not fixed.

---

## 4 · Decisions the plan did not dictate

### 4.1 · Banned tokens that are values, not comments — left alone and reported

Three places carry a banned token inside a **string literal**. Changing them would
change behaviour, so the ground rules forbid it. All three are reported here
instead. In each case a *user* sees the string, so by the plan's own judgement
call (§8, last bullet) they are probably wrong for the same reason a comment
would be:

1. **Every service baseline writes an architecture section reference into the
   database.** All six `V100__baseline.sql` files attach an extended property to
   their schema whose value is, for example, `N'§C1 — the world as configured'`.
   An operator inspecting the database — exactly the reader that migration was
   written for — sees a section number of a document they do not have.
2. **`V107__settings_workspace_audit.sql` seeds a setting description reading
   `'…a genuine setting 1.x carried'`.** `1.x` is the banned bare tag, and this
   string is shown in the settings console.
3. **Four more seeded descriptions in the same file** end in `PROVISIONAL` or
   `PROVISIONAL - the class list is not closed (phase-1 report)`, and one says
   `the audit class this phase introduces`. Same problem, same surface.

Fixing any of these is a data change and belongs in a new migration, not in a
comment sweep.

### 4.2 · Real file paths kept; section numbers of those files dropped

The plan explicitly allows named documents spelled out, and prefers a real path.
Five comments genuinely need to point somewhere, so they carry a path and no
section number:

| File | Points at | Why it has to |
|---|---|---|
| `V104__entitlement_catalog_seed.sql` | `docs/entitlement-catalog-from-1x.md` | The generated rows have no meaning without their extraction source |
| `V108__device_catalog_completion.sql` | `docs/device-catalog-completion-from-1x.md` | Same |
| `deploy/adopt-flowable/…` | `docs/flowable-adoption.md` | The script is half of a procedure; the procedure is the other half |
| `deploy/demo/demo-site.sql` | `docs/phase-3-report.md` | The operator-linking step of the walkthrough is written out there and nowhere else |
| several | `deploy/bootstrap/`, `deploy/demo/seed.sh`, `deploy/README.md` | Real, runnable things a reader needs |

`docs/phase-3-report.md` contains a phase number **in its filename**. I kept it as
a path rather than paraphrasing it away, because a reader can act on a path and
cannot act on "the report from that phase". Every such path was checked to exist.

### 4.3 · Where the old system is named, it is described rather than tagged

`1.x`, `DERIVED-FROM-1X` and `the sheet` are gone everywhere. They are replaced
with a description a stranger can use — *"the Go system in production today"*,
*"the schema there is defined by object-relational mappings rather than by any
CREATE TABLE, so it is evidence of what the fielded product needs, not a
specification"*. Numbered translation rules (`rule 5`, `rule 7`, `rule 11`) are
replaced by the rule itself, stated where it applies. That is why several files
grew: eleven numbered rules referenced 30-odd times each become the sentence they
stand for, at the point it matters.

### 4.4 · Sibling migrations are named by filename, not as "an earlier migration"

Nineteen comments referred to another migration in the same directory. They now
name it — `V106__device_registry.sql` rather than "a later migration". Migration
filenames are real, findable paths, which the standard explicitly allows and
prefers; a Flyway version never changes once it has shipped, so there is no rot
risk; and "an earlier migration" in a directory of eleven is a search task, not a
reference.

### 4.5 · Real identifiers restored where the first pass generalized them away

The first pass replaced some grep-able names with prose — "a build check reads
this file", "the view orca-core publishes", "a shared component that records what
has been handled". That is knowledge loss dressed as clarity, and the standard
lists real identifiers among the things to KEEP. A second pass put them back
alongside the explanation rather than instead of it:

`ScopeIndexRule`, `RetentionClassRule`, `ScopeSeamRule`, `IdentityTables`,
`AuditTables`, `@PersistentTable`, `AdmissionPropertiesIT` and its `it_admission`
schema, `IdempotencyStore`, `core.topology_lane`, `core.topology_operator`,
`lane_session`, `payload_blob`, `ZapPacket`, `EXPECTED_PROCESSING_TIME_SEC` and
`MAX_PROCESSING_TIME_SEC`, `ORCA_DEVICE_HOST_STUB_PORT`, `keycloak_subject`,
`gate-visit`, `flowable.database-schema-update`, `deleted_at`, and the five
command actions `RAISE_GATE`, `LOWER_GATE`, `PRINT`, `SET_IO`, `PTZ_PRESET`.

The three platform migrations also regained the list of services they are applied
into — four for the outbox, all six for the lease, three for the idempotency
record — which the first pass had flattened to "several services".

### 4.6 · Two forward references added

`V101__world_model.sql` describes `device_type` as a provisional free-text column,
which it no longer is — `V106` replaces it with a catalog reference and drops it.
I added a bracketed sentence saying so. Likewise `V106` says its knowingly-short
catalogs are completed later.

This is new information rather than translation, and it is the one place I went
beyond the brief. The justification: a migration is read years later by someone
debugging, and a comment describing a column that no longer exists — with no hint
that it was superseded — is worse than no comment. Both statements were verified
against the migrations that make them true.

### 4.7 · Three things a second pass found wrong in MY OWN work, and fixed

A full re-read after the first four commits found three real defects. They are
recorded because the same traps are available to anyone doing this kind of sweep.

**1. An invented explanation, in `deploy/bootstrap/V002__schemas_and_logins.sql`.**
The original said nothing about why each statement is wrapped in `EXEC`. I
supplied a reason — that `CREATE USER` would otherwise fail to compile against a
login that does not exist yet — and it is wrong. Checked against the running
database with `SET PARSEONLY ON`:

```
IF 1 = 0 CREATE SCHEMA zzz_probe;                   →  Msg 156: Incorrect syntax near the keyword 'SCHEMA'
IF 1 = 0 CREATE LOGIN [zzz] WITH PASSWORD = 'X';    →  parses fine
IF 1 = 0 CREATE USER [zzz] FOR LOGIN [zzz_absent];  →  parses fine
IF 1 = 0 ALTER USER [zzz] WITH DEFAULT_SCHEMA = …;  →  parses fine
```

Only `CREATE SCHEMA` genuinely needs the wrapper — it must be the first statement
in its batch and cannot sit under an `IF`. The comment now says that and nothing
more. **This is exactly what the plan's "do not invent explanations" rule is for,
and I broke it; the check that caught it was reading my own sentence again and
asking whether I actually knew it.**

**2. Two invented consequences.** "the console showed two identical rows"
(`V103__identity.sql`) and "showed up as a person listed twice in the console"
(`V105__teams_templates.sql`) describe behaviour of the old system's user
interface that I have not seen. Both now state only what is verifiable: that the
old system had no unique constraint, so duplicates were possible there.

**3. A miscount.** `V101__execution.sql` said "the four columns carried along in
the index"; the index carries three.

Three claims that WERE checked and held, for the record — the case-insensitive
collation the `COLLATE` clauses exist to defeat, the `CREATE VIEW` batching rule,
and the PUSH/PROMPT definitions:

```
DATABASEPROPERTYEX('orca','Collation')      →  SQL_Latin1_General_CP1_CI_AS
'push' IN ('PUSH','PROMPT')                 →  ACCEPTED  (this is the trap)
'push' COLLATE Latin1_General_100_BIN2 IN … →  REFUSED   (this is the fix)
SELECT 1; CREATE VIEW …                     →  Msg 111: 'CREATE VIEW' must be the first statement in a query batch
```

### 4.8 · Work-package numbers replaced by what the work-package delivered

Every migration's opening line was `orca-core · WP3 — …` or `orca-runtime · Phase
3 WP1 — …`. Those are gone. What replaced them is a one-sentence statement of what
the file creates, in the shape the plan asks for: *"The device registry, finished:
what kinds of device exist, what a device's input and output ports are wired to…"*.

Where the sequencing genuinely mattered it survives as a fact rather than a
number: `V115__work_items.sql` explains that it is numbered above the engine's own
set because Flyway refuses an out-of-order migration on a database that has
already applied them.

---

## 5 · Comments I could not translate

**None.** Every comment in scope resolved, either from
`docs/ORCA_ARCHITECTURE.md`, from `docs/core-config-schema-from-1x.md`, or from
the surrounding SQL.

One came close and is worth recording, because the *specific* reference is now
unrecoverable and what I wrote is a translation of its meaning rather than of its
words:

> **All six `V100__baseline.sql` files** carried *"It also proves the mechanism
> the whole of §7 items 4, 4b and 9b rest on"*. Those item numbers belong to the
> Phase 0 plan, which was **deleted** in commit `737a1c2` as part of shrinking
> `docs/`. The numbered items cannot be recovered.
>
> The surrounding sentence makes the meaning unambiguous — the file proves the
> service authenticated as its own login and wrote into its own schema — so the
> rewrite states that, and states it as the property rather than as a list of
> verification items. The count and identity of those items is lost; nothing that
> depended on them is.

---

## 6 · What I found wrong while reading

**Reported, not fixed.** Fixing any of these would have destroyed the proof in §2.

### 6.1 · `V108__device_catalog_completion.sql` contains a duplicated block

Two `UPDATE` statements, and the comment above them, appear **twice**:

```
services/orca-core/src/main/resources/db/migration/V108__device_catalog_completion.sql
  lines 45-46   UPDATE … AXIS_PTZ_CAMERA … ;  UPDATE … PELCO_PTZ_CAMERA … ;
  lines 50-51   UPDATE … AXIS_PTZ_CAMERA … ;  UPDATE … PELCO_PTZ_CAMERA … ;   ← identical
```

It is an editing accident — a comment block pasted twice with its statements. It
is **harmless in effect**: each statement matches on the code it is replacing, so
the second copy matches no rows and changes nothing. But it is confusing to read,
it makes the file look like it is doing something subtle that it is not, and the
migration has shipped, so the correct repair is a note or a new migration rather
than an edit.

The rewritten comment on the second copy says only that these are the same
corrections continued and that a statement whose row is already corrected matches
nothing. It does not claim the duplication was intentional.

### 6.2 · `V101__world_model.sql` in orca-core describes a column that is now gone

Its `device_type` comment ran to seven lines explaining a provisional vocabulary
that `V106` removes entirely. Nothing pointed forward. I added a forward reference
(§4.4) rather than leaving a reader to discover it; the underlying issue —
migrations aging out of accuracy with no mechanism to notice — is general and
worth a thought beyond this file.

### 6.3 · The retention class names are still marked provisional in seeded data

`V107` seeds four retention-window settings whose descriptions say `PROVISIONAL`
because *"the class list is not closed"*. That was true when written. Whether it
is still true is not something a comment sweep can establish, and the strings are
user-facing (§4.1). Worth someone confirming, and either closing the list or
saying plainly in the console that it is open.

### 6.4 · `deploy/bootstrap/verify-isolation.sh` is easy to mislocate

Two comments referred to it as bare `verify-isolation.sh`. I first "corrected"
that to `deploy/verify-isolation.sh`, which does not exist — the file is
`deploy/bootstrap/verify-isolation.sh`, while the *compose service* that runs it
is named `verify-isolation`. Both were fixed in `0a0ff46`. Flagged because the
same mistake is available to anyone reading `deploy/README.md`, where the compose
service name is what appears.

### 6.5 · The plan's own file counts are wrong

Covered in §1. Not a code defect, but the next person to work from that document
will hit it.

### 6.6 · `docs/phase-1-demo.md`'s query helper omits `-I`

Its `q()` helper runs `sqlcmd` without `-I`, so every write it is used for against
a table carrying a filtered unique index fails with a message about
`QUOTED_IDENTIFIER` and no hint about which table. That is more than a third of
the tables in `core`. Hit during verification (§3, item 7). One flag on one line.

### 6.7 · The device-host stub returns a banned tag in a body that is stored

Not SQL, so out of scope, but it surfaced while checking the truck run. The stub
under `deploy/stubs/device-host` answers with:

```json
{"status":"OK","code":200,"message":"STUB. DERIVED-FROM-1X: 200 AND a body that decodes is what 1.x treats as performed.", …}
```

That string is written verbatim into `edge.command_log.device_response`, which is
exactly the column an operator reads after an incident — so it is a reader-facing
`DERIVED-FROM-1X`, in a database, and it is the same defect this work removed from
the comments. It belongs to whichever session owns `deploy/stubs/`.

### 6.8 · The three on-site services have an undocumented start order

orca-edge refuses to start until orca-core has migrated, because it checks that
the published views it reads actually exist. The refusal is correct and its
message is excellent. But nothing in `docs/phase-1-demo.md` or `deploy/README.md`
says core must come up first, and starting the three in parallel therefore fails
about half the time. Hit during the review pass (§3).

### 6.9 · A comment edit in `platform/` invalidates checksums in several schemas

Not a defect, but it is written down nowhere and it surprised this session. The
three primitive migrations are applied into each *consuming* service's own schema,
so editing one produces a checksum mismatch in every schema it reached — four for
the outbox, all six for the lease, three for the idempotency record. That is why
the first service started after this work reported mismatches for versions 001 and
002 alongside its own.

Each of the three files now says so in its own header. It is also worth a line in
`platform/AGENTS.md`, which currently describes those files only as being defined
once.

---

## 7 · The developer-facing note

**Send this to the team.**

> The SQL migrations' comments were rewritten. **No SQL statement changed** — but
> Flyway checksums the whole file, comments included, so a database that already
> ran the old versions will refuse to start:
>
> ```
> Validate failed: Migrations have failed validation
> Migration checksum mismatch for migration version 102
> ```
>
> **Nothing is deployed anywhere. Rebuild your local database once and carry on:**
>
> ```bash
> cd deploy && docker compose down -v && docker compose up -d
> ```
>
> ```bash
> cd deploy && docker compose run --rm bootstrap && docker compose run --rm demo-seed
> ```
>
> Two things this does **not** change, and neither should be worked around:
>
> - **The refusal is correct.** On a real installation the answer to a schema
>   change is always a *new* migration, never an edit to one that has shipped.
>   This rebuild is a one-off because nothing is installed anywhere yet.
> - **Never weaken `validate-on-migrate`.** That setting is the only thing
>   stopping two databases from silently disagreeing about their schema.
