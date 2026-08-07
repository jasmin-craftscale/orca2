# ORCA — AI Context Brief

**7 August 2026 · For a dedicated build session · Companion to `docs/phase-0-brief.md`, same rules of engagement**

This brief makes the repository **AI-native**: any coding agent — Claude Code, Codex, Cursor, or one that does not exist yet — starts a session already knowing what this repository is, which rules are load-bearing, and where the truth lives. The product owner has approved the shape; your job is to execute it exactly and prove what you wrote is true.

**The one-sentence theory of this task:** instructions drift and get ignored; this repository's real guardrails are the build checks that fail the build. Context files are the *third* layer — a lean, accurate **map with hard rules** that points into documents and checks that already exist. You are not writing new architecture prose. You are writing the map.

---

## 1 · Principles — read these twice

1. **One canonical file.** `AGENTS.md` at the repository root carries all content. Codex and Cursor read `AGENTS.md` natively (root **and** nested; closest file wins). Claude Code reads `CLAUDE.md`, and its own documentation prescribes exactly the adapter this brief specifies: a `CLAUDE.md` whose first line is the `@AGENTS.md` import. One file to keep honest; every tool served. Two divergent copies of an instruction is how this programme's earlier corpus rotted; it was deleted for that reason.
2. **A map, not a retelling.** The architecture is ~19,000 words and already written. If you find yourself summarising it, stop — link the section instead. Every summary is a future stale copy.
3. **Every hard rule cites its enforcement.** A rule an agent must follow is stated next to the build check that fails when it is broken. A rule with no check is labelled *convention* so nobody mistakes prose for enforcement.
4. **Executed, not asserted.** Every command you write into a context file, you have run. Every class, path, and rule name you cite, you have confirmed exists. The verification table in §5 is the proof.
5. **Lean budgets are part of the design.** Anthropic's guidance is to keep an instruction file **under 200 lines** — longer files measurably reduce adherence — and imported files load into context in full at launch. Cursor's guidance caps rules at 500 lines and says *reference files rather than copying content*. The budgets in §3 follow the stricter number and are acceptance criteria, not suggestions.
6. **Write rules concrete enough to verify.** "Run `./gradlew check integrationTest` before claiming done" — not "test your changes". If a rule cannot be checked, rewrite it until it can.

**Normative references** (consulted 7 Aug 2026 — consult them again if a format detail matters):
- The AGENTS.md standard: https://agents.md/ — plain markdown, no required sections; nested files supported, closest wins
- Claude Code memory & imports: https://code.claude.com/docs/en/memory — the `@AGENTS.md` adapter pattern, import rules, nested-file loading, the under-200-lines guidance
- Claude Code settings & permissions: https://code.claude.com/docs/en/settings — the `permissions.allow`/`deny` rule syntax used in §3·D4
- Cursor rules: https://cursor.com/docs/context/rules — native `AGENTS.md` support, root and nested

## 2 · Ground rules

| Rule | Why |
|---|---|
| **Never invent a resolution to an open question.** If something is unspecified, `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` says whether it is undecided or forgotten. A gap reported is worth more than a gap filled | The most repeated failure of this programme, and the most important sentence you will put into the files themselves |
| **No code changes.** This task creates context files, one settings file, and a report. It does not touch `platform/`, `services/`, `build-checks/` source, contracts, migrations, or Gradle files | Scope is the deliverable list in §3, nothing else |
| **Do not edit the existing corpus.** `ORCA_ARCHITECTURE.md`, the register, the handover, the Phase 0 report, `PLATFORM_PRIMITIVES.md`, `REPOSITORY_GUIDE.md` are inputs, read-only | Anything you find wrong in them goes in your report, not into an edit |
| **Expect a dirty working tree.** Up to three files may carry uncommitted orchestrator edits when you start: `docs/ORCA_ORCHESTRATOR_HANDOVER.md`, `docs/REPOSITORY_GUIDE.md`, `docs/ORCA_OPEN_QUESTIONS_REGISTER.md`. Leave them exactly as they are — do not commit, revert, or edit them. **Stage only files this brief creates.** Any *other* unexpected modification: stop and put it in the report | Another session owns those edits |
| **`~/Documents/Projects/lynxis/Lynxis-Gate` is out of scope entirely** — do not read from it, and its corpus copies are not yours to sync | The orchestrator session owns corpus sync |
| **Verify by executing.** Run every command before writing it down; `grep`/`ls` every name before citing it | A context file that lies is worse than no context file |
| If anything in the repository contradicts this brief, **stop and record it in the report** rather than adapting silently | The brief may be wrong; silent adaptation hides that |

