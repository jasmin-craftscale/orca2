# Kickoff prompts — the comment-clarity sweep

**Three sessions, in this order. Session A runs alone and is reviewed before B and C start.**

Each prompt below is self-contained: paste it into a fresh session opened in
`~/Documents/Projects/orca`. The sessions never touch the same files, so B and C
can run at the same time once A has been reviewed.

Shared reading for all three: **`docs/comment-clarity-plan.md`**.

**Not in scope for any session** — `AGENTS.md`, the new build check, and this plan
itself are the orchestrator's, so that no two sessions edit the same file.

---

## Session A — the SQL (run this first)

```
Read docs/comment-clarity-plan.md in full before doing anything. It is
self-contained and explains the standard, the rules and the verification.

Your scope is the SQL, and only the SQL:

  - 24 files under services/*/src/main/resources/db/migration/*.sql
    EXCLUDING V110 through V114 in orca-runtime, which are extracted from the
    workflow engine's own jars and must not be touched at all.
  - 6 files under platform/*/src/main/resources/db/migration/*.sql
  - 7 files under deploy/ — bootstrap/V001-V004, demo/*.sql, adopt-flowable/*.sql

That is 37 files. Rewrite every comment in them so that it explains itself to a
developer who has never read this project's documents: no section numbers, no
decision-record codes, no phase or work-package numbers, no register item
references. The plan's section 3 has the full rule and the two files already
rewritten to the standard — read those two first and match their depth.

Migrations are the priority. A migration is the permanent record of why the
schema looks the way it does, read years later by someone who cannot ask anyone
a question. For each one, say what it creates, what the tables are for, who
writes and reads them, why any non-obvious constraint or index exists, and what
will surprise the reader.

Comments only. No SQL statement, no identifier, no value may change. Prove it
after every few files with:

    python3 deploy/tools/assert-comments-only.py

Editing a migration changes its Flyway checksum, so your local database will
refuse to start afterwards. That is expected — rebuild it, per section 5 of the
plan. Nothing is deployed anywhere.

Work in commits of roughly ten files so the diff stays reviewable. When you are
done, run the verification table in section 7 of the plan and write
docs/comment-clarity-report.md in the shape section 9 describes.

If you cannot work out what a comment meant, leave it and list it in the report.
Never invent an explanation, and never delete a warning to satisfy the rule —
you are translating knowledge, not removing it.
```

---

## Session B — Java in `platform/` and `build-checks/`

```
Read docs/comment-clarity-plan.md in full before doing anything.

Your scope is:

  - platform/**/src/**/*.java
  - build-checks/**/*.java

Do not touch services/, docs/, AGENTS.md, or any SQL — other sessions own those.

Rewrite every comment so it explains itself to a developer who has never read
this project's documents: no section numbers, no decision-record codes, no phase
or work-package numbers, no register item references. The plan's section 3 has
the rule; platform/scope/.../Scope.java is already rewritten to the standard and
is your calibration sample.

These files carry the densest reasoning in the repository, and much of it is
genuinely valuable — the argument for why a primitive is shaped the way it is.
Translate the references and preserve the argument. Where a comment explains a
failure that was actually observed, keep that: it is the most useful kind of
comment there is.

Comments only. No expression, no identifier may change. Prove it with:

    python3 deploy/tools/assert-comments-only.py

Then ./gradlew check to confirm the tree still compiles and the rules still pass.

Commit in reviewable chunks. Report in the shape section 9 of the plan
describes, as docs/comment-clarity-report-platform.md.
```

---

## Session C — Java in `services/`, plus build files and configuration

```
Read docs/comment-clarity-plan.md in full before doing anything.

Your scope is:

  - services/**/src/**/*.java          (about 190 files)
  - *.gradle.kts and services/*/build.gradle.kts
  - services/*/src/main/resources/application.yaml
  - services/orca-runtime/src/main/resources/processes/*.bpmn20.xml
    and the two .bpmn20.xml fixtures under src/integrationTest/resources/

Do not touch platform/, build-checks/, docs/, AGENTS.md, or any SQL — other
sessions own those.

Rewrite every comment so it explains itself to a developer who has never read
this project's documents: no section numbers, no decision-record codes, no phase
or work-package numbers, no register item references. The plan's section 3 has
the rule and names two files already rewritten to the standard.

This is the largest scope and most of it is light — many comments need a single
phrase replaced. Spend your care where it is worth spending: the module-level
comments in orca-runtime, and the BPMN process definition, whose comments will
be read by a developer building the workflow compiler who may have no other
context at all.

Comments only. No expression, no configuration value, no identifier may change.
Prove it with:

    python3 deploy/tools/assert-comments-only.py

Then ./gradlew check, and confirm the services still start.

Commit in reviewable chunks. Report in the shape section 9 of the plan
describes, as docs/comment-clarity-report-services.md.
```

---

## After all three

The orchestrator closes the work:

1. Add the standard to `AGENTS.md` (six lines of headroom against its 200-line budget).
2. Add the build check that fails on a reintroduced reference, **watched to fail** before it is committed.
3. Re-run the full verification independently: `./gradlew check integrationTest --rerun-tasks`, a clean-database rebuild, one truck through the gate, and the clerk loop.
