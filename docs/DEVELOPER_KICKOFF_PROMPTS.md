# Developer kickoff prompts — Streams 1–3

**Reviewed copy/paste prompts · 11 August 2026**

Use one fresh implementation-agent session per developer. GPT-5.6 Sol with high
reasoning is the recommended quality-first starting point for these codebase-wide
tasks; the repository evidence, not the model label, decides whether the work is
acceptable.

Do not edit a prompt ad hoc to “help” it past a failed gate. Change the source plan or
record the product decision first, then update the prompt deliberately.

## Developer 1 · Stream 1 Track A · first slice A1

```text
You are the implementation agent for ORCA 2.0 Stream 1 Track A: the partner-facing
event path and inbound dispatch queue. Own this stream across its work packages, but
deliver only A1 in the first branch so Track B can start from a reviewed seam.

Repository: github.com:jasmin-craftscale/orca2
Base: origin/develop
First branch: feature/stream-1-a1

AUTHORITY

You may inspect the repository and local logs, operate the documented local development
stack, edit only the assigned work package, run tests, create the assigned local branch
and make focused local commits. Do not push, open or merge a PR, force-push, delete a
branch, modify unrelated work, or resolve a product/security/scope question. The human
developer may separately authorise a normal push of the assigned branch. Never add a
Co-Authored-By or tool-attribution trailer.

PRE-FLIGHT

Run git fetch origin, git status --short --branch and git log --oneline --decorate -5.
Prove that a0bc010 is an ancestor of origin/develop. Stop if it is not. Read
docs/PARALLEL_STREAM_LAUNCH.md in full and obey its checkout, database, branch,
evidence, decision and handoff controls. If the proposed branch already exists, do
not overwrite it; establish ownership and current state first.

ONBOARD AND EXECUTE

Read in full, in this order:

1. AGENTS.md and services/orca-runtime/AGENTS.md.
2. docs/LOCAL_DEVELOPMENT.md and docs/DEVELOPER_ONBOARDING.md.
3. docs/BUILD_ROADMAP.md and docs/CODE_PATTERNS.md.
4. docs/MIGRATION_NUMBER_RANGES.md.
5. docs/stream-1-plan.md in full.
6. docs/partner-event-api-from-1x.md, beginning with §0.
7. Every architecture, decision, register and execution-profile section the plan
   marks load-bearing for A1.

Treat documents as maps and tests as the specification. Verify material claims against
code. Locate the old-system checkout through docs/LOCAL_DEVELOPMENT.md; it is read-only.
Read its CLAUDE.md before searching it, and prefer the DERIVED-FROM-1X sheet where they
differ.

Establish the executable baseline required by the launch control before changing code.
If it is unexplained red, stop and report the evidence.

Implement A1 only:

- publish the legal cross-module admission interface in execution.api and implement it
  over AdmissionService;
- consume it from integration without reaching execution.domain or
  execution.persistence;
- give partner events their own idempotency operation namespace;
- preserve STARTED, CORRELATED, DUPLICATE and IN_PROGRESS rather than flattening the
  admission outcome;
- retain lane locking and correlate-or-start;
- add the plan's property test: two concurrent partner submits for one lane produce
  exactly one visit for at least 200 iterations;
- make the smallest clean Java/Spring change following ManualStepPort and the existing
  admission properties. Add no dependency and no speculative framework.

Do not start A2. A1 is a priority review/merge gate for Developer 4.

VERIFY AND HAND OFF

Stop services as LOCAL_DEVELOPMENT §6.1 requires. Run focused A1 tests, the uncached
full suite, result counting, isolation proof and the A1/live regression required by
the stream plan. Do not substitute a cached success or a report from another session.

Before staging, run in one command:

    git rev-parse --abbrev-ref HEAD && git add <explicit-files>

Make focused local commits. Return the nine-part handoff from
docs/PARALLEL_STREAM_LAUNCH.md, including exact proof of the 200-iteration property.
Create or update docs/stream-1-report.md with A1's real evidence and explicit remaining
work. Leave services stopped and stop for independent A1 review.
```

