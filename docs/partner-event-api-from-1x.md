# The partner event API and the inbound dispatch queue — extracted from ORCA 1.x

**⚠️ Provenance: derived from the fielded ORCA 1.x Go code on 9 August 2026, not from a design document.** Every claim below was read out of the source and the load-bearing ones were re-verified by hand. Sources: `services/workflow-connector-service/` (the HTTP surface), `services/event-dispatch-service/` (the background poller), `common/entity/event_dispatch_entity.go`, `common/migration/migration.go` (indexes), and `services/work-flow-executor-service/` (what it does *not* do).

**This is the reference for the DATA and the BEHAVIOUR. The target architecture governs the design.** Where the two disagree, the architecture wins and the disagreement is named below rather than silently resolved.

**Read §0 first.** It is the list of things 2.0 must deliberately do differently, and it is the acceptance criteria for the phase that rebuilds this path.

---

## 0 · The seven inversions — what 2.0 must not carry forward

Each is code-verified, and each is a defect rather than a design choice.

1. **One event can start two workflows.** Deduplication is a `FindPendingDuplicate` read followed by a separate `Create`, with no transaction, no lock and no unique constraint behind it (`internal/services/event_dispatch_service.go:202` then `:241`). Two concurrent retries both see nothing pending and both insert. **2.0: the idempotency primitive already built — a recorded key, claimed atomically, returning the recorded outcome on replay.**

2. **Two services disagree about what the statuses mean.** The HTTP service enforces `PENDING → DISPATCHED → COMPLETED | FAILED` (`event_dispatch_service.go:108-111`). The poller writes `DISPATCHING` and `RETRYING` as well, with no transition guard at all. Neither can transition a row the other has touched: a status update on a `DISPATCHING` row fails its transition check and returns a conflict, permanently. **2.0: one owner of the state machine, one enumeration, enforced in one place.**

3. **Nothing in the platform ever writes `COMPLETED`** — verified by grep: the workflow executor contains **zero** references to `event_dispatch`. The only writer is an external partner calling `PATCH /{uuid}/status`, and nothing obliges them to. Retention deletes only `COMPLETED` and `FAILED` rows (`internal/retention/retention.go:74`). **So every successfully dispatched event accumulates forever, and the table's growth is bounded only by partner goodwill.** A knock-on: replay refuses any event that is not `COMPLETED` or `FAILED` (`event_dispatch_service.go:813`), so in practice only failed events are replayable. **2.0: the platform closes out its own queue rows; retention never depends on an external caller.**

4. **The queue table has no tenant column and no soft delete.** `event_dispatch` carries no `site_id`, no `site_uuid` and no `lane_uuid`, and none of the `is_active` / `is_deleted` pair every other table in that system has (`common/entity/event_dispatch_entity.go:5-19`). Rows are hard-deleted. **2.0: the scope column and its leading index are mandatory, and the build check will insist.**

5. **The payload is an unbounded `text` column** (`event_dispatch_entity.go:11`) holding every inbound partner body, with no size limit anywhere. Combined with inversion 3, this is the single largest uncontrolled growth in the system. **2.0: the payload store and retention class, declared the day the table lands.**

6. **A claimed batch is unbounded and un-reconciled.** The poller marks *every* pending row for an entity as `DISPATCHING` with no limit, then re-selects by `(event_type, entity_id, status)` rather than by the rows it just claimed — so a second instance's in-flight rows come back in this instance's batch. There is no reaper: the code's own comments say an event stranded in `DISPATCHING` is *"NOT retried on the next tick"*. **2.0: the outbox and lease primitives already solve this; the queue consumes them rather than reinventing them.**

7. **A route that cannot be resolved strands the event silently.** The HTTP path returns a clear `400` before writing anything, which is correct. The poller, having already claimed the row, logs and moves on — leaving it `DISPATCHING` forever. Identical configuration fault, opposite observable outcomes. **2.0: one resolution path, one outcome, and an unresolvable event is visible rather than stranded.**

---

## 1 · Two services, asymmetrically split

| | `workflow-connector-service` | `event-dispatch-service` |
|---|---|---|
| HTTP surface | **All ten partner endpoints** | **None** — a health check only |
| Role | Accepts, validates, resolves, stores; can dispatch immediately | Background poller: claims batches and dispatches them |
| Writes `event_dispatch` | Insert, status update, single-row claim | Batch claim, status update |
| Retention | Owns it | None |

Both resolve inbound routes with **byte-identical code that is not shared** — the second copy's own comment says so. 2.0 has one runtime service, so this split does not port; it is described because the two halves disagree (inversion 2) and the disagreement is invisible unless you know there are two.

## 2 · The partner-facing endpoints

Mounted under a configured base route (in practice `/workflowconnector/api/v1`). Registration order matters and is load-bearing: `/submit/bulk` must precede `/submit`, and `/next` must precede `/{uuid}`, because the router takes first match.

| Method | Path | Notes |
|---|---|---|
| POST | `/submit` | One event. Returns **201** `{status: "queued"｜"dispatched", event_uuid}` |
| POST | `/submit/bulk` | A batch. Returns **207** with a per-item result array — deliberately not the standard envelope |
| POST | `/callback` | An external system answers a workflow that is already waiting. Creates **no** queue row |
| GET | `/latest` · `/list` | Query submitted events; `list` pages and sorts |
| GET | `/next` | Atomically claim the highest-priority pending event, for partners that pull |
| GET · PATCH | `/{uuid}` · `/{uuid}/status` | Inspect; report completion or failure |
| POST | `/{uuid}/replay` | Replay, optionally overriding the data |

