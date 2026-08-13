# Stream 2 report

Status: WP0-WP4 complete for in-app notifications. Q1 was resolved in code as
database-backed best-effort fan-out. Email/push remain blocked on Q5.

## What was built

### WP0 - runtime modules no longer empty

- Added real classes to `readmodel` and `notify`.
- Watched `ImportedSetGuard.whatIsStillEmptyIsStated` fail before changing it:
  first on `notify` (`expected: 0L but was: 1L`), then on `readmodel`
  (`expected: 0L but was: 15L`).
- Replaced the empty-module guard with positive assertions for all five runtime
  modules.
- Removed both `allowEmptyShould(true)` exemptions from `ModuleWallRule`.

### WP1 - lane-monitor projection

- Added runtime migration `V138__lane_monitor_projection.sql`.
- Added one bounded projection table, `lane_monitor`, keyed by
  `(site_external_id, lane_id)` with a scope-leading board index and a unique
  `(site_external_id, lane_external_id)` index.
- Added contract-first route `POST /api/v1/grids/lane-monitors`.
- Added `readmodel.api.LaneMonitorProjectionPort`, implemented by
  `LaneMonitorService`.
- Wired same-transaction projection updates from:
  - admission: visit started and device event observed;
  - visit completion: completed/manual terminal state;
  - lane reset/abort: failed terminal state;
  - work-item lifecycle: queued, cleared, parked, assigned, completed, failed and SLA
    breach visibility.
- Updated runtime integration fixtures to publish the current `core.topology_lane`
  shape readmodel needs.
- Updated `FlowableAdoptionIT`'s rewind list to drop `lane_monitor`, because every
  runtime migration after the Flowable adoption band must be removed when that test
  reconstructs the old self-migrated-engine state.

### WP2 - remaining grid routes

- Added contract-first grid routes:
  - `POST /api/v1/grids/queue`
  - `POST /api/v1/grids/alerts`
  - `POST /api/v1/grids/completed-work`
  - `POST /api/v1/grids/export`
  - `GET /api/v1/exports/{exportExternalId}`
- Replaced the lane-monitor-only controller with `readmodel.api.GridController`.
- Added `workitem.api.WorkItemGridPort` and implemented it in `WorkItemService`, so
  readmodel can reuse the existing queue ordering without importing
  `workitem.domain` or reading `work_item`.
- Added completed-work reads over terminal work items, ordered newest-first and
  bounded by a completion window.
- Added one alerts route over `lane_monitor`, not the three 1.x route names that
  mapped to one handler.
- Added runtime migration `V139__grid_exports.sql`:
  - scope-leading completed-work index on `work_item`;
  - scoped traffic-growing `grid_export_job` table with provisional retention class
    `grid_export`.
- Added a bounded CSV export job that completes synchronously in this first slice
  and stores the result in readmodel's own table.
- Updated `FlowableAdoptionIT`'s rewind list to drop `grid_export_job`.

### WP3 - durable notifications

- Added contract-first notification routes:
  - `GET /api/v1/notifications`
  - `POST /api/v1/notifications/{notificationExternalId}/read`
  - `POST /api/v1/notifications/ws-ticket`
- Added runtime migration `V140__notifications.sql`.
- Added scoped traffic-growing `notification` with nullable `read_at`; unread state
  is derived from `read_at IS NULL`.
- Did not copy 1.x's partial soft-delete shape. The table has neither `is_active`
  nor `is_deleted`.
- Added scoped traffic-growing `notification_ws_ticket`, storing only a SHA-256
  ticket hash and the consumed timestamp.
- Added `NotificationService`, `NotificationRepository` and
  `NotificationTicketRepository`, with reads through the scope seam.

### WP4 - live channel

- Registered `/api/v1/notifications/ws` as the notification WebSocket endpoint.
- Added a handshake interceptor that requires a valid short-lived ticket issued by
  `POST /api/v1/notifications/ws-ticket`.
- Tickets are single-use by guarded update: a replay, missing ticket or invalid
  ticket fails the handshake.
- Implemented Q1 as database-backed best-effort fan-out:
  - each runtime instance keeps only its own local WebSocket sessions;
  - every instance polls the durable `notification` table under system identity and
    installation scope;
  - matching rows are delivered to local sockets as `notification.created` messages;
  - reconnect uses the durable notification list as the authoritative state.
