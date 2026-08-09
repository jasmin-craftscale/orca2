# Comment clarity — making every comment stand on its own

**A plan for a focused build session. Self-contained: you need no context beyond this file and the repository.**

---

## 1 · The problem, in one example

`services/orca-core/src/main/resources/db/migration/V102__topology_views.sql` opens like this:

```sql
-- orca-core · the first published views (ADR-009, §C1).
--
-- Mechanism 2 of §B4: a service that needs a lane's devices reads a view in its
-- own transaction — no network hop, no latency on the gate path...
```

A developer opening that file learns nothing they can act on. They do not know what `ADR-009` is, where `§C1` lives, or what "Mechanism 2 of §B4" refers to — and after thirty-three lines of preamble the file has still not said the one thing that matters: **these are two read-only views that let other services see this installation's lanes and devices.**

The references point into `docs/ORCA_ARCHITECTURE.md`. That document is real and useful, but a code comment that *requires* it is a comment that fails the person reading the code. The meaning must be in the comment.

**Scale, measured:**

| Token | Occurrences |
|---|---|
| `§A1` / `§B4` / `§C2` style section references | 501 |
| `ADR-001` … `ADR-017` | 110 |
| `WP0`–`WP7` (work-package numbers) | 171 |
| `Phase 0`–`Phase 7` | 125 |
| `register NEW-4`, `register item 20`, `U2` | 26 |
| `DERIVED-FROM-1X` | 18 |

**Files affected: 248.** All 40 SQL files. 179 of 290 Java files. Every build file, every `application.yaml`, 3 of 6 XML files.

## 2 · What this delivers

**Every comment in the codebase explains itself to a developer who has never read the architecture document and never attended a planning session.**

Specifically:

1. A written standard for comments, with a worked example (§3), placed where authors will find it.
2. Every SQL file rewritten to that standard — **this is the priority**.
3. Every Java, Kotlin, YAML and BPMN comment rewritten to that standard.
4. A build check that fails the build when an opaque reference is reintroduced into code.
5. Proof that **not one byte of behaviour changed**.

## 3 · The standard

### The rule

> **A comment must be understandable on its own, by someone who has never seen this project's documents.** If understanding it requires knowing what a code, a section number, a phase or a work package refers to, the comment is broken.

### Banned in all code, migrations, build files and configuration

| Banned | Why |
|---|---|
| `§B4`, `§C1`, `§A7` … | Section numbers of a document the reader does not have open |
| `ADR-009`, `ADR-011` … | Decision-record numbers that mean nothing outside the corpus |
| `WP3`, `Work Package 3` | Planning artifacts. The plan is finished; the code is permanent |
| `Phase 1`, `Phase 2` … | Same. "Phase 3 built this" tells a maintainer nothing useful in 2029 |
| `register NEW-4`, `register item 20`, `U2`, `#27` | Register row identifiers |
| `the plan`, `the sheet`, `the brief`, `the profile` (bare) | Documents the reader cannot identify |
| `DERIVED-FROM-1X` (as a bare tag) | Means nothing until you say what it means |

### Explicitly still allowed

These are plain English and carry real information:

- **Dates and human attribution** — *"the product owner ruled on 7 August 2026 that…"*. This dates a decision and names who made it. Keep it.
- **Named documents, spelled out** — *"the platform architecture document"*, *"the local deployment guide"*. A reader can find those. Prefer a real path when one exists: `docs/deployment.md`.
- **Real code identifiers** — class names, table names, column names, file paths. These are findable by grep.
- **Statements of fact about the old system**, once explained: instead of `DERIVED-FROM-1X`, write *"extracted from the Go system in production today, which is evidence about what the fielded equipment expects — not a vendor specification."*

### The shape a good comment takes

For any non-trivial file, answer four questions in this order:

1. **What is this?** — one sentence, plain, no jargon. A stranger must be able to repeat it back.
2. **Why does it exist / who uses it?** — the problem it solves and the caller who depends on it.
3. **What decisions are baked in, and what did they cost?** — where a choice was made, say what it is and why, in words.
4. **What will surprise you?** — the trap, the non-obvious constraint, the thing that looks like a mistake and is not.

Not every file needs all four. A one-line helper needs none of them. **Length is not the goal — comprehension is.** Cut anything that does not teach.

