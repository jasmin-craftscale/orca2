# Decision proposal — the retention-class catalogue

**For the product owner · 10 August 2026 · Blocks planning stream 4**

## The decision

The architecture says `retention_policy.data_class` is a closed 18-value list, but
does not enumerate it. The source annotations say the catalogue is absent from this
repository and that two published copies disagreed. Neither repository currently
available contains an authoritative enumeration.

That makes the number 18 an unusable requirement, not permission to invent missing
names. The product owner needs to approve a new catalogue from the tables that exist
and the tables already committed by the next streams.

**Recommendation:** preserve the closed-enumeration principle, but remove the
unsupported fixed count. Close the list over named artifact families; adding a class
later remains a deliberate schema and policy change.

This proposal identifies the catalogue. It deliberately does **not** choose retention
durations; those require legal and commercial input.

## Verified inventory

Production migrations contain 94 table declarations:

- **49 ORCA/platform tables**, matched one-for-one today by 49
  `@PersistentTable` declarations;
- **45 Flowable-owned tables**, not annotated and not reached by any current ORCA
  retention policy;
- of the 49 declared tables, **12 are traffic-growing** and 37 are bounded.

The build check proves only that a declared traffic-growing table has a non-blank,
free-form class. It does not prove catalogue membership, a database `CHECK`, a purge
job, a configured window, migration-to-declaration completeness, or that `BOUNDED`
is truthful.

### Current provisional classes

| Current class | Tables |
|---|---|
| `audit` | `core.setting_history`, `core.audit_event`, `runtime.work_item_audit` |
| `device_event` | `edge.event_buffer`, `runtime.execution_event` |
| `device_command` | `edge.command_log` |
| `visit` | `runtime.execution` |
| `work_item` | `runtime.work_item` |
| `presence` | `runtime.user_activity` |
| `outbox` | `outbox` in every service schema |
| `outbox_delivery` | `outbox_delivery` in every service schema |
| `idempotency_record` | `idempotency_record` in every service schema |

That is nine names over 12 logical tables. The three platform tables are physically
present in each of six service schemas. Every name is marked provisional in source.
Only four retention-window settings exist (`device_event`, `visit`,
`device_command`, `audit`).

## Proposed catalogue

### Approve as the baseline

| Proposed class | Covers | Change from today / reason |
|---|---|---|
| `raw_device_event` | `edge.event_buffer` | Separates raw vendor payload from the normalized runtime record; legal may initially give both the same duration |
| `device_event` | `runtime.execution_event` | Normalized event attached to a visit |
| `device_command` | `edge.command_log` | Hardware command and observed outcome |
| `visit` | `runtime.execution` | Business record of a truck's passage |
| `work_item` | `runtime.work_item` | Human work item |
| `work_item_audit` | `runtime.work_item_audit` | Keeps its legal character explicit; see the parent-FK ruling below |
| `audit` | `core.audit_event`, `core.setting_history` | Configuration/administrative ledger |
| `presence` | `runtime.user_activity` | Operator activity; personal data |
| `outbox` | `outbox` **and** `outbox_delivery` | One purge operation already deletes both under one window and one acknowledgement guard |
| `idempotency_record` | platform idempotency rows | Different eligibility and replay horizon from the outbox |
| `partner_event` | stream 1 `event_dispatch` | Inbound partner payload/dispatch history already committed by the plan |
| `notification` | stream 2 durable notification rows | What an operator was told and read state |
| `engine_history` | Flowable-supported history cleanup | Policy identity only; never authority for raw SQL deletion of engine tables |

This is a **13-value baseline**, not a target count. It contains no unnamed reserve.

### Add only if the corresponding design is ruled and built

| Conditional class | Add when |
|---|---|
| `live_signal` | The product owner chooses database-backed notification fan-out and it uses an append-growing signal feed |
| `ephemeral_token` | WebSocket tickets or consumed nonces are persisted as growing rows |
| `projection` | A projection is append-growing. An update-in-place `lane_monitor`/`queue_monitor` is bounded and needs no retention class |