**Authentication** is one of two token families on the `Authorization` header — an identity-provider bearer token, or an internal token whose claims are checked against the route's name. There is no API-key mechanism, despite the published specification advertising one; a partner integrating from that document gets `401` on every call.

**The response taxonomy is the weak part and is worth redesigning.** Roughly nine distinct `400`s are returned that a caller cannot tell apart programmatically, three of them from a single resolution failure. The lost-claim race returns `404` where `409` is the honest answer.

⚠️ **This surface is no longer frozen.** It was frozen only because fielded customers had integration code written against it; with the product shipping to new clients only, it is free to redesign. The standing advice is to take that freedom **narrowly** — keep the route shape and the envelope, fix the error taxonomy, and take the `409`.

## 3 · The `event_dispatch` table, exactly as it exists

| Column | Type | Note |
|---|---|---|
| `event_dispatch_id` | int, identity | Primary key |
| `event_dispatch_uuid` | varchar(36), not null | External id. **Uniqueness is not guaranteed — see below** |
| `event_type` | varchar(100), not null | Matched against a connector's slug |
| `entity_id` | varchar(100) | **This is the lane code**, despite the name |
| `data` | text | The whole inbound payload, unbounded |
| `priority` | int, default 0 | Lower is more urgent; unset sorts last |
| `status` | varchar(20), not null | See inversion 2 |
| `source_event_id` | varchar(36), nullable | Set on a replay, pointing at the original |
| `created_on` | timestamp, not null | |
| `dispatched_on` | timestamp, nullable | |

**Ten columns. No tenant column, no soft delete, no audit columns.**

⚠️ **The unique index on `event_dispatch_uuid` may not be unique.** The migration checks at runtime for existing duplicates and, if it finds any, creates a **non-unique index with the same name** (`common/migration/migration.go:5725-5731`). So the presence of an index called `UQ_event_dispatch_uuid_Custom` tells you nothing about whether uniqueness is enforced on that database. Any 2.0 design must not assume the identifier is unique in a system it inherits data from — and, since 2.0 takes no data from 1.x, must simply declare it unique from the first migration.

## 4 · How an inbound event finds its workflow

One query, joining six tables, anchored on the connector configuration:

```
connector_config  (slug = event_type, direction = INBOUND, active)
  → connector_node   (INBOUND, active)
  → workflow         (active)
  → workflow_deployments (active)
  → lanes_and_portals    (lane_code = entity_id)
  → areas
```
ordered by lane then deployment, **limit 1**.

**The tenant hazard, and how 1.x handles it.** A lane code is unique only within a site, and this lookup carries no site identity — so the same code at two sites would match twice. A second query counts the distinct matching lanes and, if there is more than one, refuses with a conflict rather than guessing. That fail-closed instinct is right and should survive. What should not survive: the count runs **outside any transaction**, so a configuration change between the two queries can still let an ambiguous route through.

**2.0 has a straightforward advantage here** — one installation serves one customer, and the scope seam puts the site on every read by construction. The ambiguity this guard exists to catch cannot arise in the same way.

## 5 · Dispatch, retry and the response a partner actually gets

**Immediate mode** performs one HTTP call to the executor with no retry, then marks the row dispatched or failed. **Queue mode** polls (default every 30 s, re-reading its configuration each tick), claims a batch, and retries up to three times with 2 s / 4 s / 8 s backoff before marking the row failed.

**Neither call carries an `Authorization` header, and the executor does not require one.** See the private security findings document — that is a live finding on the running system, not a design note.

⚠️ **A `201` does not mean the workflow ran.** The executor accepts the request, hands it to a goroutine and returns immediately, so the answer means only *"accepted"* — and it carries no execution identifier, so a partner has nothing to correlate against afterwards. Polling `/{uuid}` is the only completion channel, and there is no callback or webhook mechanism anywhere in the system (verified by searching for one in both directions).

**One more surprise worth designing against:** if a workflow is already running on that deployment, an inbound event does **not** start a new one — it re-enters the running execution. So two partner events on one lane produce one execution or two depending purely on timing, and the partner receives an identical `201` either way.

## 6 · The outbound half, for symmetry

The same `connector_config` table carries outbound connectors, discriminated by a `direction` column. Outbound rows add a request path, method, an **AES-encrypted `auth` blob** decrypted at call time, a payload template, content type, protocol (REST or SOAP), a SOAP action and a per-connector certificate name.

Two properties to carry forward deliberately:

- **The client is rebuilt on every call when per-connector TLS is configured.** This is the strongest of the known performance findings in that system. 2.0 should pool clients keyed by credential and trust configuration.
- **The outbound call has no retry at all**, while the inbound poller retries four times. That asymmetry is accidental rather than reasoned.

## 7 · What this document cannot tell you

- **Whether any partner actually calls `PATCH /{uuid}/status`.** The entire retention story depends on it, and it can only be answered by looking at a live site's data.
- **The real distribution of payload sizes** in the `data` column — the input to any bound 2.0 chooses.
- **Whether the ambiguous-lane guard has ever fired** in production.

*Extracted 9 August 2026. Companion: `docs/core-config-schema-from-1x.md`, whose translation rules still apply.*
