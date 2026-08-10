# The operator read models and the notification hub — extracted from ORCA 1.x

**⚠️ Provenance: derived from the fielded ORCA 1.x Go code on 10 August 2026, not from a design document.** Every claim below was read out of the source and the load-bearing ones were re-verified by hand. Sources: `services/work-flow-executor-service/internal/repository/workflow_executor_repo.go`, `services/admin-service/internal/repository/{lane_monitoring,lane_management,device_management}_repository.go`, `services/shared-apis-service/internal/repository/shared_apis_repository.go`, `services/notification-service/` (the hub), `common/entity/gate_notification_entity.go`.

**This is the reference for the DATA and the BEHAVIOUR. The target architecture governs the design.** Where the two disagree, the architecture wins and the disagreement is named below rather than silently resolved.

**Read §0 first.** It is the list of things 2.0 must deliberately do differently, and it is the acceptance criteria for the stream that rebuilds this path.

---

## 0 · The six inversions — what 2.0 must not carry forward

Each is code-verified, and each is a defect rather than a design choice.

1. **The lane-monitor projection exists in five copies, and they do not agree.** The same nine-table join appears at `workflow_executor_repo.go:3126`, `shared_apis_repository.go:7703`, `lane_monitoring_repository.go:1516`, `device_management_repository.go:3535` and `lane_management_repository.go:737` — identical SELECT lists and identical GROUP BY clauses. **Exactly one of them takes `WITH (NOLOCK)` on every join** (the executor's); the other four take no hint at all. So the same board, read through two different services, can disagree about isolation as well as about content. **2.0: one projection, maintained by one writer, read by everyone. That is what `readmodel` is for.**

2. **⚠️ The projection is rebuilt from nine tables on every workflow step.** `SendNotificationToLaneMonitoring(ctx, laneUUID)` is on the repository interface of *every* executor node type — display, IO, decision, manual input, connector, notification, map-iterator, process-start. Each hop re-runs the join. **2.0: a projection is maintained when something changes, not recomputed when something is asked. This is the single strongest argument for the stream existing.**

3. **The read path has no site predicate.** The query filters on `lanes_and_portals.lane_uuid` and lane type, and joins `sites` only to select its columns. Tenant scoping depends entirely on the caller having been given the right lane UUID. **2.0: the scope seam puts the site on every read by construction, and the build check refuses a scoped table with no index leading with the scope column.**

4. **The notification hub is two in-memory maps.** `UserConnections map[string][]Operators` and `TopicSubscriptions map[string]map[string][]Operators` (`notification_service.go:51-52`), populated by `RegisterOperator`. **Consequences: a restart drops every subscription, and a publish on one instance never reaches a subscriber on another.** ⚠️ **ORCA 2.0 is multi-instance by design and has no broker inside a site** — so this shape cannot be ported at all, not merely improved. See §5, which states the problem without resolving it.

5. **The WebSocket takes its identity from a query parameter.** `/notification/subscribe` is registered on a router that has **no** auth middleware (`routes.go:93`; only `protectedRouter` and `privateKeyRouter` call `.Use(...)`, lines 71 and 73). Inside the handler, `userUUID := r.URL.Query().Get("userUUID")` and the subscribed `topics` are also query parameters; the only validation is that the UUID parses, and the only other gate is `CheckOrigin`, which is a browser control and not an authorization one. `/notification/publish` is likewise on the unauthenticated router. **This is a live finding on the running system, not a design note.** ⚠️ *Its severity assessment lives in a restricted document held by the product owner and the technical lead. It is **not** in either repository you cloned — it is deliberately excluded from version control, so do not go looking for the file. Ask, if you need it; you do not need it to build the replacement.* **2.0: the architecture already names the replacement — `POST /notifications/ws-ticket`, a short-lived ticket obtained over an authenticated call and redeemed to open the socket.**

6. **Three routes, one behaviour.** `/lanealerts/{lane_uuid}`, `/lanemonitors/{lane_uuid}` and `/sitemonitors/{lane_uuid}` all resolve to the same handler, `GetLaneAlertDetails` (`admin-service/routes.go:334, 337, 344`), differing only in the permission name attached to each. **2.0: if three surfaces genuinely differ they get three projections; if they do not, they get one route.** Do not reproduce three names for one query.

---

## 1 · The lane-monitor projection, exactly as it exists

Nine tables, anchored on the lane:

```
lanes_and_portals                        (base; lane_type = 'lane', is_deleted = 0)
  ├─ INNER JOIN sites                    (is_active, is_deleted)
  ├─ INNER JOIN areas                    (is_active, is_deleted)
  ├─ LEFT  JOIN work_items               (lane_id, status = QUEUED, is_active, is_deleted)
  ├─ LEFT  JOIN workflow_executions      (by lane_code — a CODE, not an id — status = RUNNING)
  ├─ LEFT  JOIN lane_status              (workflow_executions.lane_status_id → status_name, color_code)
  ├─ LEFT  JOIN devices                  (device_mode = 'io')
  ├─ LEFT  JOIN device_io_assignments    (loop inputs, and the gate-arm / red / orange / green outputs)
  └─ LEFT  JOIN device_states            (the live value per IO port)
```

**What it produces:** site and area identity, lane identity, `traffic` (the lane status name) and its `color_code`, the queued work item and when it was queued, the loop inputs, and the four indicator values.

**Three properties of the join worth carrying deliberately:**

- **`workflow_executions` is joined on `lane_code`, not on a lane id.** A code is unique only within a site, and this join carries no site predicate — the same hazard the partner-event sheet records for `event_dispatch.entity_id`. The two sheets found it independently in two different paths.
- **The indicator set is hard-coded in the join condition** — `gate_arm`, `red_lamp`, `orange_lamp`, `green_lamp`, plus any port whose name matches `%loop%`. Adding a fifth indicator is a code change in five places.
- **The result is assembled in Go, not in SQL.** The rows come back one per IO port and are folded into a single response object by a loop, with the loop names **concatenated into a comma-separated string** (`notificationResponse.Loops = ... + ", " + ...`). The board's shape is therefore defined by that loop, not by the query.

## 2 · `gate_notifications`, the persisted half

| Column | Type | Note |
|---|---|---|
| `gate_notification_id` | int64, identity | Primary key |
| `gate_notification_uuid` | varchar(36), not null | Unique index `UQ_gate_notifications_gate_notification_uuid` |
| `recipient_user_uuid` | varchar(36), not null | Indexed with the read state, `idx_gate_notif_recipient_read` |
| `site_uuid` | varchar(36), not null | **Indexed.** This table *is* site-scoped, unlike the read path in §1 |
| `notification_type` | varchar(100), not null | Indexed. A free string — the entity's own comment says new types need no schema change |
| `title` | varchar(500), not null | |
| `message` | varchar(2000), not null | |
| `payload_json` | text | Unbounded |
| `read_at` | timestamp, nullable | Null means unread; there is no boolean |
| `created_on` | timestamp, not null | |
| `is_deleted` | bit, not null | |

**Ten columns. ⚠️ Note what is missing: `is_active`.** Every other table in that system carries the `is_active` / `is_deleted` pair, and this one carries only half of it — so the repository-wide "filter both" convention is wrong here specifically. It is called out because a developer reading `Lynxis-Gate/CLAUDE.md` will otherwise write a filter for a column that does not exist.

**`read_at` as a nullable timestamp rather than a flag is the right shape and should survive** — it records *when*, and the unread state falls out of it.

## 3 · How a notification is delivered

Three independent channels, and they are not layered:

| Channel | Mechanism | Persisted? |
|---|---|---|
| **In-app, live** | WebSocket, in-memory topic map (§0.4) | No — the socket is the delivery |
| **In-app, durable** | `gate_notifications` rows | Yes |
| **Web push** | VAPID subscription; `/subscription`, `/delete-subscription`, `/send-push`, all on the authenticated router | Subscription persisted |
| **Email** | `/send-email`, authenticated router | No |

`PublishMessage(ctx, destination []string, publishType, data)` fans out to named destinations; `PublishMessageToTopic(ctx, topic, publishType, data)` fans out to a topic. **Nothing correlates the live channel with the persisted one** — a notification can be delivered over the socket without a row, or written as a row without a delivery, and no code reconciles the two.

## 4 · What 2.0 already has that this replaces

Stated so the stream does not rebuild it:

- **The work-item queue read** already exists and is already ordered by the routing rules' priority (`GET /api/v1/work-items`, `orca-runtime`). It is not part of this stream except where the lane-monitor projection needs queued items beside running visits.
- **`readmodel` and `notify` are empty packages today**, and a build check asserts they are empty. ⚠️ **That check has to be updated in the same commit that fills them** — deliberately, so nobody fills them by accident.
- **`readmodel` is the one sanctioned exception to the module walls.** The lane monitor legitimately needs running visits *beside* queued work items — two modules' data. It must not read another module's tables; it maintains **its own projection built from both**. Getting this wrong is the most likely way this stream breaks the architecture rather than extending it.

## 5 · The question this document cannot answer, and must not

**How a live update reaches a browser when the site runs more than one instance and there is no broker.**

1.x does not answer it — its hub is an in-memory map on one process (§0.4), which is why the question never arose. ORCA 2.0 is multi-instance by design (architecture §B7: *"the platform is designed for more than one instance in every profile"*) and has deliberately no message broker inside a site (ADR-007) — so the two facts collide precisely here, and this stream is where the collision surfaces.

The shape of the answer is a design decision with a cost either way — a database-backed fan-out that every instance polls, a sticky routing rule at the reverse proxy, or something else. ⚠️ **It is not settled in the architecture and it is not this sheet's to settle.** It belongs in the stream's plan as a named decision with options and a recommendation, and it is worth raising with the product owner before the stream starts rather than after, because the answer may touch the reverse proxy, which is not in the repository at all yet (`docs/deployment.md` Part 2, step 8).

## 6 · What this document cannot tell you

- **Which indicators a real site actually configures** beyond the four hard-coded ones, and whether any site has added a fifth by other means.
- **How many notifications a site produces per day**, which is the input to the retention class this stream's tables will need.
- **Whether the five copies of the projection (§0.1) ever return different answers in production**, or whether the NOLOCK divergence has been observed. It can only be answered against a live site.

*Extracted 10 August 2026. Companions: `docs/work-items-schema-from-1x.md` (whose §0 inversions Phase 3 built), `docs/partner-event-api-from-1x.md`, `docs/core-config-schema-from-1x.md` (whose translation rules still apply).*