### The two reference files — read these first, in the repository

**Two files have already been rewritten to this standard and are your calibration
sample.** Read them, and read their diff against the previous commit. They were
chosen to bracket the difficulty: one SQL migration, and the Java class with the
hardest translation problem in the repository.

| File | Why it is the reference |
|---|---|
| `services/orca-core/src/main/resources/db/migration/V102__topology_views.sql` | The example in §1, rewritten. Shows the four-question shape on a migration, and how a trap (`GO` separators) and a grant rationale survive in plain words |
| `platform/scope/src/main/java/com/lynxis/orca/platform/scope/Scope.java` | The hardest case: its comment referenced both an architecture section *and* an unresolved design question. Shows how to explain a decision whose justification is that something is **still open** — the three enforcement problems are now spelled out, and "an open design question" replaces a register row number |

**Match their depth and their tone.** If your rewrite is shorter than these on a
file of comparable weight, you have probably deleted knowledge rather than
translated it.

## 4 · Ground rules

**Comments only. No behaviour may change.** No SQL statement, no Java expression, no configuration value, no identifier. This is what makes the work verifiable — see §7, item 1, which proves it mechanically.

**Do not touch these files at all:**

- `services/orca-runtime/src/main/resources/db/migration/V110__flowable_common.sql` through **`V114__flowable_eventregistry.sql`**. These five are extracted verbatim from the workflow engine's own jars by a Gradle task. They are not ours to edit — a comment change here is a fork of a third-party schema. Their headers explain the re-extraction rule; leave them exactly as they are.
- Everything under `docs/`. Section numbers and decision-record codes are **correct** there: that is the corpus those references point into. This plan is about code, not documents.
- Anything under `build/`, `.git/`, or `node_modules/`.

**Do not delete knowledge to satisfy the rule.** If a comment records a real trap, a measurement, or a decision's cost, that content must survive in plainer words. Deleting a warning is worse than leaving it opaque. If you cannot work out what a comment meant, say so in the report rather than guessing or dropping it.

**Do not invent explanations.** If a comment references `§C3` and you cannot determine what it was asserting, read `docs/ORCA_ARCHITECTURE.md` to find out. If it is still unclear, leave the comment, mark it in the report, and move on. A gap reported is worth more than a gap filled with a plausible guess.

## 5 · Editing a migration means rebuilding your local database

Flyway records a checksum for every migration it applies, computed over the whole
file — comments included. So editing a migration's comments makes a database that
already ran the old version refuse to start:

```
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 102
```

**Nothing is deployed anywhere. Rebuild the database and carry on:**

```bash
cd deploy && docker compose down -v && docker compose up -d
docker compose run --rm bootstrap
docker compose run --rm demo-seed
```

That is the whole procedure. Do the rewrite in one sweep so it is needed once, and
put the three commands in your report so the team runs them once too.

**Two things this does not change.** The refusal is correct and stays: on a real
installation the answer to a schema change is always a *new* migration, never an
edit to one that has shipped. And **never weaken `validate-on-migrate`** — that
setting is what stops two databases silently disagreeing about their schema.

## 6 · Work packages

Each package is one commit. WP1 first; WP2 is the priority deliverable. WP3–WP6 are independent of each other once WP1 lands and may be done in any order.

### WP1 · The standard, and the check that enforces it

Two deliverables, landing together — a convention without enforcement decays into a description of what somebody once did.

1. **Write the standard into `AGENTS.md`** as a short section — the rule, the banned list, the allowed list, and a pointer to `docs/comment-clarity-plan.md` for the worked example.

   ⚠️ **`AGENTS.md` is 194 lines against a hard 200-line budget, so you have six lines.** That budget is deliberate: the file loads into context in full on every agent launch, and adherence measurably drops as it grows. Six lines is enough for the rule and a pointer, and **not** enough for the banned list — so either compress ruthlessly, or reclaim space by cutting the least valuable existing lines. If you cut, name exactly what you removed and why in the report. Do not silently exceed the budget.

   Keep every `@`-prefixed token inside backticks, as that file already requires — a bare `@Entity` is parsed as a file import by some agents.