- Added runtime migration `V141__notification_live_fanout_index.sql`, a
  scope-leading sequence index for the poller.

## What was not built

- Email and push delivery. Q5 remains open.
- Notification publication is available as a domain service for in-app rows, but no
  other module has been wired to publish operational notifications yet.

## Decisions made

- Projection update trigger: same transaction as the fact being projected, through a
  `readmodel.api` port. A committed admission/work-item/visit close is therefore not
  briefly ahead of the board. The trade is one extra local database write on the gate
  transaction. No network call and no live fan-out decision are involved.
- Projection failure behavior: if readmodel cannot write during the source
  transaction, the source transaction rolls back too. This is deliberate for WP1's
  property: truth and board cannot split.
- Retention for `lane_monitor`: no retention class. The table is bounded one row per
  lane and is a rebuildable projection, not a traffic-growing system of record.
- Indicator set: the first slice carries the four inherited fixed indicators
  (`gate_arm`, `red_lamp`, `orange_lamp`, `green_lamp`) plus a JSON loop snapshot
  from observed device-event attributes. This is not a configurable indicator
  registry and does not resolve the open runtime device-state replica question.
- Traffic color mapping is projection display state only:
  `ACTIVE -> BLUE`, `COMPLETED -> GREEN`, `MANUAL -> AMBER`, `FAILED -> RED`,
  `CLEAR -> NEUTRAL`.
- Queue grid ownership: the `/grids/queue` route is readmodel's console surface,
  but the data and ordering are workitem's. It delegates through
  `WorkItemGridPort` rather than building a second queue projection.
- Completed-work ordering: newest completed first, over a bounded completion
  window. When the caller supplies no lower bound, this slice uses the last 24
  hours to avoid an accidental all-history read.
- Q3 route split: one alerts route. The 1.x `/lanealerts`, `/lanemonitors` and
  `/sitemonitors` names were one behavior with different permissions; Stream 2
  does not reproduce three route names without three different projections.
- Export shape: the first backend slice completes exports synchronously and stores
  the bounded CSV in `grid_export_job`. It is still exposed as a job because the
  architecture names an export job and this keeps the contract expandable without
  changing callers.
- Retention for `grid_export_job`: provisional `grid_export`. It is traffic-growing
  convenience data, not a system of record; Stream 4 still owns the final closed
  retention catalog.
- Notification unread state: `read_at` is the only state. Marking an already-read
  notification is idempotent and preserves the first read timestamp.
- Notification deletion: no soft-delete columns were inherited from 1.x. Retention,
  not an `is_active` habit, is the lifecycle boundary.
- Q4 live/persisted correlation: the durable row is the source of truth. Future live
  delivery carries the persisted `notificationExternalId`; this stream does not
  allow a rowless live notification.
- Q1 live fan-out: database-backed polling is the chosen shape. Sticky routing alone
  would keep a browser on one runtime instance but would not move a notification
  created on another instance to that socket. A broker would violate the on-site
  "none to install" rule. Polling the durable table adds bounded database reads and
  at most poll-interval live latency, while preserving the architecture's rule that
  WebSocket push is best effort and the query is authoritative on reconnect.
- Live fan-out startup cursor: a runtime instance starts at the current notification
  table tail, so old inbox rows are not replayed as new live messages after a
  restart. Anything missed while disconnected is still visible through
  `GET /api/v1/notifications`.
- WebSocket ticket TTL: `orca.runtime.notifications.ws-ticket-ttl` defaults to
  `60s` and startup rejects non-positive values. This is security-shaped and should
  be reviewed with the live fan-out deployment settings.
- Live fan-out poll settings: `orca.runtime.notifications.live-poll-interval`
  defaults to `1s`, and `orca.runtime.notifications.live-poll-batch-size` defaults
  to `100` with startup rejecting non-positive batch sizes.
- WebSocket ticket storage: only the SHA-256 hash is stored. A redeemed ticket is
  consumed by guarded update, not by pre-check then update.
- Retention for `notification`: provisional `notification`. It is traffic-growing
  operator inbox data; Stream 4 still owns the final closed retention catalog.
- Retention for `notification_ws_ticket`: provisional `notification_ws_ticket`. It is
  short-lived credential bookkeeping; Stream 4 still owns the final closed retention
  catalog.

## The six inversions

