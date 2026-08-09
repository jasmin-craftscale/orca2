# Comment clarity, part two — the Java, and everything else that is not SQL

**A plan for one focused session. Self-contained: you need nothing but this file and the repository.**

The SQL half of this work is finished, validated and merged. This is the rest. Read §1 to know what you are joining, §3 for the standard you are judged against, and §5 before you touch a single Java file — it names four traps that the SQL half did not have and that will silently produce a broken sweep if you meet them unprepared.

---

## 1 · What has already happened, and what it proved

`docs/comment-clarity-plan.md` set a standard: **a comment must be understandable by someone who has never read this project's documents.** Comments across this repository leaned on architecture section numbers, decision-record codes, phase and work-package numbers and register row identifiers — none of which mean anything to a person reading the code.

**34 SQL files were rewritten to that standard and independently validated.** Comment volume nearly doubled (1,278 → 2,399 lines) while not one byte of SQL changed. The validation ran the full suite, rebuilt the database from empty, and drove a truck through the gate.

Two calibration files were rewritten before that work started and are still your reference:

| File | Shows you |
|---|---|
| `services/orca-core/src/main/resources/db/migration/V102__topology_views.sql` | The four-question shape, and how a trap survives in plain words |
| `platform/scope/src/main/java/com/lynxis/orca/platform/scope/Scope.java` | **The Java reference.** Its comment leaned on both an architecture section *and* an unresolved design question. Read this one twice — it is the closest thing to what you are about to do ninety times |

**What the SQL half got wrong, so you do not repeat it.** The validation found one real defect: two migrations dropped the *fact that a rule is enforced*. The comment explained beautifully why an index must lead with the site column — and lost that a build check reads the file and **stops the build** if it does not. A developer would have read a hard constraint as a style preference. Losing an enforcement fact is the single most likely way to fail this task well.

## 2 · What this session delivers

Every comment in the remaining non-SQL files explains itself. Specifically:

1. **Work package B** — `platform/` and `build-checks/` (91 files). The densest reasoning in the repository.
2. **Work package C** — `services/` Java (354 files), the Gradle build files, the per-service configuration, and the process definitions.
3. **Work package D** — the build-check *failure messages*, which are strings rather than comments and need their own commit and their own justification. §7.
4. A report: `docs/comment-clarity-java-report.md`.

**This is a lot of files.** Package B is the valuable half and package C is mostly light. If you run out of capacity, **finish B completely, land it, and report C as not started** — a half-swept `services/` tree is worse than an unswept one, because nobody can tell which files were considered.

## 3 · The standard

> **A comment must be understandable on its own, by someone who has never seen this project's documents.** If understanding it requires knowing what a code, a section number, a phase or a work package refers to, the comment is broken.

**Banned everywhere in code:** `§B4`-style section numbers · `ADR-009`-style decision codes · `WP3` / "Work Package 3" · `Phase 1` · `register NEW-4`, `register item 20`, `U2`, `#27` · bare "the plan", "the sheet", "the brief", "the profile" · `DERIVED-FROM-1X` as a bare tag.

**Explicitly allowed, and removing them is itself a defect:**

- **Dates and human attribution** — *"the product owner ruled on 7 August 2026"*. Keep them.
- **Named documents, spelled out**, preferably as a real path — `docs/deployment.md`.
- **Real code identifiers** — class, method, table, column, view, setting and file names. **These are findable by grep and must survive.** Replacing `ScopeIndexRule` with "a build check" is a defect, not a simplification.
- **Sibling file references by name** — `V101__execution.sql`, `AdmissionService`. Ratified by the SQL half: a shipped filename never changes, so it is a real openable path.
- **Facts about the old Go system**, once explained in words instead of tagged.

**The shape**, for anything non-trivial: what is this, in one plain sentence · why it exists and who uses it · what decisions are baked in and what they cost · what will surprise you. Not every file needs all four; a one-line helper needs none.

