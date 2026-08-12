# Parallel stream launch control

**For the product owner, four developers and the implementation agents they drive ·
11 August 2026**

This document controls how Streams 1–3 start and integrate. It does not replace a
stream plan, an ADR or the open-questions register. A stream plan defines the work;
this document defines the launch gates, ownership boundaries and evidence needed to
let four people work without turning parallelism into branch, database or decision
conflicts.

The companion copy/paste prompts are in
`docs/DEVELOPER_KICKOFF_PROMPTS.md`.

## 1 · Gates before anybody implements

### 1.1 · The verified credentials feature is on the shared baseline

All four sessions branch from `origin/develop`. The exact reviewed credentials tip is
`a0bc010`. Do not replace this gate with “the files look present”: the commit is the
unit that was independently reviewed and executed.

```bash
git fetch origin
git merge-base --is-ancestor a0bc010 origin/develop
```

Exit `0` means the gate passes. Any other result means stop: merge and push
`feature/credentials-at-rest` first, then repeat the check. A squash that loses the
reviewed commit identity also fails this gate and needs fresh verification.

### 1.2 · Every developer has an isolated checkout

No two developers work in the same working tree. Existing changes and untracked files
belong to their owner and are never swept into a stream commit. A branch is created
from the latest `origin/develop`, not from somebody's moving local `develop`.

If the proposed branch already exists locally or remotely, inspect it and establish
ownership before doing anything. Never overwrite, delete or force-update it merely to
make the kickoff command work.

### 1.3 · The database is isolated or validation is serialised

The integration suite and running services share the runtime schema. Two developers
may not validate against the same ORCA database at the same time. Prefer one local
stack per developer. If a database or host must be shared, reserve an exclusive
validation window and run only one of these at a time:

- `check integrationTest`;
- any booted ORCA service;
- the gate demo or direct endpoint traffic;
- bootstrap, schema reset or isolation verification.

Do not invent an undocumented multi-stack port/database arrangement. Use
`docs/LOCAL_DEVELOPMENT.md` and `docs/phase-1-demo.md`; otherwise serialise.

## 2 · Assignment and first mergeable slice

| Developer | Stream ownership | First branch | First delivery | Start condition |
|---|---|---|---|---|
| **1** | Stream 1 Track A | `feature/stream-1-a1` | A1 admission seam only | Credentials gate passes |
| **2** | Stream 2 | `feature/stream-2-wp0-wp1` | WP0's readmodel transition + WP1 | Credentials gate passes |
| **3** | Stream 3 | `feature/stream-3-wp1` | WP1 declared model + WP2 decision proposal | Credentials gate passes |
| **4** | Stream 1 Track B — **newly staffed 12 Aug 2026** (Selvedin moved to Stream 5) | `feature/stream-1-b1` | B1 connector configuration | Credentials gate passes **and A1 is merged** |
| **5 · Selvedin** | **Stream 5 · workflow builder** (design storage + publish pipeline + Angular builder UI) | per `docs/stream-5-plan.md` | WP0 profile ratification + WP1 design store | ✅ **OCS-4 compiler merged to `develop` 12 Aug 2026** — gate open, pending the orchestrator's suite re-verification of merged `develop` |

**Track B is staffed by a fifth developer** (product-owner decision, 12 Aug 2026). Its
gate is unchanged — credentials on `develop` **and** A1 merged — so Developer 4 onboards
and studies B1 while A1 is in review, exactly as originally sequenced. Developer 5
(Selvedin) owns Stream 5 end to end and is the single writer of `docs/stream-5-report.md`.

Use one work package per branch and review, except where the plan explicitly makes
two inseparable (`Stream 2 WP0's readmodel transition + WP1`). WP0 completes later
when WP3 adds the first real `notify` classes. After a work package merges, fetch the new
`origin/develop` and create the next branch from it. This keeps reviewable diffs small
and makes cross-stream changes arrive continuously instead of in four late, enormous
merges.

## 3 · Decision state agents must not reinterpret

| Subject | State at launch | Implementation consequence |
|---|---|---|
| Connector/SFTP credentials at rest | **CLOSED:** Option A and seven conditions | Consume `platform/secrets`; do not build another crypto lifecycle |
| Cross-instance notification fan-out | **OPEN:** recommendation is not a ruling | Stream 2 builds through durable notification and the ticket half of WP4, then stops unless a dated decision record exists |
| Retention catalogue | **OPEN proposal** | Name provisional classes as the plans instruct; no purge implementation starts |
| Custom-entity DDL executor | **OPEN** | Developer 3 fills `docs/decision-custom-entity-ddl-executor.md`; WP2 code waits for the ruling |
| Connector mutation authorisation | **OPEN** | No authenticated-only public credential-mutation endpoint |
| Connector test-route SSRF boundary | **OPEN** | Do not ship the test route without a ruling/design review |
| SFTP host-key verification | **OPEN** | Never copy `InsecureIgnoreHostKey`; stop the scheduled SFTP slice at the trust boundary |
| Notification email/operator-push scope | **OPEN contradiction** | Do not infer included or excluded from one architecture table |
| Licence expiry behaviour | **OPEN** | Implement only the ruled local verification/concurrent-instance behaviour |