2. **Add a build check** — `build-checks/src/test/java/com/lynxis/orca/checks/CommentClarityRule.java`, in the style of the existing file-reading rules (`ScopeIndexRule` and `InternalSurfaceRule` both read repository files rather than bytecode; copy their shape and their use of `RepositoryFiles`).

   It must scan `**/src/**` plus `*.gradle.kts` and the migration directories, and fail on any of the banned patterns from §3. It must **not** scan `docs/`, `build/`, or the five untouchable Flowable migrations. Its failure message must name the file, the line, the offending token, and what to do instead — the existing rules' messages are the quality bar.

   **Watch it fail before you commit it.** Introduce one banned token, run `./gradlew check`, confirm the build stops and the message is genuinely useful, then revert. A check nobody has seen fail may not be wired in — this repository has shipped that exact defect before.

**Done when:** the standard is in `AGENTS.md`; the check exists, has been *observed* failing on a deliberate violation, and passes on a tree you have not yet cleaned only if you scope it to the directories you are about to fix — otherwise expect it to fail everywhere until WP2–WP5 land. Record in the report which order you chose and why.

> **Sequencing note:** the check will fail against the un-rewritten tree. Either (a) land the check last, or (b) land it disabled-with-a-reason and enable it in the final commit. **(a) is recommended** — keep the check as the *closing* commit of WP1 after WP2–WP5 have landed, so the build is never knowingly red. State which you did.

### WP2 · The SQL — the priority

**27 files**: 24 under `services/*/src/main/resources/db/migration/*.sql` (29 total, minus the five untouchable Flowable ones) and **3** under `platform/{outbox,lease,idempotency}/src/main/resources/db/platform/<name>/*.sql`.

⚠️ **Count and path both corrected 9 Aug 2026.** This previously said "6 under `platform/*/src/main/resources/db/migration/`". There are three, and they live under `db/platform/<name>/`, not `db/migration/`. The wrong number came from a `find` that counted compiled copies under `build/` — if you count files, exclude `build/`.

**With the seven files under `deploy/` (work package 3) that makes 34 SQL files in total**, which is the number the "done when" below uses.

These get the most care. A migration is the permanent record of *why the schema looks like this* — it is read years later by someone debugging production, and it is the one file they cannot ask a question about.

For each migration, the opening comment must state:

- **What this migration creates or changes**, in one plain sentence.
- **What the tables are for** — who writes them, who reads them, what real-world thing each row represents.
- **Why any non-obvious column, constraint or index exists.** Especially: filtered unique indexes, `COLLATE` clauses on CHECK constraints, and indexes whose column order is deliberate. Each of these encodes a hard-won lesson; say what the lesson was.
- **The traps.** `GO` batch separators, case-sensitivity of collations, ordering requirements, anything that fails with a misleading error message.

**Done when:** every one of the 34 files opens with a comment a new developer could act on; no banned token remains in any of them; the stripped-comment comparison in §7 item 1 shows zero SQL statement changes.

### WP3 · The standalone SQL in `deploy/`

**7 files**: `deploy/bootstrap/V001`–`V004`, `deploy/demo/*.sql`, `deploy/adopt-flowable/*.sql`.

These are **not** Flyway-managed, so there is no checksum risk. They are also the first SQL a new developer reads, since they run them on day one. Same standard as WP2.

**Done when:** as WP2, and `docker compose run --rm bootstrap` plus `docker compose run --rm verify-isolation` still pass (§7).

### WP4 · Java — `platform/` and `build-checks/`

The five primitives and the architecture rules. These carry the densest and most valuable commentary in the repository — the *reasoning* is the point of these files, and much of it is genuinely excellent. Translate the references; preserve the argument.

**Done when:** no banned token in `platform/**` or `build-checks/**`; every class-level comment opens by saying what the class is for in plain words.

### WP5 · Java — the services

**179 files across `services/`.** Largest volume, lowest density — many are one-line translations.

Suggested order, which is also rough dependency order for a reader: `orca-core`, then `orca-runtime` (the largest, and the module comments matter most here), then `orca-edge`, then the three skeleton services.

**Done when:** no banned token under `services/**/src/**`.

### WP6 · Build files, configuration and the process definition

`*.gradle.kts` (13 files), `services/*/src/main/resources/application.yaml` (13 files), `services/orca-runtime/src/main/resources/processes/*.bpmn20.xml` and the two test fixtures.

