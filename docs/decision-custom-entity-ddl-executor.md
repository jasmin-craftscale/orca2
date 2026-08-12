# Decision intake — controlled custom-entity DDL executor

**For the product owner · Open · Required before Stream 3 WP2 implementation**

## Status

No product-owner ruling is recorded. This is a decision proposal, not an
implementation specification and not authority to begin the executor.

The architecture fixes the outcome: one controlled executor in `orca-core`,
recorded-once declared migrations, allow-listed identifiers, and drift reported
without automatic correction. Product still has to decide how much schema-changing
power that executor receives and under whose authority it acts.

## Verified starting point — 12 August 2026

These are current repository and SQL Server facts, not proposed behaviour.

1. `orca_core` owns the `core` schema. `deploy/bootstrap/V003__grants.sql` also grants
   it database-level `CREATE TABLE` and `CREATE VIEW`; it grants no other service a
   permission on `core`. Against the documented local stack,
   `HAS_PERMS_BY_NAME` returned `1 / 1 / 1` for control of the `core` schema,
   `CREATE TABLE`, and `CREATE VIEW`. The existing login therefore already has the
   technical ability to create and alter objects in its own schema. No wider grant
   is needed for a first executor.
2. SQL Server rolled back a transaction containing `CREATE TABLE` followed by
   `ALTER TABLE ADD`; the probe object was absent after rollback. That proves the
   smallest proposed path can keep its record and DDL atomic. It does not prove that
   every possible online-index, partition, backfill or destructive operation has
   the same transaction behaviour.
3. `platform/scope` is a scoped DML seam over known tables. It has no DDL operation.
   Its internal identifier rule accepts ASCII letters, digits and underscore,
   starting with a letter, and an optional single schema qualifier for tables. The
   WP1 declaration boundary is narrower: exact lower-case
   `[a-z][a-z0-9_]{0,62}`, with `row_id` and `external_id` reserved. There is no
   approved dynamic-SQL compiler today.
4. WP1 reserves opaque table identifiers in the exact shape
   `ce_[0-9a-f]{32}`. Display-name changes never change that identifier. Its future
   internal and external row keys are fixed as `row_id` and `external_id`.
5. The default HTTP security chain authenticates every public route but applies no
   mutation entitlement. A valid user token is currently enough to reach any public
   controller. No existing rule answers who may declare a schema change, approve a
   destructive one, or run the executor.
6. `RetentionClassRule` sees Java `@PersistentTable` declarations and
   `ScopeIndexRule` parses authored SQL migration files. A generated table created at
   runtime would be invisible to both. The retention-class list is also still
   unreconciled. Calling a generated EVENT table traffic-growing without extending
   these mechanisms would create an unenforced promise.
7. Flyway records repository-authored service migrations. It is not a record of
   customer-authored custom-entity changes, and its global service migration number
   is not a safe per-entity ordering mechanism. The executor requires its own
   durable record inside `core`.
8. The installation database uses read-committed snapshot isolation. DDL still
   takes schema modification locks, can block readers, and can be blocked by them.
   The gate must remain operational while this work waits or fails.

## Decisions Product can make independently

The rows below are independent switches. Choosing an option later in this proposal
does not silently answer a row unless the option says so.