## 3 · Deliverables

### D1 · `AGENTS.md` — the canonical context file (root)

**Budget: ≤ 200 lines.** Tool-agnostic — never mention a specific AI product by name in it. Required sections, in this order:

1. **What this repository is.** Three or four sentences: ORCA 2.0, gate automation for logistics facilities, rewrite of a Go system this repo does not contain, Phase 0 foundations built and verified. State the two laws verbatim: *the gate must keep working when other things do not; the site's processes belong to the site.*
2. **Where the truth lives.** A short table mapping question → document: target design and guarantees → `docs/ORCA_ARCHITECTURE.md` (read §B10 first — it is the acceptance criteria); what is deliberately unsettled → `docs/ORCA_OPEN_QUESTIONS_REGISTER.md`; the five primitives and the pattern behind each → `docs/PLATFORM_PRIMITIVES.md`; repository layout → `docs/REPOSITORY_GUIDE.md`; what Phase 0 built and decided → `docs/phase-0-report.md`. Close the section with this, verbatim: **"If something is unspecified, check the register before concluding it was forgotten. Never invent a resolution — a gap reported is worth more than a gap filled."**
3. **Hard rules, each citing its enforcement.** One line per rule, naming the check class in `build-checks/src/test/java/com/lynxis/orca/checks/`: no domain nouns in `platform/` (`PlatformPurityRule`); no module touches another module's `persistence` or `domain` (`ModuleWallRule`); every query goes through the scope seam — no Spring Data repositories, no `JdbcTemplate`/`EntityManager` in services (`ScopeSeamRule`); controllers implement generated interfaces and return the shared envelope (`ErrorEnvelopeRule`); every `@Entity` declares `@PersistentTable` with growth, traffic-growing tables name a `@RetentionClass` (`RetentionClassRule`); every `@Scheduled` entry point enters `SystemContext` (`SystemContextRule`); no service imports another service's internals (also `ModuleWallRule` — it carries three tests, not two). Plus the two enforced outside ArchUnit: a service reaches only its own schema — the database login enforces it; internal endpoints live under `/internal/**` behind the per-installation shared credential (ADR-011), and the identity provider authenticates people only.
4. **How to work.** Contract-first: edit the service's OpenAPI YAML, regenerate, implement the interface — never controller-first. Tests prove properties, not paths — include the canonical example verbatim: *"'The outbox writes a row' is not a test — 'killing the process between the two writes leaves neither' is."* Build checks land **with** the code they govern, never after. New conventions arrive with a new check or are labelled convention.
5. **Commands.** Only commands you have executed: full build, unit tests, **the warning that plain `test` does not run the four platform property suites — use `check` plus `integrationTest`**, the local stack (`deploy/`), running one service with the `local` profile and why the credential guard refuses to start without it, bootstrap. Note the Java toolchain is auto-provisioned and dependency names come from the version catalog (Spring Boot 4 renamed starters).
6. **Decision discipline.** Settled decisions live in `ORCA_ARCHITECTURE.md` §D1 and are not reopened in code. Current programme scope: **on-site system first — `orca-portal`, `orca-sync`, `orca-fleet` stay Phase 0 skeletons until cloud scope opens.** Anything security-shaped, commercial, or scope-changing is the product owner's call: surface it, do not settle it.
7. **Definition of done for substantive work.** Build green including checks and integration tests; a property test for every guarantee touched; and a short written report: what was built, what could not be, every decision the specification did not dictate, anything that looked wrong — reported, not silently corrected.
8. **A closing maintenance note, three lines:** this file is the single source of truth and the adapter files import it; the ≤200-line budget is deliberate — adherence drops as instruction files grow; every `@`-prefixed token in this file stays inside backticks, because some agents parse a bare `@path` as a file import.

