# ORCA — AI context report

**Per §7 of `docs/ai-context-brief.md` · 7 August 2026 · branch `phase-0-foundations`**

What was written, what was verified by executing it, what was decided that the
brief did not dictate, and what a new agent still will not find. Every "pass"
below was produced by running the command, not by writing the file that should
satisfy it.

---

## 1 · What was built, per deliverable

| # | Deliverable | Lines | What it carries |
|---|---|---|---|
| **D1** | `AGENTS.md` (root) | **158** / 200 | The eight required sections in order: what the repository is (with the two laws verbatim) · where the truth lives, closing on the never-invent-a-resolution sentence verbatim · seven hard-rule rows, each naming the check class that fails · two rules enforced outside ArchUnit (database login, ADR-011 `/internal/**`) · how to work (contract-first, properties-not-paths, checks land with the code) · commands, every one executed · decision discipline and current scope · definition of done · the three-line maintenance note |
| **D2** | `CLAUDE.md` (root) | 5 | Byte-identical to the brief's block. `diff` clean |
| **D3** | `platform/AGENTS.md` | **56** / 80 | The purity rule and what it inspects; a defect-per-primitive table; changing a primitive means its tests still *prove* the property; the four integration suites `test` skips; where `@PersistentTable`/`@RetentionClass`/`Growth` actually live and why; what `platform/web` carries; why `platform/` is exempt from the scope seam |
| **D3** | `services/orca-runtime/AGENTS.md` | **59** / 80 | The five modules and what each owns; both module-wall tests including `domain`; cross-module data through `readmodel`; the modules are empty by design and what breaks on the first real class; admission, and that it is **unproven** pending WP0 |
| **D3** | `build-checks/AGENTS.md` | **54** / 80 | Tests only, output is build failures; a rule does not count until it has been watched to fail (with `ac0eb0d` as the repository's own evidence); registering with `ImportedSetGuard`; declaring and recording empty governed sets; what `OrcaClasses.production()` sees and why jars are not excluded |
| **D3** | 3 × nested `CLAUDE.md` | 1 each | Exactly `@AGENTS.md`, 11 bytes each |
| **D4** | `.claude/settings.json` | 17 | Byte-identical to the brief's block. Parses |
| **D5** | `docs/ai-context-report.md` | — | This file |

Committed in three commits, staged by explicit path: `c8899fe` (brief + `AGENTS.md`
+ `CLAUDE.md`), `77b264c` (nested files + settings), and this report.

**Not created, per the brief's out-of-scope list:** `.cursor/rules/*.mdc` (would
load the same content twice), `.github/copilot-instructions.md`, skills, README
changes, corpus edits, build or CI changes. No file under `platform/`,
`services/`, `build-checks/src/`, contracts, migrations or Gradle was touched.

---

## 2 · The §5 verification table, with actual results

| # | Check | Result |
|---|---|---|
| **1** | Every command in `AGENTS.md` §5 executes | **Pass**, all eight — see §3 below for the exit statuses and the two that needed environment overrides on this machine |
| **2** | Every cited class, rule, path and doc section exists | **Pass.** 16 cited paths `ls`-verified; 8 rule/helper classes verified; the four `*PropertiesIT` suites verified; `ac0eb0d` verified to exist *and* to be the platform-purity matcher fix (`git show --stat`: one file, `PlatformPurityRule.java`); `HealthController` count verified as exactly 6; `ORCA_ARCHITECTURE.md` §D1 verified to hold 17 ADR rows and to say "Seventeen decisions" and "Five decisions changed"; `@Entity` and `@Scheduled` verified as **zero** across every `src/main` tree; runtime's five modules verified to hold 20 `package-info.java` files and nothing else |
| **3** | Budgets hold | **Pass.** `AGENTS.md` 158 ≤ 200; nested 56, 59, 54 ≤ 80 |
| **4** | The `@` gotcha holds | **Pass.** A checker that strips fenced blocks and inline code spans, then scans the remainder, reports **0** bare `@` in all four `AGENTS.md` files and **exactly 1** in each of the four `CLAUDE.md` adapters — the `@AGENTS.md` import line itself. Output quoted in §4 |
| **5** | `CLAUDE.md` adapters byte-exact | **Pass.** Root: `diff` against the brief's D2 block (lines 60–64) is empty. Nested: 1 line, 11 bytes, `@AGENTS.md`, three times |
| **6** | `.claude/settings.json` parses and matches D4 | **Pass.** `python3 -m json.tool` exit 0; `diff` against the brief's block (lines 82–98) is empty |
| **7** | No retelling | **Pass, after one rewrite.** Measured rather than asserted — see §5 |
| **8** | The build still passes untouched | **Pass.** `./gradlew build` after all three commits: BUILD SUCCESSFUL, 68 tasks, exit 0 |
| **9** | Fresh-eyes gap list | §8 |