**Depth is a criterion.** A rewrite substantially shorter than the original, on a file of comparable weight, has probably deleted knowledge rather than translated it.

## 4 · Ground rules

**Comments only. No behaviour may change.** No expression, no identifier, no annotation, no configuration value, no string literal. Package D is the single, explicit exception and has its own rules.

**Do not touch:**
- Anything under `docs/` — section numbers are *correct* there, that is the corpus they point into.
- Any `.sql` file. That half is done, validated and merged.
- `build/`, `.git/`.

**Do not delete knowledge to satisfy the rule.** If a comment records a trap, a measurement, an enforcement, or a decision's cost, that must survive in plainer words. **Especially enforcement** — see §1.

**Do not invent.** If you cannot determine what a comment meant, read `docs/ORCA_ARCHITECTURE.md` for the section it cites. If it is still unclear, leave the comment, list it in the report, and move on. A gap reported is worth more than a gap filled with something plausible.

## 5 · ⚠️ Four traps Java has that SQL did not

### 5.1 · The checker had a blind spot for Java, and it has just been fixed

`deploy/tools/assert-comments-only.py` strips comments from both versions of a file and asserts the remainder is identical. Until now it stripped `//` with a regex — which truncates `String u = "http://a";` at `http:`, making a real change to that string **invisible**. There are 93 such lines in this repository.

**It is now string-aware**: it walks the text tracking string literals, character literals and Java text blocks, and only treats `//` and `/*` as comments outside them. Regression-tested against five cases including text blocks containing SQL.

**You must still prove it before you trust it (§7.0).** Do not skip that.

### 5.2 · Nothing compiles Javadoc, so nothing will tell you when you break it

There is **no javadoc task and no doclint** anywhere in the build. A malformed `{@link}`, an unclosed `<p>`, or a `@param` naming a parameter that does not exist will compile perfectly and fail nothing.

So: when you edit a Javadoc block, keep its tags valid by inspection. `{@link X}` must name a type that exists and is imported or fully qualified. `{@code ...}` needs no import. If in doubt, prefer plain prose in backticks over a `{@link}` you have not verified.

### 5.3 · Text blocks hold SQL, and that SQL is code

Repository classes hold their statements in Java text blocks (`"""`). The SQL inside them is **code, not comment** — a `--` line inside a text block is part of a statement being sent to the database. Never edit inside a text block. The checker now understands this; you must too.

### 5.4 · Some comments describe an enforced rule, and the enforcement is the point

The failure from the SQL half, restated because it is the one that matters: when a comment says a rule is *checked*, the rewrite must still say it is *checked*, and should name the check. Before: *"Leads with the scope column — ScopeIndexRule insists."* A rewrite explaining only *why* the rule is sensible has lost the fact that the build stops you.

`platform/` and `build-checks/` are dense with exactly this kind of comment. Treat every mention of a rule, a guard, a constraint or a check as load-bearing.

## 6 · Work packages

### WP-B · `platform/` and `build-checks/` — do this first, and completely

**91 files**: 64 under `platform/*/src/main/`, 14 under `platform/*/src/{test,integrationTest,testFixtures}/`, and 13 in `build-checks/`.

These carry the reasoning that explains why the system is shaped as it is — why the outbox refuses to write outside a transaction, why a lease is worth less than its fencing token, why an empty scope means deny rather than all. **Much of this commentary is genuinely excellent.** Your job is to remove the corpus references and leave the argument stronger, not to rewrite good prose for its own sake.