⚠️ **The `@` gotcha — this is a hard requirement.** Claude Code parses `@path` tokens in loaded instruction files as *file imports*, skipping only code spans and fenced blocks. `AGENTS.md` is loaded through that parser via the D2 import. Therefore **every `@`-prefixed token in every context file — `@Entity`, `@Scheduled`, `@PersistentTable`, `@RetentionClass`, `@RestController`, any annotation, any path you mention without wanting it imported — must sit inside backticks.** The only bare `@` allowed in any deliverable is the `@AGENTS.md` import line itself in the `CLAUDE.md` adapters. Verify this with a grep (§5).

### D2 · `CLAUDE.md` — root adapter (the pattern Claude Code's own docs prescribe)

Exactly this content, nothing more:

```markdown
@AGENTS.md

Claude Code specifics: full verification is `./gradlew check integrationTest`
(plain `test` skips the platform property suites). Permission allowlist for
this repository lives in `.claude/settings.json`.
```

(A symlink would also work but requires Administrator rights or Developer Mode on Windows, and this team has Windows machines — use the import.)

### D3 · Nested scope files — three `AGENTS.md` + three one-line `CLAUDE.md` adapters

Directory-scoped instructions, loaded only when an agent works there. Codex and Cursor pick up nested `AGENTS.md` natively; **Claude Code auto-loads nested `CLAUDE.md` only**, so each directory gets both: the content in `AGENTS.md` (**≤ 80 lines each**), and beside it a `CLAUDE.md` containing exactly the single line `@AGENTS.md` (the import resolves relative to the file that contains it, so this picks up the sibling). Same map-not-retelling discipline; the `@`-in-backticks rule applies here too.