---

## 3 · Check 1, in full — every command, executed

| Command | Exit | Evidence |
|---|---|---|
| `./gradlew build` | **0** | BUILD SUCCESSFUL, 68 actionable tasks |
| `./gradlew test` | **0** | 52 unit tests, 0 failures, 0 errors |
| `./gradlew check` | **0** | 53 tasks; the seven check classes report 18 tests between them |
| `./gradlew integrationTest` | **0** | First run reported `UP-TO-DATE` from cache, so it was re-run with `--rerun-tasks`: **55 tasks executed, 44 s, 31 tests, 0 failures** across the four suites (outbox 8, lease 8, scope 8, idempotency 7) against real SQL Server |
| `docker compose up -d` (in `deploy/`) | **0** | `orca-sqlserver` and `orca-keycloak` both healthy |
| `./bootstrap/run.sh` | **0** | Re-run against the existing database: `V001` reported "already exists — nothing to do", `V004__verify.sql` printed *"7 schemas, 7 logins, each confined to its own"*. The "re-running is a no-op" claim in its header is therefore executed, not assumed |
| `./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local'` | started | `Started CoreApplication in 1.949 seconds`; `/actuator/health` → `200 {"status":"UP"}`; `/api/v1/health` → the shared envelope; `/openapi/orca-core.yaml` → 200 |
| Same, **without** the `local` profile | **1** | `IllegalStateException` from `InternalCredentialValidator.afterPropertiesSet` line 46: *"…is the committed local development fixture, and the `local` profile is not active."* The guard documented in `AGENTS.md` §5 fires exactly as described |

**Two environment overrides were needed on this machine, and neither is a
repository defect.** This laptop runs the ORCA 1.x devcontainer stack, so:

- **Ports 8081–8086 and 8080 are all held by `ssh` tunnels.** The two `bootRun`
  runs used `--server.port=18081`. The committed configuration keeps the brief's
  ports; that they bind on a machine where they are free is **not proven here** —
  the same limitation `phase-0-report.md` §8 records for its item 9.
- **The local stack is not on its default ports.** `deploy/.env` sets
  `MSSQL_PORT=21433` and `KEYCLOAK_PORT=18080` (the compose file defaults to
  1433/8080). `ORCA_DB_URL` and `ORCA_OIDC_ISSUER_URI` were exported accordingly.
  The first `bootRun` attempt without them failed on `Connection refused` to
  `localhost:1433` before ever reaching the credential guard — which is also how
  the bean ordering was observed: **Flyway initialises before the credential
  validator**, so a service with an unreachable database fails on the database,
  not on the credential.

`deploy/.env` was never read; the port mapping was obtained from `docker port`.

---

## 4 · Check 4, in full — the `@` grep

```
--- AGENTS.md: 0 bare @ token(s)
--- platform/AGENTS.md: 0 bare @ token(s)
--- services/orca-runtime/AGENTS.md: 0 bare @ token(s)
--- build-checks/AGENTS.md: 0 bare @ token(s)
--- CLAUDE.md: 1 bare @ token(s)
    line 1: '@AGENTS.md'
--- platform/CLAUDE.md: 1 bare @ token(s)
    line 1: '@AGENTS.md'
--- services/orca-runtime/CLAUDE.md: 1 bare @ token(s)
    line 1: '@AGENTS.md'
--- build-checks/CLAUDE.md: 1 bare @ token(s)
    line 1: '@AGENTS.md'
```

A plain `grep '@'` would not have proved this — it cannot tell a backticked
`@Entity` from a bare one. The checker strips fenced blocks and inline code spans
first, which is the same thing the import parser does.

---

## 5 · Check 7, in full — "no retelling", measured

Attestation is weak evidence, so this was measured: every run of **10 or more
consecutive words** shared between a new file and any of the eight corpus
documents was extracted and inspected.

**`services/orca-runtime/AGENTS.md` failed the first draft** — 8 shared runs, the
longest **27 words**, all from `ORCA_ARCHITECTURE.md` §C2. It was restating the
transaction-cohesion argument, the readmodel exception and the admission mechanism
rather than pointing at them. It was rewritten to name each property in one line
and send the reader to §C2. It now has **1** shared run of 13 words — the wording
of WP0's halt condition, quoted deliberately because it is a commitment.

Final state, all four files:

| File | Runs ≥ 10 words | Longest | Nature |
|---|---|---|---|
| `AGENTS.md` | 7 | 32 | The brief-mandated verbatim quotes (the canonical test example, the two laws, the never-invent sentence), plus command lines and file paths |
| `platform/AGENTS.md` | 2 | 13 | The five domain nouns the purity rule lists; one attribution to `phase-0-report.md` §5.1 |
| `services/orca-runtime/AGENTS.md` | 1 | 13 | WP0's halt condition |
| `build-checks/AGENTS.md` | 2 | 15 | The `ac0eb0d` incident, cited as the repository's own evidence |