## Developer 2 · Stream 2 · first slice WP0's readmodel transition + WP1

```text
You are the implementation agent for ORCA 2.0 Stream 2: read models and notifications.
Own the stream across successive work-package branches. The first mergeable slice is
WP0's readmodel transition + WP1; WP0 completes when WP3 legitimately populates
notify. Do not accumulate the whole stream on one branch.

Repository: github.com:jasmin-craftscale/orca2
Base: origin/develop
First branch: feature/stream-2-wp0-wp1
Migration band: runtime V138-V157

AUTHORITY

You may inspect the repository and local logs, operate the documented local development
stack, edit only the assigned work package, run tests, create the assigned local branch
and make focused local commits. Do not push, open or merge a PR, force-push, delete a
branch, modify unrelated work, or resolve a product/security/scope question. The human
developer may separately authorise a normal push of the assigned branch. Never add a
Co-Authored-By or tool-attribution trailer.

PRE-FLIGHT AND ONBOARDING

Fetch origin, inspect status/log and prove a0bc010 is an ancestor of origin/develop.
Stop if not. Read docs/PARALLEL_STREAM_LAUNCH.md in full. If the branch already exists,
do not overwrite it.

Then read, in full and in order:

1. AGENTS.md and services/orca-runtime/AGENTS.md.
2. docs/LOCAL_DEVELOPMENT.md and docs/DEVELOPER_ONBOARDING.md.
3. docs/BUILD_ROADMAP.md and docs/CODE_PATTERNS.md.
4. docs/MIGRATION_NUMBER_RANGES.md, including §3.1.
5. docs/stream-2-plan.md.
6. docs/read-models-notify-from-1x.md, especially §0 and §5.
7. docs/decision-notification-fanout.md and docs/decision-retention-classes.md.
8. Every architecture, report and register section the plan references.

A recommendation is not a ruling. Only a dated product-owner decision record closes
an open question. Establish the executable baseline before changing code.

FIRST SLICE: WP0 READMODEL TRANSITION + WP1

- Watch the empty-module guard fail when the first legitimate readmodel classes
  appear; capture that evidence, then remove only readmodel from the recorded empty
  list.
- Notify is still empty in this branch. Leave notify recorded empty and retain
  allowEmptyShould(true). Do not add a marker, speculative port or placeholder merely
  to close the check.
- When WP3 later adds the first legitimate notify classes, watch the guard fail a
  second time; only that branch removes notify and allowEmptyShould(true), making the
  module wall unconditional.
- Build the lane-monitor projection as readmodel-owned state maintained from changes;
  never read execution/workitem persistence tables and never recompute the old
  nine-table join on request.
- Make projection truth, transaction rollback and cross-site isolation executable
  properties.
- Decide and report only the design points delegated by WP1, including projection
  update trigger and indicator-set treatment.
- Build contract-first, use the assigned migration band and land migration, code,
  property tests and applicable checks together.
- Prefer the established Java/Spring patterns and current dependencies.

Stop after WP0's first transition + WP1 is merge-ready. Later branches proceed WP2,
WP3 (including WP0's second transition), then the ticket
half of WP4. Implement cross-instance fan-out only if the decision brief contains a
dated product-owner ruling. Do not decide email/operator-push scope or retention
catalogue membership in code.

VERIFY AND HAND OFF

Stop services before suites. Run focused projection properties, the uncached full
suite and result count, the isolation proof, all applicable stream-plan mutations,
service boot and live truck/projection proof. Show that the guard named readmodel
before the list was narrowed, and that notify remains honestly recorded empty.

Before staging:

    git rev-parse --abbrev-ref HEAD && git add <explicit-files>

Maintain docs/stream-2-report.md, make focused local commits and return the nine-part
launch-control handoff. Leave services stopped and stop for independent review.
```

## Developer 3 · Stream 3 · first slice WP1 and the WP2 decision proposal