| # | Decision | Smallest safe advice | Alternative that remains open |
|---|---|---|---|
| 1 | Permitted DDL | `CREATE TABLE` for a new declaration; `ALTER TABLE ADD` for a new field; create only executor-authored PK, unique, check and supporting indexes. No arbitrary SQL and no alteration of an existing field | Permit staged alteration of type, length, nullability, indexes or constraints under Option B |
| 2 | Narrowing with incompatible data | Refuse narrowing because the first executor has no narrowing verb | A staged preflight/backfill/validation operation, with an explicit rejected-row policy and no implicit truncation or coercion |
| 3 | Drop | Never through the first executor. Retirement or hiding is metadata-only and leaves storage intact | A separately authorised destructive request with impact evidence, backup/restore evidence, an expiry window and named approval |
| 4 | Migration record and replay | Per-entity monotonic sequence; canonical operations plus checksum; unique declaration version and idempotency key; duplicate key plus same checksum returns the recorded outcome, different checksum conflicts | Operator-approved records may add approval and maintenance-window states without changing replay semantics |
| 5 | Identifier source | Only the persisted WP1 table identifier, fixed key names and persisted field identifiers; compile from a closed operation model after revalidation | No viable option permits request text, display names or arbitrary SQL fragments to become identifiers |
| 6 | Drift | Compare the recorded intended shape to `sys.tables`, `sys.columns`, keys, checks and indexes; report missing, unexpected and mismatched objects; never repair | Product may choose whether drift blocks later migrations or merely raises an operational incident |
| 7 | Authority | Separate declaration entitlement from execution. Execution runs as a system identity over an authorised, recorded request and retains the human author | Direct synchronous execution by a specially entitled human, or an operator approval gate, with the additional availability and audit costs below |
| 8 | Retention | No EVENT table becomes executable until its retention class is a closed, validated declaration and generated tables are included in the purge inventory and CI evidence | Product may initially permit REFERENCE only, or defer all execution until the retention catalogue is reconciled |
| 9 | Transactions and coordination | One short transaction for record claim, additive DDL and applied outcome; per-entity database application lock, finite lock/query timeout, one recorded failure, safe replay | Multi-stage jobs for backfills and destructive changes, each checkpointed and resumable under a fencing token |

## Option A — strictly additive executor

This is the smallest safe option and the recommendation for the first ruled release.
It turns a new declaration into one table and later fields into new columns. Existing
columns, constraints and tables are never renamed, narrowed or dropped.

### Permitted operation model

- `CREATE_CUSTOM_ENTITY_TABLE` creates the opaque `ce_*` table, `row_id`,
  `external_id`, declared fields, the primary key, external-id uniqueness, the
  business-key uniqueness rule, shape checks, the site access path, and the approved
  retention/index artifacts. The operation compiler owns every keyword and clause.
- `ADD_CUSTOM_ENTITY_FIELD` adds one field and only widens the shape. A nullable
  field is always safe. A non-null field is permitted only when the table is empty,
  or when Product has separately approved a deterministic default/backfill rule.
  Advice: first release permits nullable additions and empty-table non-null additions
  only.
- No operation accepts a raw SQL fragment. The implementation maps a closed field
  type and modifiers to a closed SQL type/constraint template.

### Migration record, ordering and duplicate requests

Advice is one immutable authored record per entity change containing:

- internal and external keys, entity key, site, declaration version and a monotonic
  per-entity sequence;
- a closed operation kind and canonical operation payload;
- a checksum over that canonical payload;
- caller identity, authoring time and idempotency key;
- execution state, executor instance/fence, start/finish times, bounded error code
  and bounded diagnostic text;
- the resulting physical-shape fingerprint.

Unique constraints hold `(custom_entity_id, sequence)`,
`(custom_entity_id, declaration_version)` and the request idempotency key. Replaying
the same key and checksum returns the recorded terminal or in-flight outcome. The
same key with a different checksum is a conflict. A later sequence cannot overtake
an unfinished earlier one.

### Transaction, locking and recovery

The executor claims the next record and acquires a transaction-owned SQL Server
application lock keyed by entity. It sets finite lock and statement timeouts, runs
the compiled additive DDL, fingerprints the result and marks the record applied in
one transaction. A process death or SQL error rolls back both the DDL and the
applied state; the immutable queued record is safe to retry. Contention on one
custom entity does not justify blocking unrelated entities, though SQL Server may
still serialize conflicting catalog work internally.

Metrics and logs must distinguish queue age, lock wait, execution duration,
timeout, rollback, replay, conflict and drift. Diagnostics contain identifiers and
error categories, not imported row values or secrets. A health endpoint must not
make gate-path health depend on an executor backlog.

### Cost

- **Implementation:** medium. A closed compiler, record/claim state machine,
  application-lock wrapper, shape reader and property suite are new, but there is
  no data mover or destructive planner.
- **Operations:** low to medium. Additive schema locks can still wait, so timeouts,
  backlog alerts and a retry/runbook are required. No maintenance-window machinery
  is required for the supported verbs.
- **Future migration cost:** low. The immutable operation record and compiler can be
  extended with new closed operation types without changing old records.

### Failure modes