No paragraph of architecture prose is duplicated in any of the four. Where a
concept needed explaining, the file names it and links the section.

---

## 6 · Decisions the brief did not dictate

1. **`AGENTS.md` §3 states what `ErrorEnvelopeRule` actually enforces, which is
   narrower than the brief's line for it.** This is the one place a deliverable
   deliberately departs from the brief's wording — reasoning in §7.1.
2. **The hard rules are a table, not one line per rule.** The brief says "one line
   per rule". A two-column table keeps rule and enforcing class adjacent on the
   same row, which is the property principle 3 is actually asking for, and it
   survives the reader skimming. Same information, same density.
3. **`ModuleWallRule` gets two rows rather than one.** The brief lists the module
   wall and "no service imports another service's internals" as separate rules
   that happen to share a class. They constrain different things, so they are two
   rows both citing `ModuleWallRule`, with the brief's own parenthetical — *it
   carries three tests, not two* — kept on the second.
4. **`ScopeSeamRule`'s row names the ten types it actually matches** rather than
   the brief's shorter "no `JdbcTemplate`/`EntityManager`". An agent needs to know
   `JdbcClient`, `DataSource` and raw `java.sql` are in the list too, because those
   are the ones it will reach for without thinking.
5. **The four skipped suites are named** in `AGENTS.md` §5 and again in
   `platform/AGENTS.md`. The brief asks for the warning; naming the suites makes it
   checkable — a reader can confirm the claim in one `find`.
6. **`platform/AGENTS.md` says `platform/web`'s properties are unit tests and *do*
   run under `test`.** The brief's D3 line names the four integration suites; a
   reader could reasonably infer web has one too. It does not — `platform/web/src`
   holds `main` and `test` only.
7. **`build-checks/AGENTS.md` phrases the `ImportedSetGuard` obligation as
   "register what a new rule governs"** and then describes what the guard actually
   asserts (a floor on the import, every module by name, the six controllers, and
   which sets are still empty). The brief's phrase "register every new rule's class
   set" implies an API that does not exist; the obligation it describes is real, so
   it is stated as an obligation rather than as a method call.
8. **`services/orca-runtime/AGENTS.md` closes on the Flowable schema-migration
   contradiction** (`phase-0-report.md` §7.4). It was not in the brief's D3 list,
   but it is an open contradiction sitting directly under anyone about to write
   code in that service, and the file would be misleading without it.
9. **Commit messages state the measured line counts.** Cheap, and it makes budget
   regression visible in `git log` rather than only in a review.
10. **This report quotes the `@` grep and the overlap measurement verbatim**
    rather than summarising them, because §5 asks for the grep to be shown and a
    summary of a verification is not the verification.

---

## 7 · Found wrong or contradictory — reported, not corrected

Nothing in this section was edited. Items 1 and 2 are the ones that changed what a
deliverable says.

### 7.1 · The brief attributes a rule to `ErrorEnvelopeRule` that it does not enforce

**The brief's D1.3 says:** *"controllers implement generated interfaces and return
the shared envelope (`ErrorEnvelopeRule`)"*.

**`ErrorEnvelopeRule` has exactly one test**, `controllersReturnTheEnvelope`. It
inspects return types of public instance methods on `@RestController` classes.
**Nothing in it — or in any other check — asserts that a controller implements a
generated interface.** The only trace of the idea in `build-checks` is a
description string on `ImportedSetGuard.theControllersAreVisible`, which counts
classes named `HealthController` and expects 6; it does not inspect what they
implement.

The practice does hold today: all six controllers declare `implements HealthApi`,
verified by grep. But it holds by **convention plus the compiler** — once a
controller declares `implements HealthApi`, a contract change breaks compilation.
Nothing stops a *new* controller from implementing nothing at all and still
passing every check, provided it returns the envelope.

Given §4 of the brief — *"your §D1.3 lines must match what they enforce, not what
their names suggest"* — `AGENTS.md` states only the envelope constraint on that
row, and puts contract-first in §4 "How to work" as a discipline with a
compile-time consequence rather than as a build check. **This is a gap in
enforcement, not just in wording, and it is the kind the register exists for.**

### 7.2 · `REPOSITORY_GUIDE.md` contradicts itself on the number of build checks

Its own §2 table lists **six** checks (platform purity, module walls, scope seam,
error envelope, retention class, system context) and explains at line 165 why the
sixth was added. But line 191 writes `./gradlew check` as *"the five build
checks"*, and line 217 promises *"five build checks that fail the build when
violated"*. Six rule classes exist. `AGENTS.md` says six.