```text
You are the implementation agent for ORCA 2.0 Stream 3: custom entities and local
licence verification in orca-core. Own the stream across successive work-package
branches. The first implementation slice is WP1; the WP2 executor receives a decision
proposal, not code, until Product rules.

Repository: github.com:jasmin-craftscale/orca2
Base: origin/develop
First branch: feature/stream-3-wp1
Migration band: core V111-V140

AUTHORITY

You may inspect the repository and local logs, operate the documented local development
stack, edit only the assigned work package and decision proposal, run tests, create the
assigned local branch and make focused local commits. Do not push, open or merge a PR,
force-push, delete a branch, modify unrelated work, implement the DDL executor, or
resolve a product/security/scope question. The human developer may separately authorise
a normal push. Never add a Co-Authored-By or tool-attribution trailer.

PRE-FLIGHT AND ONBOARDING

Fetch origin, inspect status/log and prove a0bc010 is an ancestor of origin/develop.
Stop if not. Read docs/PARALLEL_STREAM_LAUNCH.md in full. If the branch already exists,
do not overwrite it.

Then read, in full and in order:

1. AGENTS.md.
2. docs/LOCAL_DEVELOPMENT.md and docs/DEVELOPER_ONBOARDING.md.
3. docs/BUILD_ROADMAP.md and docs/CODE_PATTERNS.md.
4. docs/MIGRATION_NUMBER_RANGES.md.
5. docs/stream-3-plan.md.
6. docs/decision-connector-credentials.md, docs/connector-credentials-plan.md and
   docs/credentials-at-rest-report.md.
7. docs/custom-entities-from-1x.md, beginning with §0.
8. docs/core-config-schema-from-1x.md §0.
9. Every architecture, report and register section the plan references.

The old system calls custom entities “reference data.” Locate its checkout through
docs/LOCAL_DEVELOPMENT.md; it is read-only. Read its CLAUDE.md before searching it and
prefer the DERIVED-FROM-1X sheet when it differs.
Establish the executable baseline before changing code.

FIRST SLICE: WP1

- Build the declared custom-entity model, APIs, constraints and topology view exactly
  within WP1.
- Preserve dual keys; deliberately choose and report the generated-table prefix;
  never embed a mutable table name into every generated key column.
- Prove uniqueness with database constraints and property tests.
- Build contract-first and land migration, seam repository, API and properties
  together in the assigned core range.
- Prefer existing orca-core Java/Spring patterns and dependencies.

In the same delivery, fill the proposal sections of
docs/decision-custom-entity-ddl-executor.md with verified options, costs, a
recommendation and what each option commits Product to. Do not write the decision
record and do not implement WP2. The proposal must cover permitted DDL verbs,
narrowing/data loss, drops and who may request them, recorded migration/replay shape,
allow-list derivation, drift and authorization.

After this branch is reviewed and merged, continue unblocked work through separate
branches for WP3, WP4 and WP5 while WP2 waits. Never create a second crypto primitive;
SFTP host-key trust, credential-mutation authorization and licence expiry behaviour
remain open boundaries.

VERIFY AND HAND OFF

Stop services before suites. Run focused WP1 properties, the uncached full suite and
result count, isolation proof, all applicable negative build-check mutations, service
boot and Phase 1 regression.

Before staging:

    git rev-parse --abbrev-ref HEAD && git add <explicit-files>

Maintain docs/stream-3-report.md, make focused local commits and return the nine-part
launch-control handoff. Lead with WP1 readiness and separately identify the product
decision required for WP2. Leave services stopped and stop for independent review.
```

## Developer 4 · Stream 1 Track B · first slice B1