- **`platform/AGENTS.md`** — `platform/` holds primitives, never domain (a domain noun here fails `PlatformPurityRule`); each primitive exists to make a specific defect impossible — changing one means its property tests still *prove the property* (name the four integration suites and that they need `integrationTest`); the annotations `@PersistentTable`/`@RetentionClass`/`Growth` live in `platform/scope` and are read by the retention check; `platform/web` carries the envelope, `SystemContext`, and the ADR-011 internal-call filter.
- **`services/orca-runtime/AGENTS.md`** — the one decomposed service: `execution`, `workitem`, `integration`, `notify`, `readmodel`; module walls are enforced (`ModuleWallRule`, both `persistence` and `domain`); modules are currently empty by design — Phase 0 put no business logic anywhere; cross-module data needs go through `readmodel` projections, never another module's tables; admission (architecture §C2) is the property this service's design turns on.
- **`build-checks/AGENTS.md`** — tests only, output is build failures; **a new rule does not count until it has been watched to fail** — introduce a deliberate violation, see the build stop, revert (the repository's own history: the platform-purity matcher was once silently never firing, fixed in `ac0eb0d`); register every new rule's class set with `ImportedSetGuard`; empty governed sets must be declared (`allowEmptyShould(true)`) and recorded, never left implicit.

### D4 · `.claude/settings.json` — permission allowlist

Exactly this content (schema per the settings reference in §1; `Bash(pattern *)` is prefix-plus-wildcard):

```json
{
  "permissions": {
    "allow": [
      "Bash(./gradlew *)",
      "Bash(git status)",
      "Bash(git status *)",
      "Bash(git diff *)",
      "Bash(git log *)",
      "Bash(git show *)",
      "Bash(git ls-files *)",
      "Bash(docker compose *)"
    ],
    "deny": [
      "Read(./deploy/.env)"
    ]
  }
}
```

Nothing that pushes, deletes, or leaves the repository is auto-allowed; the local secrets file is denied to the Read tool, and no Bash rule auto-allows reading it — any such attempt still prompts the human. Verify it parses (`python3 -m json.tool`).

### D5 · `docs/ai-context-report.md` — the report (§7)

(Lowercase, flat in `docs/` — the repository's convention for work artifacts, set by `dd451ef`: phase and task files sit beside the corpus with a descriptive kebab-case name, like `phase-0-brief.md` and `phase-0-report.md`.)

### Explicitly out of scope

- **`.cursor/rules/*.mdc`** — Cursor reads `AGENTS.md` natively at root and nested; an `.mdc` rule referencing the same file would load the content **twice** into context. Do not create one.
- `.github/copilot-instructions.md`, Claude Code skills, README changes, corpus edits, anything in Lynxis-Gate, build or CI changes. If you believe one of these is needed, that belief goes in the report.

## 4 · Source material, in reading order

1. `docs/REPOSITORY_GUIDE.md` — layout, build, how it runs (carries uncommitted fixes; read the working-tree version)
2. `docs/PLATFORM_PRIMITIVES.md` — what each primitive prevents, named patterns
3. `docs/phase-0-report.md` — what was built, §5 decisions, §7 known contradictions
4. `build-checks/src/test/java/com/lynxis/orca/checks/*.java` — the actual rules; your §D1.3 lines must match what they enforce, not what their names suggest
5. `docs/ORCA_ARCHITECTURE.md` — §B10, §B4–B6, §C2, §D1; skim the rest
6. `docs/ORCA_OPEN_QUESTIONS_REGISTER.md` — header, blocking row, closed table
7. `gradle/libs.versions.toml`, root `build.gradle.kts`, one service's `build.gradle.kts` + `application.yaml`, `deploy/README.md` — for §D1.5, before running the commands

## 5 · Verification — run it, record it

| # | Check | How |
|---|---|---|
| 1 | Every command in `AGENTS.md` §5 executes successfully | Run each; paste exit status into the report |
| 2 | Every cited class, rule, path, and doc section exists | `grep`/`ls` each; no citation ships unverified |
| 3 | Budgets hold | `wc -l` on every deliverable: D1 ≤ 200, each nested `AGENTS.md` ≤ 80 |
| 4 | The `@` gotcha holds | Grep every deliverable for `@`: the only bare (un-backticked) `@` in any file is the `@AGENTS.md` line in the four `CLAUDE.md` adapters. Show the grep in the report |
| 5 | `CLAUDE.md` adapters are byte-exact | Root matches the D2 block; each nested one is the single line `@AGENTS.md` |
| 6 | `.claude/settings.json` parses and matches D4 | `python3 -m json.tool`; `diff` against the brief's block |
| 7 | No retelling | For each file, attest: no paragraph duplicates corpus prose; links point instead |
| 8 | The build still passes untouched | `./gradlew build` after all commits — this task changed no code, so a failure means something is wrong; investigate before concluding |
| 9 | Fresh-eyes gap list | End of report: what would a brand-new agent *still* not find from these files? Honest list, no fixes |

## 6 · Commit plan

Three commits, staged by explicit path only:

1. This brief + `AGENTS.md` + `CLAUDE.md` — *"AI context: one canonical instruction file, Claude Code adapter"*
2. The three nested `AGENTS.md` + their three `CLAUDE.md` adapters + `.claude/settings.json` — *"AI context: directory-scoped rules and the permission allowlist"*
3. `docs/ai-context-report.md` — *"AI context report: what was written, what was verified, what is still missing"*

## 7 · The report

`docs/ai-context-report.md`: what was built per deliverable · the §5 table with real results · every decision this brief did not dictate, with reasoning · anything found wrong or contradictory in the repository or corpus while verifying — **reported, never corrected** · the fresh-eyes gap list.

---

*A gap reported is worth more than a gap filled with a guess.*