The BPMN file deserves particular care: its comments explain what a workflow compiler must be able to emit, and it is read by a developer building that compiler who may have no other context at all.

**Done when:** no banned token; the services still start (§7).

## 7 · Verification — run these, do not reason about them

| # | Check | Expected |
|---|---|---|
| 1 | **No behaviour changed.** `python3 deploy/tools/assert-comments-only.py` — strips every comment from the committed and working versions of each changed file and asserts the remainder is byte-identical. Written and verified for this task; run it after every package, not once at the end | `PASS — N file(s) checked; every change is a comment change.` Any `CHANGED` line is a bug in your edit, not a judgement call. It has been watched to catch a deliberate one-word SQL change |
| 2 | `./gradlew check integrationTest --rerun-tasks` from the repository root | `BUILD SUCCESSFUL`, 222 integration tests, 0 failures. Takes roughly 8 minutes |
| 3 | **Clean-database migration.** `cd deploy && docker compose down -v && docker compose up -d && docker compose run --rm bootstrap`, then start core, runtime and edge with `--spring.profiles.active=local` | All three start; each applies its migrations; all three answer `/actuator/health` with `UP` |
| 4 | **The rebuild procedure works.** Run the three commands in §5 against a database holding the old checksums, then start all six services | Every service starts and migrates cleanly. This is the instruction the team will follow, so it is run rather than assumed |
| 5 | `docker compose run --rm bootstrap` and `docker compose run --rm verify-isolation` | Bootstrap is a no-op on an already-bootstrapped database; isolation reports `PASS — 36 checks` |
| 6 | **One truck through the gate.** `docker compose run --rm demo-seed`, then `./gradlew sendPlate -Pplate=T-COMMENTS-01`; inspect the database | Visit `COMPLETED`, a `RAISE_GATE` command `EXECUTED`, and a `visit.completed` row in the outbox. This is the standing regression canary — `docs/phase-1-demo.md` has the queries |
| 7 | **The clerk loop.** Force the manual branch and take a work item through claim and completion, per `docs/phase-1-demo.md` §8c and the Phase 3 report's live-loop section | The visit parks, a work item is queued, completing it advances the visit |
| 8 | **The new build check fails on a deliberate violation** | The build stops, naming file, line and token |
| 9 | `grep` the banned patterns across code one final time, excluding `docs/`, `build/` and the five Flowable migrations | Zero hits |

**If ports 8081–8086 are occupied** (another stack may hold them), use the offset technique in `docs/phase-1-demo.md` §3 rather than changing committed configuration.

## 8 · When you are blocked

- **A comment's meaning is unrecoverable.** Read `docs/ORCA_ARCHITECTURE.md` for the referenced section. Still unclear — leave it, list it in the report, move on.
- **A rewrite would change behaviour.** It must not. Stop, report the file.
- **Rebuilding the database does not resolve a checksum failure.** Stop and report; do not weaken `validate-on-migrate` to get past it.
- **A banned token appears in a string literal, a test fixture or an error message** rather than a comment. Judgement: if a *user* would see it, it is probably wrong for the same reason and should be rephrased; if it is a test asserting on the corpus, leave it. Report either way.

## 9 · The report this work ends with

`docs/comment-clarity-report.md`, in the shape the phase reports use:

1. **What was rewritten** — counts per package, per file type.
2. **The proof nothing changed** — the stripped-comment comparison, how you ran it, and its output.
3. **Every verification item from §7 with its real result**, including anything that failed first.
4. **Decisions this plan did not dictate** — every judgement call you made, with the reasoning. Where you chose to keep a reference in spelled-out form, say why.
5. **Comments you could not translate** — the ones whose meaning was unrecoverable, by file and line.
6. **Anything you found wrong while reading** — this work touches nearly every file in the repository, which makes it the widest fresh-eyes pass anyone will get. Stale comments, comments contradicting their code, dead code, misleading names: **report them, do not fix them.** They are a separate concern and mixing them in would make item 2's proof impossible.
7. **The developer-facing note** — the exact database-reset instruction, ready to send to the team.

---

*One sentence to hold on to while working: **the reader has never read our documents and never will.** Everything they need is in front of them, or it is nowhere.*