Suggested order, easiest calibration first: `platform/web`, `platform/idempotency`, `platform/lease`, `platform/outbox`, `platform/scope` (whose `Scope.java` is already done — use its siblings' treatment to match), then `build-checks`.

For `build-checks` specifically: each rule class explains a failure that actually happened. That history is the most valuable thing in those files — keep it, and say what the failure was rather than citing where it was written up.

**Done when:** no banned token in any comment under `platform/**` or `build-checks/**`; every class-level comment opens by saying what the class is for in plain words; every enforcement fact still reads as enforcement; `./gradlew check` green; the checker passes.

### WP-C · `services/` Java, build files, configuration, process definitions

**386 files**: 325 under `services/*/src/main/java`, 29 integration-test classes, 14 `*.gradle.kts`, 12 `application.yaml`, 6 `*.bpmn20.xml`.

Highest volume, lowest density — many need a single phrase replaced. Spend the care where it is worth spending:

- **`orca-runtime`'s module-level comments and its `execution` and `workitem` packages** — the hardest domain logic in the product.
- **`gate-visit.bpmn20.xml`** — its comments are read by a developer building the workflow compiler, who may have no other context at all. It is written deliberately as if a compiler had emitted it; keep that framing.
- **`application.yaml`** — the comments there explain operational consequences (an executor that is switched off accepts visits and advances none). Those are the comments most likely to save someone at 2 a.m.

Suggested order: `orca-core`, `orca-runtime`, `orca-edge`, the three skeleton services, then build files, then configuration, then the process definitions.

**Done when:** no banned token in any comment in scope; `./gradlew check integrationTest` green; the services still start; the checker passes.

### WP-D · The build-check failure messages — a separate commit, deliberately

**19 lines** of failure-message text inside `build-checks/src/test/java/com/lynxis/orca/checks/*.java` contain banned tokens. Examples, verbatim:

```
"ADR-014 makes the OpenAPI document the source of truth, and the ONLY thing "
"invisible in the served document — see docs/ai-context-report.md §7.1."
"§C2's integration seam is narrow on purpose: that narrowness is what makes "
```

**These are the most user-facing instance of the whole problem.** A developer reads them at precisely the moment the build has stopped them and they need to know why. `§C2` helps nobody in that moment.

But they are **strings, not comments**, so changing them fails the comments-only proof by design. Therefore:

1. Do WP-B and WP-C first and land them with the proof passing.
2. Then, in a **separate, clearly-labelled commit**, rewrite these message strings to say in plain words what the rule is and why.
3. For that commit the comments-only proof **does not apply and you must say so in the report.** Its safety net instead is: `./gradlew check` green, and — because a check's message is only visible when it fires — **you must watch at least two of the rewritten messages actually print**, by introducing a deliberate violation and reverting it.

**Do not change what a rule checks. Only what it says when it fails.**

## 7 · Verification — run these, do not reason about them

### 7.0 · Prove the checker before you believe its PASS

Change one Java token in a file you have edited — a field name, a constant, a number. Run the checker. Confirm it reports `CHANGED`, names the file, quotes the differing line, and **exits non-zero**. Revert.

Then do the same with a change *after* a `//` inside a string literal, on one of the 93 such lines — that is the case the tool was just fixed for, and the one case where a naive checker would silently pass.

A checker nobody has watched fail may not be wired in. This repository has shipped that defect before.

### 7.1 – 7.6

| # | Check | Expected |
|---|---|---|
| 1 | `python3 deploy/tools/assert-comments-only.py --base <commit before your first>` | `PASS`, with the file count you expect. Run after every few files, not once at the end |
| 2 | Banned-token scan over comment lines only (§7.7 gives the command) | No output |
| 3 | `./gradlew check` | `BUILD SUCCESSFUL`, 60 unit tests, all ten build checks green |
| 4 | `./gradlew check integrationTest --rerun-tasks` | `BUILD SUCCESSFUL`, **222 integration tests, 60 unit, 0 failures, 0 errors, 0 skipped** |
| 5 | All six services start | Each answers `/actuator/health` with `UP`. **Core first** — runtime and edge refuse to start until core's views exist |
| 6 | One truck through the gate | Visit `COMPLETED`, a `RAISE_GATE` command `EXECUTED`, a `visit.completed` row in `runtime.outbox` |

**Item 5 is not optional and is not covered by item 4.** Every integration suite constructs its beans directly rather than starting a service, so a broken bean definition passes all 222 tests. This repository has shipped a service that passed everything and could not boot.

Running the stack, if it is not already up:

```bash
cd deploy && docker compose up -d && docker compose run --rm bootstrap
docker compose run --rm demo-seed
```

Then, from the repository root, each in its own terminal, **core first**:

```bash
./gradlew bootRun -p services/orca-core    --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local'
./gradlew bootRun -p services/orca-edge    --args='--spring.profiles.active=local'
./gradlew sendPlate -Pplate=T-JAVA-01
```

If ports 8081–8086 are taken, `docs/phase-1-demo.md` §3 has the offset incantation. If `sendPlate` gets no acknowledgement, edge has not yet won the lane lease — it polls every five seconds; wait and retry before assuming a defect.

### 7.7 · The banned-token scan

```bash
git diff --name-only <base>..HEAD | grep -E '\.(java|kts|yaml|xml)$' | while read -r f; do
  grep -nE '^\s*(\*|//|<!--)|^\s*#' "$f" \
    | grep -inE '§|ADR-[0-9]|WP[0-9]|phase [0-9]|register (NEW|item|#)|DERIVED-FROM-1X' \
    && echo "  ^^^ $f"
done
```

Expect no output. If a hit is inside a **string literal** rather than a comment, that is package D or an out-of-scope report item — not something to edit inline.

## 8 · When you are blocked

- **A comment's meaning is unrecoverable.** Read `docs/ORCA_ARCHITECTURE.md` for the cited section. Still unclear — leave it, list it, move on.
- **A rewrite would change behaviour.** It must not. Stop, report the file.
- **A banned token is in a string a user sees.** WP-D if it is a build-check message; otherwise report it, do not edit it.
- **You are running out of capacity.** Finish the package you are in, land it, and say plainly in the report what was not started. Do not spread thin.

## 9 · The report this work ends with

`docs/comment-clarity-java-report.md`:

1. **What was rewritten** — counts per package and file type, and comment-line totals before and after. Script these; they are checkable and they will be checked.
2. **The proof nothing changed** — the checker's output, how you ran it, and the two deliberate failures you watched in §7.0.
3. **Every verification item from §7 with its real result**, including anything that failed first and what you did.
4. **Decisions this plan did not dictate**, with reasoning.
5. **Comments you could not translate** — by file and line.
6. **Enforcement facts you found and preserved** — name a few explicitly. This is the failure mode from the SQL half and a reviewer will look for it first.
7. **Anything you found wrong while reading.** This is the widest fresh-eyes pass anyone will get over the Java. Stale comments, comments contradicting their code, dead code, misleading names: **report them, do not fix them** — mixing fixes in would destroy the proof in item 2.
8. **What you did not do**, plainly.

---

## 10 · Known, already reported, and not yours to fix

Recorded so you do not rediscover them or waste time deciding:

- **`CommentClarityRule` does not exist.** The build check that would stop these references being reintroduced is the last piece of this work, after this session. When it is written it needs two exemptions or it cannot land: the five Flowable migrations, and banned tokens inside string literals.
- **`deploy/stubs/device-host`** returns `DERIVED-FROM-1X` in a response body that is stored verbatim in `edge.command_log.device_response` — the column an operator reads after an incident.
- **`docs/phase-1-demo.md`'s `q()` helper omits `-I`**, so writes through it fail against any table with a filtered unique index, with an error naming no table.
- **Neither `docs/phase-1-demo.md` nor `deploy/README.md` records that core must start before edge.**
- **`platform/AGENTS.md`** should say that editing one of the three primitive migrations invalidates checksums in every schema it reached — four schemas for the outbox, six for the lease, three for the idempotency record. *(Those four numbers are verified.)*

*One sentence to hold on to: **the reader has never read our documents and never will.** Everything they need is in front of them, or it is nowhere.*
