# The operator console — implementation plan (autonomous agent)

**For an autonomous agent with full local access.** Prescriptive: where it says do X,
do X. It does not offer choices, because every "either is fine, say which you chose" in
this programme's history became a round trip.

**Companions, read before starting:** `frontend-console-plan.md` (why these pages, in
this order) · `frontend-from-1x.md` (the visual language and the 1.x defects not carried
forward) · `frontend/.claude/CLAUDE.md` (Angular rules — binding) · root `AGENTS.md`.

> **Executing WP1?** `console-wp1-execution-plan.md` supersedes this document for WP1 and
> the first slice of WP3. It is step-by-step, its commands were executed on 13 Aug 2026,
> and its code was compiled against the installed Angular 22.1.1 rather than remembered.
> This document remains the plan for WP2 and WP4–WP7.

**Standing instruction — the most important line in this document.** If anything here
is wrong, missing, or contradicted by the code: **stop and report it. Do not work
around it.** Every defect found in the last agent-executed plan surfaced because the
agent stopped and asked instead of improvising. A gap reported is worth more than a gap
filled.

**Out of scope, do not build:** the workflow builder and the screen builder (another
developer owns both). You create only a blank placeholder route for the builder —
WP7 — and nothing else. Also out of scope: the kiosk app, i18n, Excel export,
live WebSocket updates.

---

## 1 · Environment — executed in this order, all commands verified 13 Aug 2026

Run every command from the repository root unless stated. **If a step does not produce
what it says, stop and report.**

### 1.1 · Fetch, and know your branch

```bash
git -C . fetch --all --prune && git rev-parse --abbrev-ref HEAD
```

Branch from `develop`: `git checkout develop && git pull && git checkout -b feature/console-<wp>`.
Run `git rev-parse --abbrev-ref HEAD` in the **same command** as every `git add`.

### 1.2 · The stack

```bash
cd deploy && docker compose up -d && docker compose run --rm bootstrap
```

⚠️ `deploy/.env` is machine-local and already exists: **SQL Server is on 21433, Keycloak
on 18080** (not 1433/8080). Do not overwrite `.env` with `.env.example`.

### 1.3 · The three services

Each in its own terminal, and **the exports are not optional** — a service started
without them points at ports nothing listens on:

```bash
export ORCA_DB_URL='jdbc:sqlserver://localhost:21433;databaseName=orca;encrypt=true;trustServerCertificate=true'
export ORCA_OIDC_ISSUER_URI='http://localhost:18080/realms/orca'
```

```bash
./gradlew bootRun -p services/orca-core --args='--spring.profiles.active=local --server.port=18081'
```

```bash
./gradlew bootRun -p services/orca-runtime --args='--spring.profiles.active=local --server.port=18082 --orca.runtime.edge-base-url=http://localhost:18083'
```

```bash
./gradlew bootRun -p services/orca-edge --args='--spring.profiles.active=local --server.port=18083 --orca.edge.runtime-base-url=http://localhost:18082'
```

Health check all three: `for p in 18081 18082 18083; do curl -s -o /dev/null -w "$p %{http_code}\n" http://localhost:$p/actuator/health; done` → three 200s.

### 1.4 · Demo data

```bash
cd deploy && ./demo/seed.sh
```

One site, area, lane, camera, barrier, the `tos` connector, the clerk world
(`usr-demo-clerk`, team, screen `scr-demo-manual`, routing rule).

### 1.5 · The identity link — miss this and EVERY API call returns 401/USER_NOT_LINKED

The realm authenticates people; the platform resolves the token's `sub` to a platform
user through core's operator directory. The demo user must be linked **once per fresh
database**:

```bash
SUB=$(curl -s -X POST http://localhost:18080/realms/orca/protocol/openid-connect/token -d 'grant_type=password&client_id=orca-console&username=clerk&password=clerk&scope=openid' | python3 -c 'import sys,json,base64; t=json.load(sys.stdin)["access_token"]; p=t.split(".")[1]; p+="="*(-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p))["sub"])')
docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Orca!Local2026' -C -No -I -d orca -Q "UPDATE core.user_account SET keycloak_subject = '$SUB' WHERE external_id = 'usr-demo-clerk'"
```