Stream 3's generated custom-entity tables are another explicit boundary. Reference
data is normally bounded, but the DDL executor can create tables the current Java
annotation check cannot see. Its plan must classify any append-ingested shape rather
than assuming generated means bounded.

## The three catalogue changes needing a ruling

### 1. Split raw and normalized device events — recommended

The current code deliberately gives edge and runtime one class so two records of the
same fact cannot drift apart. Register item 28 asks Legal separately about raw device
output and extracted data, however. Raw vendor payload and normalized visit evidence
may have different privacy, volume and evidential value. Naming both classes now
does not force different durations; it avoids a later schema change if Legal gives
different answers.

### 2. Split work-item audit from general audit — recommended

Today `work_item_audit` shares `audit` with settings and administrative changes. It
also has a non-cascading foreign key to `work_item`. If its policy must outlive the
parent, stream 4 needs a roll-up/unlink design before deleting work items. If it dies
with the parent, the two classes may share a duration but still have different purge
eligibility. Product/Legal must say whether this is an independent ledger or part of
the work-item record.

### 3. Merge `outbox_delivery` into `outbox` — recommended

This does not merge tables. The shipped retention operation deletes delivery rows
first and facts second, in one transaction, under the same age window and the same
“all consumers acknowledged” predicate. A second policy name cannot affect that
operation and implies configurability that does not exist.

## Eligibility is separate from duration

A class says which policy window applies. Age alone must never make a live record
purgeable. Stream 4 still needs a table-specific eligibility rule, including:

- outbox facts: every registered consumer acknowledged;
- idempotency rows: completed and past the replay horizon;
- event-buffer rows and commands: terminal, not pending or unknown;
- visits and work items: terminal, with dependent records handled safely;
- presence: closed rows only;
- partner dispatch: terminal, with dead/diagnostic policy explicit;
- Flowable: supported engine cleanup boundaries, never hand-written cross-table
  deletes.

These guards are part of correctness and should be property-tested by holding each
blocking condition open past the configured duration.

## Known gaps stream 4 must include

1. **Flowable history is unbounded today.** The 45 engine tables have neither class
   nor cleanup policy. “Business payloads stay out of Flowable” reduces volume; it
   does not bound history.
2. **The check does not enforce the catalogue.** It must reject an unknown/typo class
   and prove every authored migration table has a growth declaration. Generated
   custom tables need their own equivalent control.
3. **Settings do not cover the catalogue.** Policy configuration and the database
   `CHECK` must be generated from one source so they cannot disagree again.
4. **Future stream decisions change the list.** Fan-out and WebSocket ticket storage
   can add `live_signal`/`ephemeral_token`; the decision briefs name those dependencies
   rather than guessing them here.

## Legal/commercial input still required

Register item 28 asks for minimum retention by artifact family. At minimum obtain
answers for raw device output, normalized/extracted data, visits, work items and
their audits, administrative audit, presence, partner payloads and notifications.
Those answers may be customer-configurable above a legal minimum; that commercial
model is also Product's decision.

No duration is proposed here. “Keep forever” is a duration decision too and must not
become the default merely because the policy is late.

## Ruling requested

1. Replace the unenumerated “exactly 18” assertion with a closed catalogue whose
   count is whatever is approved.
2. Approve, amend or reject the 13 baseline names above.
3. Rule specifically on raw-versus-normalized events, work-item audit identity and
   merging the two outbox classes.
4. Authorise Legal/commercial review for the durations and the status of Flowable
   history as evidence.
5. Confirm that conditional classes are added only when their underlying storage is
   actually selected and built.

Once items 1–3 are ruled, stream 4 can be planned. Durations are required before its
purge jobs can be accepted, but they need not block the engineering plan from being
written.