- One copy: one `lane_monitor` table and one lane-monitor route. `ModuleWallRule`
  now prevents modules from reaching into each other's `domain` or `persistence`
  packages with no empty-rule exemption.
- Maintained, not recomputed: `LaneMonitorProjectionIT` drives admission, device
  observations and work-item transitions and asserts the board changes without a
  request-time join.
- Scoped by construction: `LaneMonitorProjectionIT.boardReadsAreScopedByConstruction`
  proves one site's scope does not return another site's lane, and `ScopeIndexRule`
  passed over `V138`.
- No in-memory-only subscription maps: local WebSocket sessions are held in memory
  because a socket exists in one process, but cross-instance fan-out does not depend
  on those maps. Every instance polls durable notification rows and delivers matches
  to its local sessions.
- No query-parameter WebSocket identity: `NotificationLifecycleIT` proves a socket
  cannot open with a raw identity, without a ticket, or by replaying a ticket. The
  query parameter is a short-lived opaque ticket, not `userUUID`.
- Three legacy routes for one handler: implemented as one alerts route. The focused
  `GridRoutesIT.alertsGridUsesOneProjectionRouteOrderedByLanePriority` proves the
  single route returns the distinct alert board in priority order.

## Verification

- Baseline before edits:
  `./gradlew.bat check integrationTest --rerun-tasks` passed, counted
  `unit=60 integration=240 total=300`.
- WP0 guard observed failing:
  `:build-checks:test --tests ImportedSetGuard.whatIsStillEmptyIsStated --rerun-tasks`
  failed on `notify`, then on `readmodel`.
- Final WP0 guard:
  `:build-checks:test --tests ImportedSetGuard.runtimeModuleRuleSetsArePopulated --rerun-tasks`
  passed.
- Compile:
  `:services:orca-runtime:compileIntegrationTestJava --rerun-tasks` passed.
- New WP1 suite:
  `:services:orca-runtime:integrationTest --tests com.lynxis.orca.runtime.readmodel.LaneMonitorProjectionIT --rerun-tasks`
  passed, 5 tests.
- Affected runtime suites:
  `AdmissionThroughHttpIT`, `RuntimeRestartIT`, `VisitLifecycleIT`,
  `WorkItemLifecycleIT`, `WorkItemSlaIT`, `WorkItemPresenceIT` and
  `WorkItemRoutingIT` passed.
- Build checks:
  `./gradlew.bat check --rerun-tasks` passed.
- First full integration rerun:
  failed in `FlowableAdoptionIT` because `lane_monitor` was not in its rewind list.
  Fixed by dropping `lane_monitor` in that fixture.
- Recheck:
  `:services:orca-runtime:integrationTest --tests com.lynxis.orca.runtime.schema.FlowableAdoptionIT --rerun-tasks`
  passed.
- Full documented verification:
  `./gradlew.bat check integrationTest --rerun-tasks` passed, counted
  `unit=60 integration=245 total=305`.
- Build:
  `./gradlew.bat build --rerun-tasks` passed.
- WP2 compile:
  `:services:orca-runtime:compileJava --rerun-tasks` passed after the expanded
  OpenAPI contract generated the new grid interface.
- WP2 integration compile:
  `:services:orca-runtime:compileIntegrationTestJava --rerun-tasks` passed.
- New WP2 suite:
  `:services:orca-runtime:integrationTest --tests com.lynxis.orca.runtime.readmodel.GridRoutesIT --rerun-tasks`
  passed, 4 tests.
- WP2 build checks:
  `./gradlew.bat check --rerun-tasks` passed.
- First WP2 full documented rerun:
  `./gradlew.bat check integrationTest --rerun-tasks` hit the tool timeout after
  10 minutes, leaving a Gradle daemon. The daemon was stopped with
  `./gradlew.bat --stop` before rerunning.
- WP2 full documented verification:
  `./gradlew.bat check integrationTest --rerun-tasks` passed on rerun, counted
  `unit=60 integration=249 total=309`.
- WP2 build:
  `./gradlew.bat build --rerun-tasks` passed.
- WP3/WP4 integration compile:
  `:services:orca-runtime:compileIntegrationTestJava --rerun-tasks` passed.
- New WP3/WP4 suite:
  `:services:orca-runtime:integrationTest --tests com.lynxis.orca.runtime.notify.NotificationLifecycleIT --rerun-tasks`
  passed, 2 tests.