**Verify it took — do not assume the `UPDATE` matched a row.** This was found stale on
13 Aug 2026, with the realm's `sub` and the stored one different, which fails every call:

```bash
echo "token sub : $SUB"
docker exec -i orca-sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Orca!Local2026' -C -No -I -d orca -h -1 -W -Q "SET NOCOUNT ON; SELECT keycloak_subject FROM core.user_account WHERE external_id='usr-demo-clerk'"
```

The two must print the same GUID.

### 1.6 · Frontend

```bash
cd frontend && npm ci && npm run api:generate
```

⚠️ **`npx ng build` on its own FAILS** — `Error: Cannot determine project for command.`
So does `npm run build`. This is a multi-project workspace; each project must be named,
and until `tsconfig.json`'s paths point at library source, **in this order**:

```bash
npx ng build api-client && npx ng build ui-registry && npx ng build console && npx ng build kiosk
```

The order is load-bearing: the paths map `api-client`/`ui-registry` to `./dist/*`, which
is gitignored, so a console file importing either fails to compile until both libraries
have been built.

Dev server, **with the proxy** (§4 trap 1): `npx ng serve console --proxy-config proxy.config.json`.

### 1.7 · Baseline gate — stop if this is not what you see

| Check | Expected |
|---|---|
| `./gradlew check integrationTest --rerun-tasks` (services stopped first) then count from `build/test-results/**/TEST-*.xml` | **42 integration suites / 319 tests / 0 failures** and **43 unit suites / 544 tests / 0 failures** |
| `cd frontend && npx ng build <each of the four>` (§1.6 — a bare `ng build` fails) | all four projects build |
| `npx ng test console --watch=false` and `... kiosk ...` | 2 passed each (⚠️ `ng test api-client` exits 1 — it has no specs) |
| `./deploy/demo/send-plate.py --port 9100 --plate T-BASE-01` then the visit query in `docs/phase-1-demo.md` §7 | visit `COMPLETED` |

You cannot claim you ended green without knowing you started green.

## 2 · What already exists — build against it, do not mock it

**52 API paths are contracted AND implemented.** The typed client is already generated
into `frontend/projects/api-client` from the services' own OpenAPI documents. Use it.
Never hand-write an HTTP call, never invent a DTO.

Available and verified working: work items (list, get, take, takeover, park, assign,
complete, audit) · presence (`me/presence`, `operators/idle`, `operators/activity`) ·
visits (list, get, abort) · lanes (visit, take-next, reset) · users · roles · teams ·
routing rules · screens (identity only) · devices + device catalog · shift/break
templates · settings · workspace grid preferences and saved filters · sites · audit
events · custom entities · entitlements.

Regenerate with `npm run api:generate`. ⚠️ **`npm run api:check` cannot detect drift
while `frontend/` is untracked in git** — its `git diff --exit-code` only compares
tracked files, and zero frontend files are tracked today (verified 13 Aug 2026 by
corrupting a generated file: the gate still exited 0). Until the workspace is committed,
use `git status --porcelain -- projects/api-client/src/lib` after regenerating.

**If an endpoint you need is missing, that is a finding to report — not a reason to write
a mock or call a URL directly.**

## 3 · Work packages

One branch and one review per work package. Each ends with: the app running, the
package's own verification performed against the live stack, and a short report.

### WP1 · Shell and authentication

- Keycloak OIDC (authorization code + PKCE) against realm `orca`, client
  **`orca-console`**, redirect `http://localhost:4200/*`. Use `angular-oauth2-oidc`
  (add it) — do not hand-roll a token flow.
- An `HttpInterceptor` that attaches the bearer token to `/api/**` and, on 401,
  routes to login. It must **not** attach the token to anything else.
- Wire the generated clients' `basePath` to `''` (same origin — the proxy handles dev,
  the reverse proxy handles production).
- The shell in `ui-registry`: sticky white topbar with the three menus of §5, active
  item via `routerLinkActive` (never a manual `module` prop — that is a 1.x defect),
  user menu with logout, presence indicator.