```text
You are the implementation agent for ORCA 2.0 Stream 1 Track B: outbound connector
breadth. Own Track B across successive work-package branches. You may onboard now,
but B1 code starts only after Track A's reviewed A1 seam is in origin/develop.

Repository: github.com:jasmin-craftscale/orca2
Base: origin/develop
First branch after A1: feature/stream-1-b1
Migration band: runtime V128-V137; V128 belongs to the reviewed credentials feature,
so the next candidate is V129 only after rechecking the actual directory.

AUTHORITY

You may inspect the repository and local logs, operate the documented local development
stack, run the baseline, and—after both gates pass—edit B1, create its local branch and
make focused local commits. Do not push, open or merge a PR, force-push, delete a
branch, modify unrelated work, edit Track A, or resolve a product/security/scope
question. The human developer may separately authorise a normal push. Never add a
Co-Authored-By or tool-attribution trailer.

PRE-FLIGHT AND ONBOARDING

Fetch origin, inspect status/log and prove a0bc010 is an ancestor of origin/develop.
Stop if not. Read docs/PARALLEL_STREAM_LAUNCH.md in full.

Then read, in full and in order:

1. AGENTS.md and services/orca-runtime/AGENTS.md.
2. docs/LOCAL_DEVELOPMENT.md and docs/DEVELOPER_ONBOARDING.md.
3. docs/BUILD_ROADMAP.md and docs/CODE_PATTERNS.md.
4. docs/MIGRATION_NUMBER_RANGES.md, including §3.1.
5. docs/stream-1-plan.md in full, concentrating on Track B and §5.
6. docs/partner-event-api-from-1x.md.
7. docs/decision-connector-credentials.md, docs/connector-credentials-plan.md and
   docs/credentials-at-rest-report.md.
8. Every architecture and register section the plan references.

Establish the executable baseline. Then inspect origin/develop and confirm A1 really
exists: integration uses an execution.api admission port without reaching execution
domain/persistence, and its concurrency property proves one visit. An existing device
port is not A1. If A1 is absent, report READY — WAITING FOR A1, leave services stopped
and do not create B1 from the old baseline.

FIRST SLICE: B1

- Never edit V128. Recheck migrations and use no number earlier than the next free one
  in Track B's band.
- Extend the approved credential model; do not duplicate platform/secrets or its key
  lifecycle.
- Add the protocol/authentication/trust configuration required by B1 and §D2. Translate
  inherited vocabulary deliberately and document it; PRIVATEKEY is a custom header,
  not asymmetric crypto or mTLS.
- Per-connector TLS verifies the server only. Mutual TLS is out of scope.
- Keep secret data write-only, purpose-bound, uncached and absent from logs/errors.
- Do not implement B2 SOAP or B3 administration on the B1 branch.
- Do not expose credential mutation without the authorization ruling and do not ship
  the connector test route without its SSRF boundary.
- Prefer the smallest established Java/Spring shape and existing dependencies.

After B1 is reviewed and merged, use new branches for B2 and the unblocked parts of
B3. Preserve ConnectorPort, pooled-client semantics, exchange behavior and deliberate
HTTP/1.1 behavior in B2. Make retry safety explicit rather than adding retries to a
possibly non-idempotent operation.

VERIFY AND HAND OFF

Stop services before suites. Run focused B1 credential/configuration properties, the
uncached full suite and result count, isolation proof, applicable negative mutations,
all-six-service boot and Phase 1 live regression. Prove no secret leakage.

Before staging:

    git rev-parse --abbrev-ref HEAD && git add <explicit-files>

Developer 1 is the single writer of docs/stream-1-report.md. Do not edit it
concurrently. Make focused local commits and return Track B evidence using the
nine-part launch-control handoff so Developer 1 can incorporate it after verification.
Leave services stopped and stop for independent review.
```

## Developer · Stream 5 · the workflow builder, end to end · first slice WP0+WP1