- Schema modification lock cannot be acquired before the timeout: record a
  retryable failure; the gate continues on the old shape.
- Database failure or process death inside the transaction: DDL and applied marker
  roll back together; retry the same record.
- Object already exists or physical shape differs: report drift and block; do not
  adopt, reconcile or overwrite it.
- Two instances select the same work: the unique record plus database application
  lock/fence lets one apply and the other return the recorded result.
- Non-null addition encounters rows: refuse before DDL unless the ruled empty/default
  precondition is satisfied.

### Security consequences

The general `orca-core` process already controls its schema, so Option A does not
add a database grant. It does add a dynamic path to exercise existing authority.
The compiler must therefore be the sole code path to DDL, must re-read and revalidate
persisted identifiers, and must reject rather than quote or transform anything
outside the closed shapes. Public authoring and system execution need separate
authorities. The human author remains in the immutable record even when a system
identity executes it.

### Product and operational commitments

- Product accepts that existing fields and entities cannot be physically removed or
  narrowed in the first release.
- Product defines who holds the declaration entitlement and whether execution is
  automatic after authoring or requires approval.
- Operations owns alerts and retry/runbook behaviour, not ad-hoc SQL repair.
- EVENT execution waits for a ruled retention classification and enforceable purge
  inventory; alternatively Product launches execution for REFERENCE entities only.

### Acceptance properties and live evidence

1. Every supported declaration produces one immutable record and exactly one
   physical result; 16 concurrent claimers still apply it once.
2. Replay returns the recorded outcome. Same idempotency key/different checksum is a
   typed conflict and performs no DDL.
3. A rejected table, field or constraint identifier never reaches the SQL execution
   adapter; no object appears in `sys.objects`. Test every character class and
   reserved key, not one injection string.
4. Killing the executor after record claim, during `CREATE TABLE`, and before the
   applied marker leaves no half-applied shape and permits safe replay.
5. A held schema lock reaches the bounded timeout while the Phase 1 gate path still
   completes against its existing tables and views.
6. A second site cannot author, observe, claim or drift-check the first site's
   entity.
7. Runtime can read only the published allow-list view and generated row surface it
   is explicitly granted; it still cannot read core metadata tables.
8. Drift fixtures for missing, extra and changed columns/constraints are reported
   and never corrected.
9. Every executable EVENT table appears in both the retention inventory and CI
   evidence before the first row can be written.
10. The installation isolation proof stays green with no new cross-schema grant.

### Explicit exclusions

No rename, type alteration, narrowing, backfill, drop, truncate, arbitrary index,
arbitrary constraint, arbitrary SQL, automatic drift repair, data import, SFTP,
query builder or licence enforcement.

## Option B — staged schema evolution, including governed destructive change

This option adds an expand/validate/contract planner. A narrowing change creates a
new compatible column or constraint, backfills in bounded batches, validates every
row, switches the declared read shape, and only later contracts the old shape.
A drop follows the same delayed contract path.

Product must separately choose all of the following; “Option B” alone does not:

- whether an incompatible row blocks the change, is quarantined, or may be
  transformed by an explicitly authored rule;
- whether physical drop is ever allowed, and the minimum retirement period;
- which roles may request and approve a destructive stage, whether two distinct
  people are required, and what backup/restore evidence must be attached;
- whether a maintenance window is mandatory and whether queued gate work blocks it;
- when the old field stops being readable and how rollback works after that switch.

### Cost

- **Implementation:** high. It needs a resumable multi-stage planner, batch data
  mover, validation reports, compatibility reads, approval state, cancellation and
  recovery across transactions.
- **Operations:** high. Long-running work, space amplification during expand,
  maintenance windows, blocked DDL, restore drills and staged rollback all need
  runbooks and observability.
- **Future migration cost:** medium to high. Every new type transition requires a
  declared compatibility rule and tests; it cannot fall through to SQL Server casts.

### Failure modes

Backfill can be partially complete, new writes can race the backfill, disk can fill
while both columns exist, validation can fail on only some rows, and a crash can
occur after readers switch but before contraction. A fence and durable checkpoint
must make every stage resumable. Contract/drop cannot share one short atomic
transaction with a long backfill.

### Security consequences