- Routes: `/operations/...`, `/insights/...`, `/administration/...`, plus `404` and an
  error page. **Every feature route is lazy** (`loadComponent`/`loadChildren`).

**Done when:** logging in as `clerk`/`clerk` lands on the queue route, the token is
attached to an API call, and logout returns to Keycloak.

### WP2 · The component set — build only what WP3 consumes

In `ui-registry`, styled with the theme tokens (never raw hex):
`PageShell` (breadcrumb + title + actions slot) · `DataGrid` (columns declared as
`ng-template` per column key, sortable, empty state, loading state) · `Button` ·
`StatusChip` (the status tokens) · `Modal` · `ConfirmDialog` · `Toast` service ·
`EmptyState` · `Timer` (live elapsed).

⚠️ **`ConfirmDialog` and `Toast` are separate components.** 1.x conflates them into one
`Toaster` with eight status modes, imported by 85 files — that is the single worst
component in the old console and it is not carried forward.

**Done when:** each has a unit test; the grid renders 0, 1 and many rows correctly.

### WP3 · The operator's day — the reference slice

Route `/operations/work-items`.

- Two panels: **Queued** and **In progress** (1.x splits them; keep that).
- Columns: work item, lane, plate/visit, queued-or-in-progress elapsed (live `Timer`),
  status chip, actions.
- Actions: **Take** (queued), **Take over** (in progress), **Park**, **Assign** (pick an
  operator from `operators/idle`) — each through `ConfirmDialog`, each followed by a
  resource reload.
- Take navigates to `/operations/work-items/:id`, a **hand-written completion form**
  (a textarea for corrected event data plus Complete/Park). ⚠️ **Do not build the
  data-driven screen renderer** — the screen artifacts do not exist until stream 5.
- The detail page shows the audit trail from `GET .../audit`.
- Refresh: `setInterval` reload every 5s while the page is open. No WebSocket.

**Done when:** you drive a truck to `MANUAL` (§6), the item appears in Queued, Take
moves it to In progress, Complete closes it, the audit shows `TAKE` then `COMPLETE`,
and the visit reads `MANUAL` in the database.

### WP4 · Visits and lanes

`/insights/visits` (grid: plate, lane, status, started, finished; filters: status,
lane, plate, date range; row → detail) and `/operations/lanes/:laneExternalId`
(current visit, abort, reset).

### WP5 · Administration CRUD

`/administration/{users,roles,teams,devices,shift-templates,break-templates,settings,audit-events}`.
Each: a `DataGrid` list page plus a **full-page** create/edit form (1.x uses full pages,
not modals — keep that), signal forms with validation, `ConfirmDialog` on delete.

⚠️ **Do not wire `/me/workspace/grids` column preferences.** Declare columns in the
component. The contract exists; adopting it is a later, separate decision.

### WP6 · Empty, loading and error states