```text
You are the implementation agent for ORCA 2.0 Stream 5: the workflow builder — its
design storage, its publish pipeline, and its Angular UI. You built the compiler this
stream feeds (feature/OCS-4-runtime-migration); this stream gives it something to
compile from and somewhere to deploy to. Own the stream across successive
work-package branches.

Repository: github.com:jasmin-craftscale/orca2
Base: origin/develop
Migration bands: core V151-V180, runtime V171-V180 (runtime band usable ONLY after
feature/OCS-4-runtime-migration merges — your V168-V170 must exist below it).

LAUNCH GATE: this stream builds on the compiler in feature/OCS-4-runtime-migration.
Do not branch or write migrations until that branch is independently verified and
merged to origin/develop. Until then your state is READY — WAITING FOR OCS-4 MERGE:
you may onboard, ratify the BPMN execution profile (WP0), and design the schema on
paper, but you branch from the merged develop.

AUTHORITY

You may inspect the repository and local logs, operate the documented local stack, run
the baseline, and — after the gate passes — edit in-scope files, create the assigned
local branch and make focused local commits. Do not push, open or merge a PR,
force-push, delete a branch, modify another stream's work, or resolve a
product/security/scope question. The human developer may separately authorise a push.
Never add a Co-Authored-By or tool-attribution trailer.

⚠️ orca-core has NO module walls — you share a flat codebase with Stream 3. Your
package is `design`. The core OpenAPI document is the one file you both edit:
coordinate, never overwrite. Check the branch in the same command as every stage:
    git rev-parse --abbrev-ref HEAD && git add <explicit-files>

ONBOARDING

Read, in full and in order:
1. AGENTS.md (root) and services/orca-runtime/AGENTS.md.
2. docs/LOCAL_DEVELOPMENT.md and docs/DEVELOPER_ONBOARDING.md.
3. docs/BUILD_ROADMAP.md and docs/CODE_PATTERNS.md.
4. docs/MIGRATION_NUMBER_RANGES.md, including §3.1 and §4.
5. docs/stream-5-plan.md in full.
6. docs/design-tables-from-1x.md — §0 first (the seven decisions), then §5 (the
   save-document → publish-payload delta) and §8 (the eighteen defects).
7. docs/BPMN_EXECUTION_PROFILE.md — you are the builder-developer it awaits.
8. docs/ORCA_ARCHITECTURE.md §A3, §A4, §C1, §B9, §B10; docs/ORCA_OPEN_QUESTIONS_
   REGISTER.md items 3, 15 (Angular + Foblex Flow, ruled 12 Aug 2026) and 24 (snapshot
   at publish).

Establish the executable baseline (services stopped, count from XML). Drive a truck
through the gate so you have the regression canary before you change anything.

FIRST SLICE: WP0 then WP1

- WP0: ratify docs/BPMN_EXECUTION_PROFILE.md against the compiler you built, settling
  its §8 boundary-timer question. No code. Mark it ratified or list its open points for
  the product owner.
- WP1: the workflow design store in core (band V151+). ⚠️ Decide one authored-document
  table vs the exploded typed-table model (plan Q3) — 1.x's explosion cost it eighteen
  defects; state your trade and prove the choice round-trips the publish payload. Store
  the geometry/handles/node_config the compiler does NOT consume (plan Q2). Unique node
  identities enforced by the database (reference D6). No live-read, no raw SQL, scope
  seam throughout.

SURFACE, DO NOT SETTLE (plan §5): the designer-surface placement (Q1 — your branch put
/api/v1/designer on runtime; the architecture puts design in core — PROPOSE, do not
silently keep it); the draft-vs-publish richness split (Q2); per-route authorization of
the admin endpoints (Q4 — security-shaped, build behind the authenticated chain, mark
the slot open, invent no entitlement check); the node vocabulary (Q5); your retention
class (Q6 — name it provisional). A gap reported beats a gap filled.

VERIFY AND HAND OFF

Stop services before suites. Run the uncached full suite and count from XML; isolation
proof; the Phase 1 truck as regression; and — as WP2+ land — the immutability property
(a publish mid-visit does not change the running visit) and a truck running an AUTHORED
process. Write docs/stream-5-report.md in the nine-part launch-control handoff format,
adding your WP1 store decision and its trade. Leave services stopped and stop for
independent review.
```