The executor can now destroy or transform customer data. Authentication alone is
not sufficient. Destructive request, approval and execution authorities should be
distinct, recorded and site-scoped. Transformation rules must remain a closed
operation model; a user-supplied SQL expression would be arbitrary code under the
schema owner.

### Product and operational commitments

Product owns the incompatible-row and approval policies. Operations owns maintenance
windows, capacity headroom, restore evidence and stage recovery. Future migrations
must preserve old operation semantics forever because replay and audit read those
immutable records.

### Acceptance properties and live evidence

In addition to every Option A property: inject incompatible rows; prove the ruled
block/quarantine/transform outcome; kill every stage and resume without duplication;
write concurrently during backfill; fill the timeout and disk-space guardrails;
prove an unapproved destructive stage cannot be claimed; restore a dropped fixture
from the required recovery artifact; and keep the live gate path operating during
bounded batches.

### Explicit exclusions

Still no arbitrary SQL, automatic drift repair, cross-schema DDL, hidden truncation,
implicit coercion, or synchronous unbounded backfill inside an HTTP request. Import,
SFTP, query building and licensing remain later work packages.

## Option C — operator-mediated execution

Core authors the same immutable closed operation record, but an explicit operational
command or deployment job runs the executor during an approved window. The public
request never executes DDL synchronously. Core later reports the recorded outcome and
drift.

This is viable when installations require a change ticket or database maintenance
window. It does not mean handing SQL text to an operator: the same closed compiler,
identifier policy, record and replay rules remain mandatory. The command is the
single executor; manual SQL is not a second one.

### Cost

- **Implementation:** medium. The safe compiler and record are still required; a
  secure operational entry point, authentication, packaging and handoff protocol
  replace the background claimer.
- **Operations:** high per change. Every application has latency and human
  coordination; offline sites need a local runbook and artifact lifecycle.
- **Future migration cost:** low to medium technically, high operationally as the
  number of sites and changes grows.

### Failure modes

Requests can remain queued indefinitely, the wrong installation or stale artifact
can be selected, an operator can retry after losing output, and deployed application
code can expect a field not yet applied. Installation binding, checksum, replay and
compatibility gating are required.

### Security consequences

Online service exposure is smaller, but authority moves to an operational identity
and artifact channel. Product must decide who can approve and run it, how the command
authenticates locally, and how artifacts are bound to installation/site. This option
must not create a second credential-encryption primitive or a general SQL console.

### Product and operational commitments

Product accepts delayed schema availability and defines application behaviour while
a declaration is unapplied. Operations accepts a per-change workflow and owns its
availability. Future migrations remain compatible with the same immutable record
format.

### Acceptance properties and live evidence

Prove that an ordinary authenticated API caller cannot execute; an artifact for
another installation/site is refused before DDL; lost output plus replay returns the
recorded outcome; stale or modified checksums conflict; queued/unapplied declarations
remain clearly reported; and all Option A compiler, rollback, drift and retention
properties still hold.

### Explicit exclusions

No manual SQL, no database administrator editing the migration record, no bypass of
site scope, no automatic drift repair, and no claim that authoring means application.

## Advice, not a ruling

Choose **Option A, the strictly additive executor**, for the first release, with
nullable additions (and non-null additions only on a proven-empty table), separate
declaration/execution authorities, and EVENT execution gated on enforceable retention
inventory. It is the smallest option that satisfies the architecture's recorded,
allow-listed, once-only and report-only-drift requirements while keeping long-running
data movement and destructive DDL off the gate installation.

Design the immutable operation record so Option B can add staged operation kinds
later, and retain Option C as an installation policy if customers require operator
approval. Do not pre-build either complexity before Product chooses it.

## Product ruling requested

Please record choices for decisions 1–9, including:

- Option A, B or C as the initial execution mode;
- whether automatic execution after an authorised declaration is allowed;
- the exact declaration and execution/approval authorities;
- the non-null-addition rule;
- whether drift blocks later migrations;
- REFERENCE-only initial execution versus a ruled retention class/inventory for
  EVENT; and
- whether physical drop is permanently forbidden or merely deferred to a later,
  separately approved capability.

## Decision record

**OPEN — product owner has not ruled.**

Only the product owner replaces this line with a dated ruling and its conditions.