Per-region loading (never 1.x's full-screen blocking overlay), `EmptyState` on every
grid, one error path: an API error becomes a `Toast` and leaves the page usable.

### WP7 · The builder placeholder — a blank page and nothing more

Route `/administration/builder`, lazy, in `console/src/app/builder/`, rendering a single
component that says the builder is under development. **Do not add Foblex Flow, Monaco,
a canvas, or any dependency.** Another developer owns this; you are reserving the slot.

## 4 · Traps — each of these has cost this programme real time

1. **CORS does not exist on the backend.** There is no CORS configuration in any
   service. The dev server must run with `--proxy-config proxy.config.json` (already
   written) so the browser sees one origin. **Do not add CORS configuration to a
   backend service** — in production the console is served same-origin behind the
   reverse proxy, so CORS would be solving a problem that only exists in dev.
2. **The `sub` → platform user link (§1.5)** must be applied after every fresh
   database, or every call fails with `USER_NOT_LINKED`.
3. **The backend authenticates but does not authorize per route** (`PlatformSecurityAutoConfiguration`
   javadoc: "no role mapping, no scope-to-entitlement translation, no per-route
   policy"). Hide actions in the UI where sensible, but **do not claim the backend
   enforces them** and do not invent an authorization scheme. Open register item.
4. **A running service poisons the integration suite** — they share the `runtime`
   schema. Stop the services before `./gradlew integrationTest`.
5. **A running service holds the old classes.** Restart it before verifying a backend
   change.
6. **Tailwind v4 prunes unused theme tokens** — the token block uses `@theme static`
   for that reason. Do not remove `static`.
7. **The two contracts both define `ApiResponse`/`ApiError`**, which is why the client
   is namespaced `runtime.*` / `core.*`. Import from `api-client`, never from a deep
   path.
8. **`ng test` uses Vitest here**, not Karma. `--browsers` requires an extra package;
   plain `npx ng test <project> --watch=false` works.
9. **Do not edit anything under `projects/api-client/src/lib/`** — it is generated and
   CI diffs it.
10. **`sqlcmd` needs `-I`**, or writes to tables with filtered indexes fail with a message
    that names SET options and no table.
11. **The app is zoneless** — no `zone.js` is installed and there is no `polyfills` entry.
    State a template reads must be a signal; a `setInterval` mutating a plain field
    repaints nothing.
12. **An unwired `BASE_PATH` silently defaults** to the OpenAPI document's
    `http://localhost:8081`/`:8082` server URL, bypassing the proxy and failing on the
    CORS that deliberately does not exist. Both `runtime.provideApi('')` and
    `core.provideApi('')` must be in `app.config.ts` — each namespace has its own token.
13. **The proxy is prose-only.** It is not in `angular.json`, and both `launch.json` files
    omit `--proxy-config`. Forgetting it does not give a clean 404: the dev server's SPA
    fallback answers `/api/v1/…` with **200 and the app's own `index.html`**, so the typed
    client fails parsing HTML as JSON. A wired proxy with the services down answers 502 —
    that is how you tell the two apart.

## 5 · Navigation (from 1.x, trimmed to what 2.0 has)

- **Operations** — Work Item Queue · Lanes
- **Insights** — Visits · Completed Work Items
- **Administration** — Users · Roles · Teams · Devices · Shift & Break Templates ·
  Settings · Audit History · Builder (placeholder)

Not carried forward: customer management (one installation = one customer), license
management (2.0 licensing is a lease-based instance limit), sync configuration (cloud
scope), mobile layout manager (portal scope), workflow simulator (stream 5).

## 6 · Verification — you have full local access; use it

You have the database, the services and the demo tooling. **Verify by executing, never
by asserting.** For each work package:

1. **Seed real data through the real path**, not by inserting rows blindly:
   - a completed visit: `./deploy/demo/send-plate.py --port 9100 --plate T-X`
   - a work item: set the route to an unmapped status, send a plate, restore it —
     `docs/phase-1-demo.md` §8c is the exact recipe
   - direct SQL is acceptable for *bulk* rows (many visits for paging), using the
     `sqlcmd` invocation in §1.5 — ⚠️ include `-I`, or writes to tables with filtered
     indexes fail with a message that names SET options and no table.
2. **Drive the UI in a real browser** and confirm the resulting state in the database.
3. **Check the browser console for errors** — a page that renders with a console full
   of errors is not done.
4. `npx ng build && npx ng test <project> --watch=false` green, and the §1.7 backend
   baseline still green at the end.

⚠️ **Never write to the ORCA 1.x database** (port 11433, `OrcaCommercial`). It is
read-only evidence.

## 7 · Decisions you must NOT make — report instead

Per-route authorization · localization · Excel export · live-update transport ·
grid column preferences · anything that changes a backend contract · anything
security-shaped or commercial. Each is the technical lead's or the product owner's.

## 8 · The report each work package ends with

Nine parts (`PARALLEL_STREAM_LAUNCH.md:182-193`): outcome · commits · changed surface ·
evidence (commands actually run, with their output) · what was not built · decisions
made within authority · decisions required from the technical lead · **found wrong —
reported, not silently corrected** · next slice.

Do not commit, push or merge unless explicitly told. No `Co-Authored-By` trailer.