- WP3/WP4 build checks:
  `./gradlew.bat check --rerun-tasks` passed.
- WP3/WP4 full documented verification:
  `./gradlew.bat check integrationTest --rerun-tasks` passed, counted
  `unit=60 integration=251 total=311`.
- WP3/WP4 build:
  `./gradlew.bat build --rerun-tasks` passed.
- First post-WP3/WP4 live demo:
  failed after the camera ACK. Runtime returned 500 while reading
  `core.topology_lane` because readmodel selected `is_primary`; the real core view
  exposes `site_is_primary`.
- Topology-contract fix:
  `LaneMonitorRepository` and the runtime fixtures now use `site_is_primary`.
- Recheck after the topology fix:
  `:services:orca-runtime:integrationTest --tests com.lynxis.orca.runtime.readmodel.LaneMonitorProjectionIT --rerun-tasks`
  passed, 5 tests.
- Phase 1 demo after the topology fix:
  passed on offset ports `18081`-`18083`. Plate `T-S2-FIXED` ACKed; visit
  `vis-6a4c5d17-af74-4679-938d-677695971495` reached `COMPLETED`; `lane_monitor`
  showed `LANE-DEMO-01 COMPLETED GREEN` for the same visit external id.
- Final full documented verification after the topology fix:
  `./gradlew.bat check integrationTest --rerun-tasks` passed, counted
  `unit=60 integration=251 total=311`.
- Final build after the topology fix:
  `./gradlew.bat build --rerun-tasks` passed.
- All six services booted after the topology fix:
  `core`, `runtime`, `edge`, `portal`, `sync` and `fleet` answered
  `/actuator/health` with HTTP 200 on offset ports `18081`-`18086`.
- Isolation check after the topology fix:
  `docker compose run --rm verify-isolation` passed, `PASS - 36 checks`.
- Q1 focused suite:
  `:services:orca-runtime:integrationTest --tests com.lynxis.orca.runtime.notify.NotificationLifecycleIT --rerun-tasks`
  passed, 3 tests, including a simulated second runtime instance whose local socket
  received a notification created through the shared durable table.
- Q1 build checks:
  `./gradlew.bat check --rerun-tasks` passed. This includes `SystemContextRule`
  over the new scheduled live fan-out poller.
- First Q1 full rerun:
  failed because the live poller initialized its cursor by reading `notification`
  during bean creation. The schema adoption tests intentionally boot runtime before
  later Stream 2 tables exist, so background live delivery cannot make application
  context creation depend on that table read. Fixed by tailing the cursor on the
  first scheduled run instead.
- Q1 schema recheck:
  `:services:orca-runtime:integrationTest --tests com.lynxis.orca.runtime.schema.FlowableAdoptionIT --tests com.lynxis.orca.runtime.schema.FlowableSchemaUnderFlywayIT --rerun-tasks`
  passed.
- Final full documented verification after Q1:
  `./gradlew.bat check integrationTest --rerun-tasks` passed, counted
  `unit=60 integration=252 total=312`.
- Final build after Q1:
  `./gradlew.bat build --rerun-tasks` passed.

## Found wrong or drifted

- The onboarding test count is drifted. It says 236 tests; this checkout ran 300
  before WP1, 305 after WP1, 309 after WP2, 311 after WP3/WP4 and 312 after Q1.
- Older integration fixtures published a three-column `core.topology_lane`; WP1
  needs the current Phase 2 shape with site, area and lane display fields.
- Runtime's readmodel query and the widened runtime fixtures initially used
  `is_primary`; core's real `V102__topology_views.sql` publishes the column as
  `site_is_primary`. The integration suite passed because the fixtures copied the
  same wrong alias. The live demo caught it; the query and fixtures now match the
  core view.
- `FlowableAdoptionIT`'s comment was already correct that every runtime migration
  numbered after the Flowable adoption band must be dropped during rewind; the code
  needed the new `V138` and `V139` tables added.
- On this Windows shell, `python` is 3.9 and cannot run `send-plate.py`'s
  `str | None` annotations; `py -3.11` works. PowerShell also needed
  `PYTHONIOENCODING=utf-8` for the script's arrow output.
- Docker Desktop stopped during verification; it was restarted and the compose
  stack was brought back up before rerunning integration tests and live checks.