### 7.3 · `REPOSITORY_GUIDE.md` §2's heading says "seven modules" for `services/`

Line 118: *"`services/` — seven modules, six deployable applications"*.
`settings.gradle.kts` registers **six** service modules and states outright that
`services/orca-media` *"is a README placeholder and is deliberately NOT a Gradle
module"*. Counting it as a module also makes the repository thirteen modules
rather than twelve, which the same guide asserts twice. `phase-0-report.md` §7.5
records that this guide was stale at kickoff and was updated mid-session; this
looks like a residue of the same drift.

### 7.4 · The Phase 0 report's unit-test count has drifted

`phase-0-report.md` §2 says *"50 unit tests"* and §3 attributes *"27 unit"* to
`platform/web`. Measured now: **52** unit tests total, of which `platform/web`
contributes **29** (4 + 9 + 4 + 5 + 7). The integration count still matches exactly
at 31. Small, and a report is a snapshot — recorded because it was found while
counting, not because it indicates anything wrong.

### 7.5 · Only two of the three expected dirty files were dirty

The brief warns that up to three files may carry uncommitted orchestrator edits.
On arrival `docs/ORCA_ORCHESTRATOR_HANDOVER.md` and `docs/REPOSITORY_GUIDE.md`
were modified; `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` was clean. Both dirty files
were left exactly as found — neither is staged in any of the three commits, which
`git status` confirms. No unexpected modification appeared.

### 7.6 · Two documents named in the corpus are not reachable from this repository

`ORCA_ARCHITECTURE.md` §C2 and `phase-0-report.md` §4 both depend on the **ORCA
Data Dictionary** for the closed 18-value retention-class list, and the register
records its two published copies as not identical. It is not in this repository.
`ORCA_ORCHESTRATOR_HANDOVER.md` likewise points at documents under
`Lynxis-Gate/`, which is out of scope for this task. `AGENTS.md` therefore states
what `RetentionClassRule` enforces — that a class is *named* — and does not imply
the closed list is checkable here.

---

## 8 · Fresh-eyes gap list

What a brand-new agent still will **not** find from these files. No fixes
proposed; several of these are open questions, not omissions.

1. **What a `Scope` actually is.** `platform/scope` is deliberately opaque about
   what a dimension means, because naming one would decide NEW-1a by accident. An
   agent asked to add scoping will find the seam, the rule and the default-deny
   behaviour — and no answer to *"scoped by what?"*. That is correct and it will
   still feel like a missing page.
2. **How to write the first real entity, repository or migration.** Phase 0 has no
   JPA entity, no business table and no service-owned migration beyond the
   skeleton. The rules that will govern the first one are all stated; the worked
   example does not exist and cannot be written honestly until it does.
3. **Which retention class to name.** The check demands a non-blank class; the
   closed list lives in a document this repository does not contain, whose two
   published copies disagree. The three names in the source are marked
   PROVISIONAL. An agent will have to pick one and flag it.
4. **Lease durations, payload thresholds, connector body limits, SLA numbers.**
   The architecture states no numbers and the validators are the specification.
   The committed values are marked local. Nothing tells an agent what a site
   should use, because nobody has decided.
5. **How a service registers an outbox consumer, or publishes a view.** Both
   mechanisms are built and property-tested; neither is wired to anything, so
   there is no example to copy. `required-views` and `outbox.consumers` are empty
   by design and deliberately not defaulted.
6. **Whether the frontend lives in this repository.** `REPOSITORY_GUIDE.md` §7
   raises it as an open review question. It is unanswered, and it decides the
   top-level layout.
7. **Who owns `platform/` after Phase 0.** Also `REPOSITORY_GUIDE.md` §7, also
   unanswered. An agent changing a primitive will not find out whose review that
   needs.
8. **The four external contracts fixed by the other side** (§D2) and the frozen
   device boundary (ADR-013). `AGENTS.md` does not mention them; an agent working
   near `orca-edge` could spend a while before discovering a contract is not
   ours to change.
9. **That `ImportedSetGuard.whatIsStillEmptyIsStated` is designed to fail.** It is
   written in `services/orca-runtime/AGENTS.md` and `build-checks/AGENTS.md`, but
   an agent that lands on a red build without reading either will most likely
   "fix" the assertion. This is the single most likely wrong turn these files try
   to prevent, and the prevention is prose, not a check.
10. **Ports 8081–8086 binding on a free machine is still unproven** — carried
    forward unchanged from `phase-0-report.md` §8, and not fixed here.
11. **CI checks are not marked required.** `phase-0-report.md` §8 records it as a
    branch-protection setting. Nothing in the repository enforces that a pull
    request is green, so the rules in `AGENTS.md` are only as binding as the
    person merging.

---

*A gap reported is worth more than a gap filled with a guess.*