The authority for this table remains the named decision document and
`docs/ORCA_OPEN_QUESTIONS_REGISTER.md`. If either changes, the prompt follows the
new recorded ruling. A recommendation paragraph, agent report or plausible code
shape is not a ruling.

## 4 · Branch, commit and remote-write policy

The kickoff authorises an agent to read, edit in-scope files, run non-destructive
local verification, create the assigned local feature branch and make focused local
commits. It does **not** authorise a push, pull request, merge, force-push, branch
deletion or external write. The developer operating the session may authorise a
normal push to the assigned feature branch after reviewing the local handoff. Nobody
force-pushes a branch another person may be using.

Before every stage operation, mechanically check the branch in the same command:

```bash
git rev-parse --abbrev-ref HEAD && git add <explicit-files>
```

Never stage the whole working tree by habit. Never add a `Co-Authored-By` trailer or
tool attribution.

Recreating an ORCA database is permitted only when it is the developer's confirmed,
disposable local Compose stack and the repository procedure calls for it. It is never
permission to drop a shared, external or customer database.

## 5 · Execution and evidence contract

### Before changing code

1. Read the root and applicable nested `AGENTS.md` files.
2. Read this launch control and the assigned stream plan in full, in its stated
   order. Read every reference the plan marks load-bearing.
3. Verify important documentation claims against code and tests.
4. Bring up the documented local stack and establish a real baseline. Stop the
   services before the suite exactly as `docs/LOCAL_DEVELOPMENT.md` §6.1 requires.
5. Run the uncached full suite and count the XML results; do not trust only
   `BUILD SUCCESSFUL`.
6. Boot the relevant services and execute the existing live gate path before calling
   the baseline understood.

If the clean baseline fails, distinguish environment contention from a product
failure and report it. Do not build a feature on top of an unexplained red baseline.

### For each work package

- contract, migration, implementation, property tests and build checks land together;
- tests prove concurrency, atomicity, scoping and failure properties, not just happy
  path method calls;
- use the assigned migration range and never edit a migration already applied;
- prefer the existing Java/Spring shape and dependency set; avoid speculative
  abstractions and new libraries;
- record anything wrong in the plan rather than silently correcting its intent.

### Before handoff

From the repository root, with services stopped:

```bash
./gradlew check integrationTest --rerun-tasks
```

Count the unit and integration XML results using the command in
`docs/LOCAL_DEVELOPMENT.md` §6.2. Then perform the stream plan's live verification,
boot proof and gate regression. Run the isolation proof from the directory containing
its Compose file:

```bash
(cd deploy && docker compose run --rm verify-isolation)
```

Leave locally booted ORCA services stopped after the handoff so the next suite cannot
be poisoned by an abandoned process.

## 6 · Review and merge cadence

An implementation report is a claim until another session reruns it against the
exact commit.

1. The implementing agent returns a merge-ready local commit and the handoff below.
2. A fresh reviewer inspects the diff against `origin/develop`, traces both directions
   and runs focused tests plus the risk-proportionate full/live gates.
3. Findings go back to the same feature branch. The implementer makes the smallest
   correction and adds a regression property where applicable.
4. The developer authorises push/PR only after the local handoff is coherent.
5. Merge into `develop`; never merge stream branches directly into one another.
6. The next work-package branch starts from the new `origin/develop`.

The handoff format is fixed:

1. **Outcome** — ready or exact blocker.
2. **Commits** — hashes and one-line intent.
3. **Changed surface** — files/modules/contracts/migrations and why.
4. **Evidence** — exact commands, suite/test counts, failures/skips and live proof.
5. **Not built** — explicit exclusions and open decision boundaries.
6. **Decisions made within authority** — choice, cost and proof.
7. **Decisions required from Product** — options/evidence, never a hidden default.
8. **Found wrong** — plan, reference, architecture or code claims disproved by
   execution.
9. **Next slice** — proposed branch and prerequisite.

## 7 · Shared document ownership

- Developer 1 is the single writer of `docs/stream-1-report.md` while Tracks A and B
  run concurrently.
- Developer 4 returns Track B evidence in the fixed handoff format; Developer 1
  incorporates it after the corresponding Track B commit is independently verified.
- Developers 2 and 3 own their respective stream reports.
- Only Developer 3 edits `docs/decision-custom-entity-ddl-executor.md` to submit the
  WP2 proposal. Only the product owner records the ruling.
- Decision briefs are not implementation scratchpads. An agent may add verified
  evidence or an explicitly requested proposal; it may not write a product-owner
  decision.

## 8 · Product-owner launch sequence

1. Put the reviewed credentials commit on `origin/develop` and run the ancestor gate.
2. Give each developer an isolated checkout and their prompt from
   `docs/DEVELOPER_KICKOFF_PROMPTS.md`.
3. Start Developers 1–3. Developer 4 onboards and waits.
4. Independently review and merge A1 as the priority integration event.
5. Tell Developer 4 to refresh `origin/develop`; B1 may now start.
6. While implementation proceeds, rule on notification fan-out and the retention
   catalogue. Review Developer 3's DDL-executor proposal when it arrives.
7. Keep Stream 4 out of implementation until Streams 1–3 finish; it touches every
   schema and depends on their final traffic-growing tables.
